package xyz.avasilev.taskcomm.communication.internal.engine;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.avasilev.taskcomm.communication.internal.CommunicationPartitionHorizon;
import xyz.avasilev.taskcomm.communication.internal.CommunicationPhase;
import xyz.avasilev.taskcomm.communication.internal.CommunicationRef;
import xyz.avasilev.taskcomm.communication.internal.CommunicationSql;
import xyz.avasilev.taskcomm.communication.internal.Direction;
import xyz.avasilev.taskcomm.communication.internal.lifecycle.CommunicationRetries;
import xyz.avasilev.taskcomm.communication.internal.settings.CommunicationSettings;

/**
 * Подстраховочный поллер коммуникаций (docs/adr/0086-async-response-in-outbound.md): находит исходящие
 * в {@code AWAITING_RESPONSE}, чей срок ожидания ответа — {@code next_attempt_at} — истёк, и отправляет
 * их повторно с backoff в пределах лимита попыток отправки; исчерпано — {@code FAILED} «ответ не получен
 * за срок». Повторная отправка даёт внешней системе второй запрос — её идемпотентность обязательна.
 *
 * <p>Синхронный запуск (docs/adr/0167-communication-run-mode.md) фоном не повторяют: у синхронной
 * истёкшее ожидание ответа — {@code FAILED} без повторной отправки, а синхронная в {@code PENDING}, чью
 * следующую попытку вызывающий не забрал за срок аренды, — {@code FAILED} «вызывающий не дождался»:
 * захват её не возьмёт, и без поллера она осталась бы в очереди навсегда.
 *
 * <p>Такт — одна короткая транзакция; строки берутся {@code FOR UPDATE SKIP LOCKED}, поэтому поллеры
 * нескольких экземпляров друг другу не мешают. Системы без процессора не трогаются
 * (docs/adr/0105-orphan-communications.md). Ищет только в активных партициях.
 */
@Slf4j
class CommunicationSafetyPoller {

    /** Сколько истёкших ожиданий берёт один такт. */
    static final int LIMIT = 100;

    /** Начало причины {@code FAILED} синхронной, чью следующую попытку вызывающий не забрал. */
    static final String SYNC_NOT_AWAITED = "синхронная: вызывающий не дождался следующей попытки";

    private static final String EXPIRED = """
            SELECT c.id, c.execution_created_at, coalesce(c.source, c.destination) AS system, c.run_mode,
                   (SELECT a.id FROM tc_communication_attempt a
                     WHERE a.execution_created_at = c.execution_created_at AND a.communication_id = c.id
                       AND a.phase = 'PROCESS'
                     ORDER BY a.started_at DESC NULLS LAST
                     LIMIT 1) AS last_send
              FROM tc_communication c
             WHERE c.status = 'AWAITING_RESPONSE'
               AND c.next_attempt_at <= now()
               AND c.execution_created_at >= :activeFrom
               AND coalesce(c.source, c.destination) = ANY(:systems)
             ORDER BY c.next_attempt_at
             LIMIT :limit
               FOR UPDATE OF c SKIP LOCKED
            """;

    /** Синхронные в паузе между попытками, срок следующей уже прошёл; насколько — в секундах. */
    private static final String SYNC_PENDING = """
            SELECT c.id, c.execution_created_at, coalesce(c.source, c.destination) AS system,
                   extract(epoch FROM now() - c.next_attempt_at) AS overdue
              FROM tc_communication c
             WHERE c.status = 'PENDING' AND c.run_mode = 'SYNC'
               AND c.phase = 'PROCESS'
               AND c.next_attempt_at <= now()
               AND c.execution_created_at >= :activeFrom
               AND coalesce(c.source, c.destination) = ANY(:systems)
             ORDER BY c.next_attempt_at
             LIMIT :limit
               FOR UPDATE OF c SKIP LOCKED
            """;

    /** Открытая незахваченная попытка брошенной синхронной — пропущена: её никто не начнёт. */
    private static final String SKIP_OPEN = """
            UPDATE tc_communication_attempt
               SET status = 'SKIPPED', status_description = :reason, finished_at = now(), updated_at = now()
             WHERE execution_created_at = :executionCreatedAt AND communication_id = :id
               AND finished_at IS NULL AND locked_until IS NULL
            """;

    private final JdbcClient jdbc;

    private final TransactionTemplate transactions;

    private final CommunicationSettings settings;

    private final CommunicationRetries retries;

    private final Set<String> systems;

    CommunicationSafetyPoller(
            final DataSource dataSource,
            final PlatformTransactionManager transactionManager,
            final CommunicationSettings settings,
            final CommunicationRetries retries,
            final Set<String> systems) {
        this.jdbc = JdbcClient.create(dataSource);
        this.transactions = CommunicationSql.transactions(transactionManager);
        this.settings = settings;
        this.retries = retries;
        this.systems = Set.copyOf(systems);
    }

    /** Один такт: до {@link #LIMIT} истёкших ожиданий и до {@link #LIMIT} брошенных синхронных. */
    void tick() {
        if (this.systems.isEmpty()) {
            return;
        }
        this.transactions.executeWithoutResult(status -> {
            CommunicationSql.forceCustomPlan(this.jdbc);
            final OffsetDateTime activeFrom =
                    CommunicationSql.parameter(CommunicationPartitionHorizon.activeFrom(this.jdbc));
            this.expired(activeFrom).forEach(this::resend);
            this.overdue(activeFrom).forEach(this::abandon);
        });
    }

    private List<Expired> expired(final OffsetDateTime activeFrom) {
        return this.jdbc
                .sql(EXPIRED)
                .param("activeFrom", activeFrom)
                .param("systems", this.systems.toArray(String[]::new))
                .param("limit", LIMIT)
                .query((rs, rowNum) -> new Expired(
                        new CommunicationRetries.Closed(
                                ref(
                                        rs.getObject("id", UUID.class),
                                        rs.getObject("execution_created_at", OffsetDateTime.class)),
                                rs.getObject("last_send", UUID.class),
                                rs.getString("system"),
                                Direction.OUTBOUND,
                                CommunicationPhase.PROCESS),
                        "SYNC".equals(rs.getString("run_mode"))))
                .list();
    }

    private List<Overdue> overdue(final OffsetDateTime activeFrom) {
        return this.jdbc
                .sql(SYNC_PENDING)
                .param("activeFrom", activeFrom)
                .param("systems", this.systems.toArray(String[]::new))
                .param("limit", LIMIT)
                .query((rs, rowNum) -> new Overdue(
                        ref(rs.getObject("id", UUID.class), rs.getObject("execution_created_at", OffsetDateTime.class)),
                        rs.getString("system"),
                        rs.getDouble("overdue")))
                .list();
    }

    private void resend(final Expired expired) {
        final CommunicationRetries.Closed lastSend = expired.lastSend();
        final String timeout = "ответ не получен за " + this.settings.responseTimeout(lastSend.system());
        if (expired.sync()) {
            log.info(
                    "Ответ на синхронную исходящую {} в '{}' не получен за срок — FAILED без повторной отправки",
                    lastSend.target().id(),
                    lastSend.system());
            CommunicationRetries.finish(this.jdbc, lastSend.target(), "FAILED", timeout);
            return;
        }
        log.info(
                "Ответ на исходящую {} в '{}' не получен за срок — повторная отправка или FAILED",
                lastSend.target().id(),
                lastSend.system());
        this.retries.retry(this.jdbc, lastSend, this.retries.attempts(this.jdbc, lastSend), timeout);
    }

    /**
     * Брошенная пауза синхронной: срок следующей попытки прошёл больше чем на аренду фазы — вызывающий,
     * который должен был её забрать, жив не был.
     */
    private void abandon(final Overdue overdue) {
        final double grace = CommunicationSql.seconds(this.settings
                .attempt(overdue.system(), CommunicationPhase.PROCESS)
                .lease()
                .duration());
        if (overdue.seconds() <= grace) {
            return;
        }
        log.info(
                "Синхронная {} в '{}': вызывающий не забрал следующую попытку за срок — FAILED",
                overdue.target().id(),
                overdue.system());
        this.jdbc
                .sql(SKIP_OPEN)
                .param("reason", SYNC_NOT_AWAITED)
                .param(
                        "executionCreatedAt",
                        CommunicationSql.parameter(overdue.target().executionCreatedAt()))
                .param("id", overdue.target().id())
                .update();
        CommunicationRetries.finish(this.jdbc, overdue.target(), "FAILED", SYNC_NOT_AWAITED);
    }

    private static CommunicationRef ref(final UUID id, final OffsetDateTime executionCreatedAt) {
        return new CommunicationRef(id, executionCreatedAt.toInstant());
    }

    /**
     * Истёкшее ожидание ответа.
     *
     * @param lastSend последняя отправка — образец повторной
     * @param sync синхронный запуск — без повторной отправки
     */
    private record Expired(CommunicationRetries.Closed lastSend, boolean sync) {}

    /**
     * Синхронная в паузе с прошедшим сроком.
     *
     * @param target коммуникация
     * @param system внешняя система
     * @param seconds насколько срок прошёл, секунд
     */
    private record Overdue(CommunicationRef target, String system, double seconds) {}
}
