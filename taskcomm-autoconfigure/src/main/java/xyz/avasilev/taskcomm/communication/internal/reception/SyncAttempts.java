package xyz.avasilev.taskcomm.communication.internal.reception;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.avasilev.taskcomm.TaskcommException;
import xyz.avasilev.taskcomm.communication.internal.AttemptHistory;
import xyz.avasilev.taskcomm.communication.internal.AttemptLease;
import xyz.avasilev.taskcomm.communication.internal.ClaimedCommunication;
import xyz.avasilev.taskcomm.communication.internal.CommunicationPhase;
import xyz.avasilev.taskcomm.communication.internal.CommunicationRef;
import xyz.avasilev.taskcomm.communication.internal.CommunicationSql;
import xyz.avasilev.taskcomm.communication.internal.Direction;
import xyz.avasilev.taskcomm.communication.internal.creation.CreatedCommunication;
import xyz.avasilev.taskcomm.communication.internal.creation.NewCommunication;
import xyz.avasilev.taskcomm.communication.internal.engine.CommunicationEngine;
import xyz.avasilev.taskcomm.communication.internal.engine.CommunicationWorker;
import xyz.avasilev.taskcomm.communication.internal.guard.SendGuards;
import xyz.avasilev.taskcomm.communication.internal.lifecycle.CommunicationRetries;
import xyz.avasilev.taskcomm.communication.internal.settings.CommunicationSettings;
import xyz.avasilev.taskcomm.execution.internal.UuidV7;

/**
 * Попытки синхронного запуска в потоке вызывающего (docs/adr/0167-communication-run-mode.md) — общее
 * у синхронного приёма и синхронной отправки.
 *
 * <p>Первая попытка открыта под арендой при создании. Неуспех закрывает воркер как у любой попытки —
 * решение о повторе принимает {@link CommunicationRetries}; если повтор есть, поток ждёт паузу и сам
 * забирает следующую попытку: захват синхронные не берёт. Не забрал — например, поток умер, — её
 * закроет подстраховочный поллер. У исходящей перед повтором — ожидание размыкателя и слота
 * ограничителя скорости; истёк предел — {@code FAILED}.
 */
final class SyncAttempts {

    /** Шаг опроса размыкателя, пока ждём. */
    static final Duration STEP = Duration.ofMillis(200);

    /** Состояние коммуникации после неуспеха: есть ли следующая попытка и сколько её ждать. */
    private static final String NEXT = """
            SELECT c.status, c.status_description, c.phase,
                   greatest(extract(epoch FROM c.next_attempt_at - now()), 0) AS wait,
                   (SELECT a.id FROM tc_communication_attempt a
                     WHERE a.execution_created_at = c.execution_created_at AND a.communication_id = c.id
                       AND a.phase = c.phase AND a.finished_at IS NULL AND a.locked_until IS NULL
                     LIMIT 1) AS next_attempt
              FROM tc_communication c
             WHERE c.execution_created_at = :executionCreatedAt AND c.id = :id
            """;

    /** Самозахват: коммуникация — {@code RUNNING}, если она всё ещё синхронная в очереди отправки. */
    private static final String TAKE_COMMUNICATION = """
            UPDATE tc_communication
               SET status = 'RUNNING', updated_at = now()
             WHERE execution_created_at = :executionCreatedAt AND id = :id
               AND status = 'PENDING' AND phase = 'PROCESS' AND run_mode = 'SYNC'
               AND cancel_requested_at IS NULL
            """;

    /** Самозахват: аренда — в открытую незахваченную попытку, как у захвата. */
    private static final String TAKE_ATTEMPT = """
            UPDATE tc_communication_attempt
               SET locked_by = :lockedBy, locked_until = now() + make_interval(secs => :lease),
                   lease_token = :token, started_at = now(), updated_at = now()
             WHERE execution_created_at = :executionCreatedAt AND id = :attemptId
               AND finished_at IS NULL AND locked_until IS NULL
            """;

    /** Открытая попытка, которую уже не начнут: система недоступна дольше предела. */
    private static final String SKIP_OPEN = """
            UPDATE tc_communication_attempt
               SET status = 'SKIPPED', status_description = :reason, finished_at = now(), updated_at = now()
             WHERE execution_created_at = :executionCreatedAt AND id = :attemptId
               AND finished_at IS NULL AND locked_until IS NULL
            """;

    private final Supplier<CommunicationSettings> settings;

    private final Supplier<CommunicationEngine> engine;

    private final UuidV7 ids;

    private final JdbcClient jdbc;

    private final TransactionTemplate transactions;

    SyncAttempts(
            final Supplier<CommunicationSettings> settings,
            final Supplier<CommunicationEngine> engine,
            final UuidV7 ids,
            final DataSource dataSource,
            final PlatformTransactionManager transactionManager) {
        this.settings = settings;
        this.engine = engine;
        this.ids = ids;
        this.jdbc = JdbcClient.create(dataSource);
        this.transactions = CommunicationSql.transactions(transactionManager);
    }

    /**
     * Проверяет, что транзакции вызывающего нет.
     *
     * @throws TaskcommException если транзакция в потоке активна
     */
    static void requireOutsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new TaskcommException("Синхронный запуск коммуникации вызван внутри активной транзакции. Попытка, "
                    + "обмен и закрытие пишутся своими короткими транзакциями и не увидели бы незакоммиченную "
                    + "коммуникацию, а работа рядом с вашей транзакцией молча потеряла бы атомарность с "
                    + "бизнес-данными. Вызывайте receiveAndProcess и sendAndProcess вне транзакции или "
                    + "используйте асинхронные receive и send (docs/adr/0133-synchronous-inbound-reception.md, "
                    + "docs/adr/0167-communication-run-mode.md).");
        }
    }

    /**
     * Аренда первой попытки — её строка создаётся вместе с коммуникацией.
     *
     * @param system внешняя система
     * @return аренда с новым идентификатором попытки
     */
    AttemptLease lease(final String system) {
        return this.settings
                .get()
                .attempt(system, CommunicationPhase.PROCESS)
                .lease()
                .lease(this.ids.next());
    }

    /**
     * Имя этого экземпляра — в {@code locked_by} первой попытки.
     *
     * @return имя экземпляра
     */
    String instance() {
        return this.engine.get().instance();
    }

    /**
     * Первая попытка — открыта под арендой вместе с коммуникацией.
     *
     * @param lease аренда первой попытки
     * @param target коммуникация
     * @param system внешняя система
     * @param direction направление
     * @param row созданная строка
     * @param created что создавали
     * @param payload доменная форма запроса в JSON
     * @return попытка для воркера
     */
    static ClaimedCommunication first(
            final AttemptLease lease,
            final CommunicationRef target,
            final String system,
            final Direction direction,
            final CreatedCommunication row,
            final NewCommunication created,
            final String payload) {
        return new ClaimedCommunication(
                lease,
                target,
                system,
                direction,
                CommunicationPhase.PROCESS,
                AttemptHistory.FIRST,
                new ClaimedCommunication.Snapshot(
                        row.execution().id(),
                        created.scheduling().priority(),
                        row.createdAt(),
                        row.origin(),
                        payload,
                        null));
    }

    /**
     * Попытки в потоке: первая уже под арендой, следующие — самозахватом после паузы.
     *
     * @param first первая попытка
     * @param syncWait предел ожидания защиты отправки перед повтором или {@code null} — у входящей её нет
     * @return итог
     */
    Run run(final ClaimedCommunication first, final Duration syncWait) {
        final CommunicationWorker worker = this.engine.get().worker();
        ClaimedCommunication attempt = first;
        Run result = null;
        while (result == null) {
            final CommunicationWorker.SyncOutcome outcome = worker.executeSync(attempt);
            if (outcome.processed()) {
                result = new Run(true, outcome.response(), null);
            } else {
                final ClaimedCommunication next = this.retry(attempt, syncWait, outcome.failure());
                if (next == null) {
                    result = new Run(false, null, this.failure(attempt.target(), outcome.failure()));
                }
                attempt = next;
            }
        }
        return result;
    }

    /**
     * Ждёт размыкателя и слота ограничителя скорости. Наступила проба разомкнутого — вызов сам становится
     * пробой: её исход запишет воркер.
     *
     * @param system внешняя система
     * @param limit предел ожидания
     * @return причина недоступности или {@code null}, если отправлять можно
     */
    String awaitGuards(final String system, final Duration limit) {
        final SendGuards guards = this.engine.get().guards();
        final long deadline = System.nanoTime() + limit.toNanos();
        try {
            String unavailable = null;
            while (unavailable == null && !guards.breakers().claimable(system)) {
                if (!guards.breakers().dueProbes(Set.of(system)).isEmpty()) {
                    guards.breakers().probeClaimed(system, 1);
                    break;
                }
                final long left = deadline - System.nanoTime();
                if (left <= 0) {
                    unavailable = "размыкатель системы '" + system + "' разомкнут дольше " + limit;
                } else {
                    TimeUnit.NANOSECONDS.sleep(Math.min(left, STEP.toNanos()));
                }
            }
            if (unavailable == null && !guards.rates().acquire(system, () -> System.nanoTime() > deadline)) {
                unavailable = "нет слота ограничителя скорости системы '" + system + "' дольше " + limit;
            }
            return unavailable;
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return "поток прерван в ожидании защиты отправки";
        }
    }

    /**
     * Следующая попытка после неуспеха — пауза, защита отправки и самозахват.
     *
     * @return захваченная попытка или {@code null} — повтора нет или забрать не вышло
     */
    private ClaimedCommunication retry(
            final ClaimedCommunication attempt, final Duration syncWait, final String failure) {
        final Next next = this.next(attempt.target());
        if (next.attemptId() == null || !pause(next.waitSeconds())) {
            return null;
        }
        final String unavailable = syncWait == null ? null : this.awaitGuards(attempt.system(), syncWait);
        if (unavailable != null) {
            this.giveUp(attempt.target(), next.attemptId(), unavailable + "; прежний неуспех: " + failure);
            return null;
        }
        return this.take(attempt, next.attemptId());
    }

    /** Причина неуспеха: конечного статуса коммуникации, иначе последней попытки. */
    private String failure(final CommunicationRef target, final String lastAttempt) {
        final Next state = this.next(target);
        return state.reason() == null ? lastAttempt : state.reason();
    }

    /** Что с коммуникацией после неуспеха: следующая попытка в очереди отправки или конечный статус. */
    private Next next(final CommunicationRef target) {
        return this.jdbc
                .sql(NEXT)
                .param("executionCreatedAt", CommunicationSql.parameter(target.executionCreatedAt()))
                .param("id", target.id())
                .query((rs, rowNum) -> {
                    final boolean queued = "PENDING".equals(rs.getString("status"))
                            && CommunicationPhase.PROCESS.name().equals(rs.getString("phase"));
                    return new Next(
                            queued ? rs.getObject("next_attempt", UUID.class) : null,
                            rs.getDouble("wait"),
                            queued ? null : rs.getString("status_description"));
                })
                .single();
    }

    /** Забирает следующую попытку себе — одной транзакцией; не вышло — {@code null}. */
    private ClaimedCommunication take(final ClaimedCommunication previous, final UUID attemptId) {
        final AttemptLease lease = this.settings
                .get()
                .attempt(previous.system(), CommunicationPhase.PROCESS)
                .lease()
                .lease(attemptId);
        final Boolean taken = this.transactions.execute(status -> {
            final int communication = this.jdbc
                    .sql(TAKE_COMMUNICATION)
                    .param(
                            "executionCreatedAt",
                            CommunicationSql.parameter(previous.target().executionCreatedAt()))
                    .param("id", previous.target().id())
                    .update();
            final int attempt = communication == 1
                    ? this.jdbc
                            .sql(TAKE_ATTEMPT)
                            .param("lockedBy", this.instance())
                            .param("lease", CommunicationSql.seconds(lease.duration()))
                            .param("token", lease.token())
                            .param(
                                    "executionCreatedAt",
                                    CommunicationSql.parameter(previous.target().executionCreatedAt()))
                            .param("attemptId", attemptId)
                            .update()
                    : 0;
            if (attempt != 1) {
                status.setRollbackOnly();
            }
            return attempt == 1;
        });
        return Boolean.TRUE.equals(taken)
                ? new ClaimedCommunication(
                        lease,
                        previous.target(),
                        previous.system(),
                        previous.direction(),
                        CommunicationPhase.PROCESS,
                        new AttemptHistory(previous.history().number() + 1, true),
                        previous.snapshot())
                : null;
    }

    /** Система недоступна дольше предела перед повтором: попытку не начинают, коммуникация — {@code FAILED}. */
    private void giveUp(final CommunicationRef target, final UUID attemptId, final String reason) {
        this.transactions.executeWithoutResult(status -> {
            this.jdbc
                    .sql(SKIP_OPEN)
                    .param("reason", reason)
                    .param("executionCreatedAt", CommunicationSql.parameter(target.executionCreatedAt()))
                    .param("attemptId", attemptId)
                    .update();
            CommunicationRetries.finish(this.jdbc, target, "FAILED", reason);
        });
    }

    /**
     * Пауза в потоке.
     *
     * @param seconds сколько ждать
     * @return {@code false}, если поток прерван
     */
    static boolean pause(final double seconds) {
        try {
            if (seconds > 0) {
                TimeUnit.NANOSECONDS.sleep((long) (seconds * 1e9));
            }
            return true;
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Итог попыток в потоке.
     *
     * @param processed успешна или пропущена
     * @param response что вернул процессор
     * @param failure причина неуспеха или {@code null}
     */
    record Run(boolean processed, Object response, String failure) {}

    /**
     * Следующая попытка после неуспеха.
     *
     * @param attemptId открытая незахваченная попытка или {@code null} — повтора нет
     * @param waitSeconds сколько ждать до её срока
     * @param reason причина конечного статуса или {@code null}
     */
    private record Next(UUID attemptId, double waitSeconds, String reason) {}
}
