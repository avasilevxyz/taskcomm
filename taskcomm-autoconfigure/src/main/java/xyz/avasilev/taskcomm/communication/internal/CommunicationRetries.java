package xyz.avasilev.taskcomm.communication.internal;

import java.time.Duration;
import java.util.UUID;
import java.util.function.DoubleSupplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import xyz.avasilev.taskcomm.execution.internal.UuidV7;

/**
 * Что делается с коммуникацией после закрытия попытки — воркером, переподхватом или подстраховкой.
 * Методы работают в транзакции вызывающего, под блокировкой строки коммуникации.
 *
 * <ul>
 *   <li><b>Неуспех</b> (docs/adr/0067-expired-lease-closes-attempt.md): коммуникация возвращается
 *       в {@code PENDING} с backoff, и <b>следующая попытка открывается сразу</b>, без аренды
 *       (docs/adr/0054-communication-attempt-lifecycle.md); у входящей и у обработки ответа в неё
 *       копируется запрос (docs/adr/0055-communication-exchange-storage.md). Лимит исчерпан — {@code FAILED}.
 *   <li><b>Успех отправки</b> с зарегистрированным ключом корреляции своей системы — ожидание ответа
 *       (docs/adr/0104-wait-defined-by-correlation-key.md).
 *   <li><b>Ранний колбэк</b> — открытая попытка ответа при закрытии отправки — доказательство доставки:
 *       неуспех и {@code LEASE_LOST} не повторяют отправку, а передают в обработку ответа; пропуск
 *       закрывает ответ {@code SKIPPED}; отмена побеждает всё (docs/adr/0099-early-callback-proves-delivery.md).
 * </ul>
 *
 * <p>Попытки считаются по строкам той же фазы, начатым с {@code retry_cycle_started_at}.
 */
class CommunicationRetries {

    /** Причина {@code FAILED} у коммуникации, исчерпавшей лимит. */
    static final String EXHAUSTED = "попытки исчерпаны";

    /** Причина {@code CANCELLED}. */
    static final String CANCELLED = "отменено";

    /** Причина {@code SKIPPED} у попытки ответа, чья отправка пропущена. */
    static final String SEND_SKIPPED = "отправка пропущена, ответ не обработан";

    /** Перевод в терминальный статус — условно по незавершённым статусам. */
    static final String FINISH = """
            UPDATE tc_communication
               SET status = :status, status_description = :reason, updated_at = now()
             WHERE execution_created_at = :executionCreatedAt AND id = :id
               AND status IN ('RUNNING', 'PENDING', 'AWAITING_RESPONSE')
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
               SET status = 'PENDING', phase = :phase,
                   next_attempt_at = now() + make_interval(secs => :delay),
                   claim_order_at = now() + make_interval(secs => :delay + priority * :deadlineStep),
                   updated_at = now()
             WHERE execution_created_at = :executionCreatedAt AND id = :id
               AND status IN ('RUNNING', 'AWAITING_RESPONSE')
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
     * После успешной попытки: ожидание ответа, передача раннего колбэка в обработку или {@code SUCCEEDED}.
     *
     * @param jdbc клиент в транзакции закрытия
     * @param closed закрытая попытка
     * @param doNotAwait процессор сказал «не ждать»
     */
    void afterSuccess(final JdbcClient jdbc, final Closed closed, final boolean doNotAwait) {
        if (!sending(closed)) {
            finish(jdbc, closed.target(), "SUCCEEDED", null);
        } else if (ResponseAttempts.hasOpen(jdbc, closed.target())) {
            if (this.state(jdbc, closed).cancelled()) {
                ResponseAttempts.skipOpen(jdbc, closed.target(), CANCELLED);
                finish(jdbc, closed.target(), "CANCELLED", CANCELLED);
            } else {
                ResponseAttempts.handOver(
                        jdbc, closed.target(), this.settings.pollers().deadlineStep(closed.system()));
            }
        } else if (ResponseAttempts.unparsedArrived(jdbc, closed.target())) {
            finish(jdbc, closed.target(), "FAILED", ResponseAttempts.UNPARSED);
        } else if (!doNotAwait && CommunicationCorrelation.awaits(jdbc, closed.target(), closed.system())) {
            this.awaitUnlessCancelled(jdbc, closed);
        } else {
            finish(jdbc, closed.target(), "SUCCEEDED", null);
        }
    }

    /**
     * Отправка удалась, и ответа ждут: при отметке отмены коммуникация {@code CANCELLED} — ждать ответа
     * на отменённое некому (docs/adr/0100-cancel-keeps-real-outcome.md, docs/adr/0099-early-callback-proves-delivery.md).
     */
    private void awaitUnlessCancelled(final JdbcClient jdbc, final Closed closed) {
        if (this.state(jdbc, closed).cancelled()) {
            finish(jdbc, closed.target(), "CANCELLED", CANCELLED);
        } else {
            ResponseAttempts.await(jdbc, closed.target(), this.settings.responseTimeout(closed.system()));
        }
    }

    /**
     * После пропуска: {@code SKIPPED}; открытая попытка ответа закрывается {@code SKIPPED}, а при отмене
     * коммуникация {@code CANCELLED}.
     *
     * @param jdbc клиент в транзакции закрытия
     * @param closed закрытая попытка
     * @param reason причина пропуска
     */
    void afterSkip(final JdbcClient jdbc, final Closed closed, final String reason) {
        if (sending(closed) && ResponseAttempts.hasOpen(jdbc, closed.target())) {
            final boolean cancelled = this.state(jdbc, closed).cancelled();
            ResponseAttempts.skipOpen(jdbc, closed.target(), cancelled ? CANCELLED : SEND_SKIPPED);
            finish(jdbc, closed.target(), cancelled ? "CANCELLED" : "SKIPPED", cancelled ? CANCELLED : reason);
        } else {
            finish(jdbc, closed.target(), "SKIPPED", reason);
        }
    }

    /**
     * После неуспеха: следующая попытка или терминальный статус.
     *
     * @param jdbc клиент в транзакции закрытия
     * @param closed закрытая попытка
     * @param retryable {@code false}, если повтор запрещён
     * @param reason причина {@code FAILED}, если повтор запрещён
     */
    void afterUnsuccessful(final JdbcClient jdbc, final Closed closed, final boolean retryable, final String reason) {
        final State state = this.state(jdbc, closed);
        final boolean earlyCallback = sending(closed) && ResponseAttempts.hasOpen(jdbc, closed.target());
        if (state.cancelled()) {
            ResponseAttempts.skipOpen(jdbc, closed.target(), CANCELLED);
            finish(jdbc, closed.target(), "CANCELLED", CANCELLED);
        } else if (earlyCallback) {
            ResponseAttempts.handOver(
                    jdbc, closed.target(), this.settings.pollers().deadlineStep(closed.system()));
        } else if (sending(closed) && ResponseAttempts.unparsedArrived(jdbc, closed.target())) {
            finish(jdbc, closed.target(), "FAILED", ResponseAttempts.UNPARSED);
        } else if (!retryable) {
            finish(jdbc, closed.target(), "FAILED", reason);
        } else {
            this.retry(jdbc, closed, state.attempts(), EXHAUSTED);
        }
    }

    /**
     * Следующая попытка с backoff в пределах лимита фазы, иначе {@code FAILED}.
     *
     * @param jdbc клиент в транзакции
     * @param closed последняя попытка — образец следующей
     * @param attempts попыток фазы в текущем цикле
     * @param exhausted причина {@code FAILED} при исчерпании
     */
    void retry(final JdbcClient jdbc, final Closed closed, final int attempts, final String exhausted) {
        final RetryPolicy policy =
                this.settings.attempt(closed.system(), closed.phase()).retry();
        if (policy.exhausted(attempts)) {
            finish(jdbc, closed.target(), "FAILED", exhausted);
            return;
        }
        final Duration delay = policy.delay(attempts, this.random);
        final int updated = jdbc.sql(REQUEUE)
                .param("phase", closed.phase().name())
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
                    .param(
                            "copy",
                            closed.direction() == Direction.INBOUND
                                    || closed.phase() == CommunicationPhase.HANDLE_RESPONSE)
                    .param(
                            "executionCreatedAt",
                            CommunicationSql.parameter(closed.target().executionCreatedAt()))
                    .param("closedId", closed.attemptId())
                    .update();
        }
    }

    /**
     * Попыток фазы в текущем цикле повторов.
     *
     * @param jdbc клиент в транзакции
     * @param closed попытка — её коммуникация и фаза
     * @return число попыток
     */
    int attempts(final JdbcClient jdbc, final Closed closed) {
        return this.state(jdbc, closed).attempts();
    }

    /**
     * Переводит коммуникацию в терминальный статус и отмечает её ключи дедупликации.
     *
     * @param jdbc клиент в транзакции
     * @param target коммуникация
     * @param status статус
     * @param reason причина или {@code null}
     */
    static void finish(final JdbcClient jdbc, final CommunicationRef target, final String status, final String reason) {
        final int updated = jdbc.sql(FINISH)
                .param("status", status)
                .param("reason", reason)
                .param("executionCreatedAt", CommunicationSql.parameter(target.executionCreatedAt()))
                .param("id", target.id())
                .update();
        if (updated == 1) {
            CommunicationDedup.finish(jdbc, target, "SUCCEEDED".equals(status));
        }
    }

    private State state(final JdbcClient jdbc, final Closed closed) {
        return jdbc.sql(STATE)
                .param("phase", closed.phase().name())
                .param(
                        "executionCreatedAt",
                        CommunicationSql.parameter(closed.target().executionCreatedAt()))
                .param("id", closed.target().id())
                .query((rs, rowNum) -> new State(rs.getBoolean("cancelled"), rs.getInt("attempts")))
                .single();
    }

    /** Отправка исходящей — только у неё бывает ранний колбэк и ожидание ответа. */
    private static boolean sending(final Closed closed) {
        return closed.direction() == Direction.OUTBOUND && closed.phase() == CommunicationPhase.PROCESS;
    }

    /**
     * Закрытая попытка.
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
