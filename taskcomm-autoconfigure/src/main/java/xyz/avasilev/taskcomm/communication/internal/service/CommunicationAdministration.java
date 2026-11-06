package xyz.avasilev.taskcomm.communication.internal.service;

import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.function.SingletonSupplier;
import xyz.avasilev.taskcomm.communication.api.CommunicationCancelResult;
import xyz.avasilev.taskcomm.communication.api.CommunicationRestartResult;
import xyz.avasilev.taskcomm.communication.internal.CommunicationRef;
import xyz.avasilev.taskcomm.communication.internal.CommunicationSql;
import xyz.avasilev.taskcomm.communication.internal.Direction;
import xyz.avasilev.taskcomm.communication.internal.keys.CommunicationDedup;
import xyz.avasilev.taskcomm.communication.internal.lifecycle.CommunicationRetries;
import xyz.avasilev.taskcomm.communication.internal.settings.CommunicationSettings;
import xyz.avasilev.taskcomm.execution.internal.UuidV7;

/**
 * Отмена и ручной перезапуск коммуникации (docs/adr/0139-restart-api.md). Обе операции — одной
 * транзакцией под блокировкой строки коммуникации: её же берут воркер при закрытии попытки и приём
 * колбэка.
 *
 * <ul>
 *   <li><b>Отмена</b> ставит {@code cancel_requested_at}. Ожидающая захвата или ответа становится
 *       {@code CANCELLED} сразу, её открытая попытка закрывается {@code SKIPPED}; работающая доигрывает
 *       и пишет настоящий исход (docs/adr/0100-cancel-keeps-real-outcome.md). Терминальная не отменяется:
 *       отметка на ней навсегда запретила бы перезапуск.
 *   <li><b>Перезапуск</b> — только упавшей и не отменённой (docs/adr/0071-no-restart-after-cancel.md),
 *       всегда с отправки или обработки входящей: фаза {@code PROCESS}, новый цикл попыток, новая
 *       открытая попытка — у входящей с копией запроса последней попытки обработки. Ключи дедупликации
 *       снова «не завершены». Колбэки прежних циклов история: новый цикл их не видит.
 * </ul>
 */
@Slf4j
class CommunicationAdministration {

    private static final String LOCK = """
            SELECT status, cancel_requested_at IS NOT NULL AS cancelled, direction,
                   coalesce(source, destination) AS system, request_payload IS NULL AS unparsed
              FROM tc_communication
             WHERE execution_created_at = :executionCreatedAt AND id = :id
               FOR UPDATE
            """;

    private static final String MARK = """
            UPDATE tc_communication SET cancel_requested_at = now(), updated_at = now()
             WHERE execution_created_at = :executionCreatedAt AND id = :id
            """;

    private static final String CLOSE_OPEN = """
            UPDATE tc_communication_attempt
               SET status = 'SKIPPED', status_description = :reason, started_at = coalesce(started_at, now()),
                   finished_at = now(), updated_at = now()
             WHERE execution_created_at = :executionCreatedAt AND communication_id = :id AND finished_at IS NULL
            """;

    private static final String RESTART = """
            UPDATE tc_communication
               SET status = 'PENDING', phase = 'PROCESS', status_description = NULL, next_attempt_at = now(),
                   retry_cycle_started_at = now(),
                   claim_order_at = now() + make_interval(secs => priority * :deadlineStep), updated_at = now()
             WHERE execution_created_at = :executionCreatedAt AND id = :id
            """;

    /** Новая попытка обработки; у входящей — с запросом последней попытки обработки. */
    private static final String OPEN = """
            INSERT INTO tc_communication_attempt (
                id, created_at, updated_at, execution_created_at, communication_id, phase, lease_renewals,
                request_id, request_at, request_headers, request_body)
            SELECT :attemptId, now(), now(), :executionCreatedAt, :id, 'PROCESS', 0,
                   a.request_id, a.request_at, a.request_headers, a.request_body
              FROM (SELECT 1) one
              LEFT JOIN LATERAL (SELECT x.request_id, x.request_at, x.request_headers, x.request_body
                                   FROM tc_communication_attempt x
                                  WHERE x.execution_created_at = :executionCreatedAt AND x.communication_id = :id
                                    AND x.phase = 'PROCESS' AND :copy
                                  ORDER BY x.created_at DESC
                                  LIMIT 1) a ON true
            """;

    private final JdbcClient jdbc;

    private final TransactionTemplate transactions;

    private final Supplier<CommunicationSettings> settings;

    private final UuidV7 ids;

    /**
     * Собирает операции.
     *
     * @param dataSource {@code DataSource} стартера
     * @param transactionManager менеджер транзакций стартера
     * @param settings настройки — лениво; шаг дедлайна по системе
     * @param ids генератор идентификаторов
     */
    CommunicationAdministration(
            final DataSource dataSource,
            final PlatformTransactionManager transactionManager,
            final Supplier<CommunicationSettings> settings,
            final UuidV7 ids) {
        this.jdbc = JdbcClient.create(dataSource);
        this.transactions = CommunicationSql.transactions(transactionManager);
        this.settings = SingletonSupplier.of(settings);
        this.ids = ids;
    }

    /**
     * Отменяет коммуникацию.
     *
     * @param target коммуникация
     * @return принята отмена или отвергнута
     */
    CommunicationCancelResult cancel(final CommunicationRef target) {
        final CommunicationCancelResult result = this.transactions.execute(status -> {
            final Optional<Locked> locked = this.lock(target);
            final String rejection =
                    locked.isEmpty() ? "не найдена: коммуникация " + target.id() : cancelRejection(locked.get());
            if (rejection != null) {
                return new CommunicationCancelResult.Rejected(rejection);
            }
            this.jdbc.sql(MARK).params(key(target)).update();
            if ("RUNNING".equals(locked.get().status())) {
                return new CommunicationCancelResult.Accepted(
                        "отмена принята: идёт попытка, она доиграет и запишет настоящий исход");
            }
            this.jdbc
                    .sql(CLOSE_OPEN)
                    .params(key(target))
                    .param("reason", CommunicationRetries.CANCELLED)
                    .update();
            CommunicationRetries.finish(this.jdbc, target, "CANCELLED", CommunicationRetries.CANCELLED);
            return new CommunicationCancelResult.Accepted("отменена сразу: работа над ней не шла");
        });
        return result == null ? new CommunicationCancelResult.Rejected("отмена не выполнена") : result;
    }

    /**
     * Перезапускает упавшую коммуникацию.
     *
     * @param target коммуникация
     * @return принят перезапуск или отвергнут
     */
    CommunicationRestartResult restart(final CommunicationRef target) {
        final CommunicationRestartResult result = this.transactions.execute(status -> {
            final Optional<Locked> locked = this.lock(target);
            final String rejection =
                    locked.isEmpty() ? "не найдена: коммуникация " + target.id() : restartRejection(locked.get());
            if (rejection != null) {
                return new CommunicationRestartResult.Rejected(rejection);
            }
            this.reopen(target, locked.get());
            log.info("Коммуникация {} перезапущена вручную", target.id());
            return new CommunicationRestartResult.Accepted(
                    locked.get().direction() == Direction.INBOUND
                            ? "снова в очереди: обработка входящей заново, новый цикл попыток"
                            : "снова в очереди: отправка заново, новый цикл попыток");
        });
        return result == null ? new CommunicationRestartResult.Rejected("перезапуск не выполнен") : result;
    }

    private void reopen(final CommunicationRef target, final Locked locked) {
        this.jdbc
                .sql(RESTART)
                .params(key(target))
                .param(
                        "deadlineStep",
                        CommunicationSql.seconds(this.settings.get().pollers().deadlineStep(locked.system())))
                .update();
        this.jdbc
                .sql(OPEN)
                .params(key(target))
                .param("attemptId", this.ids.next())
                .param("copy", locked.direction() == Direction.INBOUND)
                .update();
        CommunicationDedup.reopen(this.jdbc, target);
    }

    private Optional<Locked> lock(final CommunicationRef target) {
        return this.jdbc
                .sql(LOCK)
                .params(key(target))
                .query((rs, rowNum) -> new Locked(
                        rs.getString("status"),
                        rs.getBoolean("cancelled"),
                        Direction.valueOf(rs.getString("direction")),
                        rs.getString("system"),
                        rs.getBoolean("unparsed")))
                .optional();
    }

    private static Map<String, Object> key(final CommunicationRef target) {
        return Map.of("executionCreatedAt", CommunicationSql.parameter(target.executionCreatedAt()), "id", target.id());
    }

    private static String cancelRejection(final Locked locked) {
        final String reason;
        if (locked.cancelled()) {
            reason = "уже отменена";
        } else if ("RUNNING".equals(locked.status())
                || "PENDING".equals(locked.status())
                || "AWAITING_RESPONSE".equals(locked.status())) {
            reason = null;
        } else {
            reason = "уже в терминальном статусе " + locked.status() + ": отменять нечего";
        }
        return reason;
    }

    private static String restartRejection(final Locked locked) {
        final String reason;
        if (locked.cancelled() || "CANCELLED".equals(locked.status())) {
            reason = "отменена: отменённое не перезапускается, создайте новую коммуникацию";
        } else if (!"FAILED".equals(locked.status())) {
            reason = "перезапускается только упавшая коммуникация, а эта в статусе " + locked.status();
        } else if (locked.unparsed()) {
            reason = "тело запроса не разобрано: обрабатывать нечего, нужна новая доставка";
        } else {
            reason = null;
        }
        return reason;
    }

    /**
     * Коммуникация под блокировкой.
     *
     * @param status статус
     * @param cancelled стоит ли отметка отмены
     * @param direction направление
     * @param system внешняя система
     * @param unparsed доменной формы запроса нет — тело не разобрано
     */
    private record Locked(String status, boolean cancelled, Direction direction, String system, boolean unparsed) {}
}
