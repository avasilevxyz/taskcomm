package xyz.avasilev.taskcomm.communication.internal;

import java.time.Duration;
import java.util.UUID;
import java.util.function.DoubleSupplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import xyz.avasilev.taskcomm.execution.internal.UuidV7;

/**
 * Что делается с коммуникацией после неуспешной попытки — закрытой воркером исходом {@code FAILED}
 * или переподхватом исходом {@code LEASE_LOST} (docs/adr/0067-expired-lease-closes-attempt.md).
 * Коммуникация возвращается в {@code PENDING} с backoff, и <b>следующая попытка открывается сразу</b>,
 * без аренды (docs/adr/0054-communication-attempt-lifecycle.md); у входящей в неё копируются
 * {@code request_*} — запрос пришёл один раз (docs/adr/0055-communication-exchange-storage.md). Лимит
 * исчерпан — {@code FAILED}. Отменённая — {@code CANCELLED}.
 *
 * <p>Попытки считаются по строкам той же фазы, начатым с {@code retry_cycle_started_at}. Методы работают
 * в транзакции вызывающего.
 */
class CommunicationRetries {

    /** Причина {@code FAILED} у коммуникации, исчерпавшей лимит. */
    static final String EXHAUSTED = "попытки исчерпаны";

    /** Причина {@code CANCELLED}. */
    static final String CANCELLED = "отменено";

    /** Перевод в терминальный статус — условно по {@code RUNNING}, как все переходы. */
    static final String FINISH = """
            UPDATE tc_communication
               SET status = :status, status_description = :reason, updated_at = now()
             WHERE execution_created_at = :executionCreatedAt AND id = :id AND status = 'RUNNING'
            """;

    private static final String STATE = """
            SELECT c.cancel_requested_at IS NOT NULL AS cancelled,
                   (SELECT count(*) FROM tc_communication_attempt a
                     WHERE a.execution_created_at = c.execution_created_at AND a.communication_id = c.id
                       AND a.phase = :phase AND a.started_at >= c.retry_cycle_started_at) AS attempts
              FROM tc_communication c
             WHERE c.execution_created_at = :executionCreatedAt AND c.id = :id
            """;

    private static final String REQUEUE = """
            UPDATE tc_communication
               SET status = 'PENDING',
                   next_attempt_at = now() + make_interval(secs => :delay),
                   claim_order_at = now() + make_interval(secs => :delay + priority * :deadlineStep),
                   updated_at = now()
             WHERE execution_created_at = :executionCreatedAt AND id = :id AND status = 'RUNNING'
            """;

    private static final String OPEN_NEXT = """
            INSERT INTO tc_communication_attempt (
                id, created_at, updated_at, execution_created_at, communication_id, phase, lease_renewals,
                request_id, request_at, request_headers, request_body, request_payload)
            SELECT :id, now(), now(), a.execution_created_at, a.communication_id, a.phase, 0,
                   CASE WHEN :copy THEN a.request_id END, CASE WHEN :copy THEN a.request_at END,
                   CASE WHEN :copy THEN a.request_headers END, CASE WHEN :copy THEN a.request_body END,
                   CASE WHEN :copy THEN a.request_payload END
              FROM tc_communication_attempt a
             WHERE a.execution_created_at = :executionCreatedAt AND a.id = :closedId
            """;

    private final CommunicationSettings settings;

    private final DoubleSupplier random;

    private final UuidV7 ids;

    CommunicationRetries(final CommunicationSettings settings, final DoubleSupplier random, final UuidV7 ids) {
        this.settings = settings;
        this.random = random;
        this.ids = ids;
    }

    /**
     * Возвращает коммуникацию в очередь со следующей попыткой либо делает её терминальной.
     *
     * @param jdbc клиент в транзакции, закрывшей попытку
     * @param closed закрытая попытка
     * @param retryable {@code false}, если повтор запрещён
     * @param reason причина {@code FAILED}, если повтор запрещён
     */
    void afterUnsuccessful(final JdbcClient jdbc, final Closed closed, final boolean retryable, final String reason) {
        final State state = jdbc.sql(STATE)
                .param("phase", closed.phase().name())
                .param(
                        "executionCreatedAt",
                        CommunicationSql.parameter(closed.target().executionCreatedAt()))
                .param("id", closed.target().id())
                .query((rs, rowNum) -> new State(rs.getBoolean("cancelled"), rs.getInt("attempts")))
                .single();
        if (state.cancelled()) {
            finish(jdbc, closed.target(), "CANCELLED", CANCELLED);
        } else if (!retryable) {
            finish(jdbc, closed.target(), "FAILED", reason);
        } else {
            final RetryPolicy policy =
                    this.settings.attempt(closed.system(), closed.phase()).retry();
            if (policy.exhausted(state.attempts())) {
                finish(jdbc, closed.target(), "FAILED", EXHAUSTED);
            } else {
                this.requeue(jdbc, closed, policy.delay(state.attempts(), this.random));
            }
        }
    }

    /**
     * Переводит коммуникацию в терминальный статус.
     *
     * @param jdbc клиент в транзакции
     * @param target коммуникация
     * @param status статус
     * @param reason причина или {@code null}
     */
    static void finish(final JdbcClient jdbc, final CommunicationRef target, final String status, final String reason) {
        jdbc.sql(FINISH)
                .param("status", status)
                .param("reason", reason)
                .param("executionCreatedAt", CommunicationSql.parameter(target.executionCreatedAt()))
                .param("id", target.id())
                .update();
    }

    private void requeue(final JdbcClient jdbc, final Closed closed, final Duration delay) {
        final int updated = jdbc.sql(REQUEUE)
                .param("delay", CommunicationSql.seconds(delay))
                .param(
                        "deadlineStep",
                        CommunicationSql.seconds(this.settings.pollers().deadlineStep(closed.system())))
                .param(
                        "executionCreatedAt",
                        CommunicationSql.parameter(closed.target().executionCreatedAt()))
                .param("id", closed.target().id())
                .update();
        if (updated == 1) {
            jdbc.sql(OPEN_NEXT)
                    .param("id", this.ids.next())
                    .param("copy", closed.direction() == Direction.INBOUND)
                    .param(
                            "executionCreatedAt",
                            CommunicationSql.parameter(closed.target().executionCreatedAt()))
                    .param("closedId", closed.attemptId())
                    .update();
        }
    }

    /**
     * Закрытая неуспешная попытка.
     *
     * @param target коммуникация
     * @param attemptId попытка
     * @param system внешняя система
     * @param direction направление
     * @param phase фаза попытки
     */
    record Closed(
            CommunicationRef target, UUID attemptId, String system, Direction direction, CommunicationPhase phase) {}

    /**
     * Состояние коммуникации для решения о повторе.
     *
     * @param cancelled стоит ли отметка отмены
     * @param attempts попыток фазы в текущем цикле, включая закрытую
     */
    private record State(boolean cancelled, int attempts) {}
}
