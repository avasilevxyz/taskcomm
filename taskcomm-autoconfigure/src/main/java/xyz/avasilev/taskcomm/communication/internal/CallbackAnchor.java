package xyz.avasilev.taskcomm.communication.internal;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import xyz.avasilev.taskcomm.communication.api.CallbackRequest;
import xyz.avasilev.taskcomm.execution.internal.UuidV7;

/**
 * Строка якоря глазами приёма колбэка (docs/adr/0086-async-response-in-outbound.md): блокировка строки
 * коммуникации — её же берёт закрытие попытки отправки (docs/adr/0099-early-callback-proves-delivery.md), —
 * решение, ждёт ли она ответа, и запись колбэка попыткой {@code HANDLE_RESPONSE}. Всё — в транзакции
 * приёма.
 */
final class CallbackAnchor {

    /** Причина {@code SKIPPED} незахваченной попытки повторной отправки, когда ответ уже пришёл. */
    static final String ANSWERED = "ответ получен";

    private static final String LOCK = """
            SELECT c.direction, coalesce(c.source, c.destination) AS system, c.status, c.phase,
                   c.cancel_requested_at IS NOT NULL AS cancelled,
                   EXISTS (SELECT 1 FROM tc_communication_attempt a
                            WHERE a.execution_created_at = c.execution_created_at AND a.communication_id = c.id
                              AND a.phase = 'HANDLE_RESPONSE'
                              AND (a.finished_at IS NULL OR a.created_at >= c.retry_cycle_started_at)) AS answered
              FROM tc_communication c
             WHERE c.execution_created_at = :executionCreatedAt AND c.id = :id
               FOR UPDATE
            """;

    private static final String INSERT = """
            INSERT INTO tc_communication_attempt (
                id, created_at, updated_at, execution_created_at, communication_id, phase, lease_renewals,
                started_at, finished_at, status, status_description,
                request_id, request_at, request_headers, request_body, request_payload)
            VALUES (:id, now(), now(), :executionCreatedAt, :communicationId, 'HANDLE_RESPONSE', 0,
                    CASE WHEN :failed THEN now() END, CASE WHEN :failed THEN now() END, CAST(:status AS text),
                    CAST(:reason AS text), CAST(:requestId AS text), CAST(:requestAt AS timestamptz),
                    CAST(:requestHeaders AS jsonb), :requestBody, CAST(:payload AS jsonb))
            """;

    private static final String SKIP_PROCESS = """
            UPDATE tc_communication_attempt
               SET status = 'SKIPPED', status_description = :reason, started_at = coalesce(started_at, now()),
                   finished_at = now(), updated_at = now()
             WHERE execution_created_at = :executionCreatedAt AND communication_id = :id
               AND phase = 'PROCESS' AND finished_at IS NULL
            """;

    private CallbackAnchor() {}

    /**
     * Блокирует строку якоря и читает её состояние.
     *
     * @param jdbc клиент в транзакции приёма
     * @param target якорь
     * @return состояние или пусто — партиция якоря удалена
     */
    static Optional<State> lock(final JdbcClient jdbc, final CommunicationRef target) {
        return jdbc.sql(LOCK)
                .param("executionCreatedAt", CommunicationSql.parameter(target.executionCreatedAt()))
                .param("id", target.id())
                .query((rs, rowNum) -> new State(
                        Direction.valueOf(rs.getString("direction")),
                        rs.getString("system"),
                        rs.getString("status"),
                        CommunicationPhase.valueOf(rs.getString("phase")),
                        rs.getBoolean("cancelled"),
                        rs.getBoolean("answered")))
                .optional();
    }

    /**
     * Записывает колбэк попыткой обработки ответа.
     *
     * @param jdbc клиент в транзакции приёма
     * @param ids генератор идентификаторов
     * @param codec кодек — заголовки ложатся в {@code jsonb}
     * @param target исходящая
     * @param callback колбэк
     * @param payload доменная форма колбэка в JSON; {@code null} — тело не разобралось, попытка сразу
     *     закрыта {@code FAILED} с причиной {@code reason}
     * @param reason причина разбора или {@code null}
     */
    static void record(
            final JdbcClient jdbc,
            final UuidV7 ids,
            final CommunicationCodec codec,
            final CommunicationRef target,
            final CallbackRequest callback,
            final String payload,
            final String reason) {
        final boolean failed = payload == null;
        jdbc.sql(INSERT)
                .param("id", ids.next())
                .param("executionCreatedAt", CommunicationSql.parameter(target.executionCreatedAt()))
                .param("communicationId", target.id())
                .param("failed", failed)
                .param("status", failed ? "FAILED" : null)
                .param("reason", reason)
                .param("requestId", callback.requestId().orElse(null))
                .param(
                        "requestAt",
                        callback.requestAt().map(CommunicationSql::parameter).orElse(null))
                .param("requestHeaders", codec.write(callback.headers().asMap()))
                .param("requestBody", callback.body())
                .param("payload", payload)
                .update();
    }

    /**
     * Закрывает незахваченную попытку повторной отправки: ответ уже пришёл.
     *
     * @param jdbc клиент в транзакции приёма
     * @param target исходящая
     */
    static void skipPendingSend(final JdbcClient jdbc, final CommunicationRef target) {
        jdbc.sql(SKIP_PROCESS)
                .param("reason", ANSWERED)
                .param("executionCreatedAt", CommunicationSql.parameter(target.executionCreatedAt()))
                .param("id", target.id())
                .update();
    }

    /**
     * Состояние якоря.
     *
     * @param direction направление
     * @param system внешняя сторона
     * @param status статус
     * @param phase фаза
     * @param cancelled стоит ли отметка отмены
     * @param answered был ли колбэк в текущем цикле — открытая попытка {@code HANDLE_RESPONSE} или начатая
     *     после {@code retry_cycle_started_at}: ручной перезапуск начинает цикл заново (docs/adr/0139-restart-api.md)
     */
    record State(
            Direction direction,
            String system,
            String status,
            CommunicationPhase phase,
            boolean cancelled,
            boolean answered) {

        /**
         * Колбэк от этой системы дописывается в якорь: исходящая той же системы.
         *
         * @param source система колбэка
         * @return {@code true}, если якорь — исходящая этой системы
         */
        boolean sameSystemOutbound(final String source) {
            return this.direction == Direction.OUTBOUND && this.system.equals(source);
        }

        /**
         * Ждёт ли якорь ответа: ждёт, ещё отправляется или ждёт повторной отправки, ответа не было,
         * не отменён.
         *
         * @return {@code true}, если колбэк можно дописать
         */
        boolean waiting() {
            final boolean sending = this.phase == CommunicationPhase.PROCESS
                    && ("RUNNING".equals(this.status) || "PENDING".equals(this.status));
            final boolean live = sending || "AWAITING_RESPONSE".equals(this.status);
            return live && !this.answered && !this.cancelled;
        }
    }
}
