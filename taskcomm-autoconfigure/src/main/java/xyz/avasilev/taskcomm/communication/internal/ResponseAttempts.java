package xyz.avasilev.taskcomm.communication.internal;

import java.time.Duration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Попытки обработки асинхронного ответа глазами того, кто закрывает попытку отправки
 * (docs/adr/0086-async-response-in-outbound.md, docs/adr/0099-early-callback-proves-delivery.md):
 * воркера, переподхвата, подстраховки. Всё — в транзакции вызывающего, под блокировкой строки
 * коммуникации: приём колбэка правит ту же строку.
 */
final class ResponseAttempts {

    /** Причина {@code FAILED} у исходящей, чей колбэк не разобрался (docs/adr/0136-communication-dedup-and-callbacks.md). */
    static final String UNPARSED = "колбэк не разбирается";

    private static final String OPEN = """
            SELECT count(*) FROM tc_communication_attempt
             WHERE execution_created_at = :executionCreatedAt AND communication_id = :id
               AND phase = 'HANDLE_RESPONSE' AND finished_at IS NULL
            """;

    private static final String UNPARSED_ARRIVED = """
            SELECT count(*) FROM tc_communication_attempt
             WHERE execution_created_at = :executionCreatedAt AND communication_id = :id
               AND phase = 'HANDLE_RESPONSE' AND status = 'FAILED' AND request_payload IS NULL
            """;

    private static final String CLOSE_OPEN = """
            UPDATE tc_communication_attempt
               SET status = :status, status_description = :reason, started_at = coalesce(started_at, now()),
                   finished_at = now(), updated_at = now()
             WHERE execution_created_at = :executionCreatedAt AND communication_id = :id
               AND phase = 'HANDLE_RESPONSE' AND finished_at IS NULL
            """;

    /** Ответ пришёл — в захват обработки ответа, с новым циклом повторов. */
    private static final String HAND_OVER = """
            UPDATE tc_communication
               SET status = 'PENDING', phase = 'HANDLE_RESPONSE', next_attempt_at = now(),
                   claim_order_at = now() + make_interval(secs => priority * :deadlineStep),
                   retry_cycle_started_at = now(), updated_at = now()
             WHERE execution_created_at = :executionCreatedAt AND id = :id
               AND status IN ('RUNNING', 'PENDING', 'AWAITING_RESPONSE')
            """;

    private static final String AWAIT = """
            UPDATE tc_communication
               SET status = 'AWAITING_RESPONSE', phase = 'HANDLE_RESPONSE',
                   next_attempt_at = now() + make_interval(secs => :timeout), updated_at = now()
             WHERE execution_created_at = :executionCreatedAt AND id = :id AND status = 'RUNNING'
            """;

    private ResponseAttempts() {}

    /**
     * Есть ли открытая попытка ответа — колбэк пришёл раньше, чем закрылась отправка.
     *
     * @param jdbc клиент в транзакции
     * @param target коммуникация
     * @return {@code true}, если есть
     */
    static boolean hasOpen(final JdbcClient jdbc, final CommunicationRef target) {
        return count(jdbc, OPEN, target) > 0;
    }

    /**
     * Приходил ли колбэк, тело которого не разобралось.
     *
     * @param jdbc клиент в транзакции
     * @param target коммуникация
     * @return {@code true}, если приходил
     */
    static boolean unparsedArrived(final JdbcClient jdbc, final CommunicationRef target) {
        return count(jdbc, UNPARSED_ARRIVED, target) > 0;
    }

    /**
     * Закрывает открытую попытку ответа, не обрабатывая её.
     *
     * @param jdbc клиент в транзакции
     * @param target коммуникация
     * @param reason причина
     */
    static void skipOpen(final JdbcClient jdbc, final CommunicationRef target, final String reason) {
        jdbc.sql(CLOSE_OPEN)
                .param("status", "SKIPPED")
                .param("reason", reason)
                .param("executionCreatedAt", CommunicationSql.parameter(target.executionCreatedAt()))
                .param("id", target.id())
                .update();
    }

    /**
     * Переводит коммуникацию в захват обработки ответа с новым циклом повторов.
     *
     * @param jdbc клиент в транзакции
     * @param target коммуникация
     * @param deadlineStep шаг дедлайна поллера системы
     */
    static void handOver(final JdbcClient jdbc, final CommunicationRef target, final Duration deadlineStep) {
        jdbc.sql(HAND_OVER)
                .param("deadlineStep", CommunicationSql.seconds(deadlineStep))
                .param("executionCreatedAt", CommunicationSql.parameter(target.executionCreatedAt()))
                .param("id", target.id())
                .update();
    }

    /**
     * Отправка успешна, ответ ожидается: {@code AWAITING_RESPONSE}, срок — в {@code next_attempt_at}.
     *
     * @param jdbc клиент в транзакции
     * @param target коммуникация
     * @param timeout срок ожидания ответа
     */
    static void await(final JdbcClient jdbc, final CommunicationRef target, final Duration timeout) {
        jdbc.sql(AWAIT)
                .param("timeout", CommunicationSql.seconds(timeout))
                .param("executionCreatedAt", CommunicationSql.parameter(target.executionCreatedAt()))
                .param("id", target.id())
                .update();
    }

    private static int count(final JdbcClient jdbc, final String sql, final CommunicationRef target) {
        return jdbc.sql(sql)
                .param("executionCreatedAt", CommunicationSql.parameter(target.executionCreatedAt()))
                .param("id", target.id())
                .query(Integer.class)
                .single();
    }
}
