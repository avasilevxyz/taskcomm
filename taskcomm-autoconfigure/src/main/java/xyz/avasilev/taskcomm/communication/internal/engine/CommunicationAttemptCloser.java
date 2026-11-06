package xyz.avasilev.taskcomm.communication.internal.engine;

import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.avasilev.taskcomm.communication.internal.ClaimedCommunication;
import xyz.avasilev.taskcomm.communication.internal.CommunicationSql;
import xyz.avasilev.taskcomm.communication.internal.lifecycle.CommunicationRetries;

/**
 * Закрытие попытки коммуникации воркером или вызывающим синхронного приёма. Закрытие попытки, запись
 * доменной формы ответа и правка коммуникации — одна транзакция
 * (docs/adr/0073-result-written-with-attempt-close.md).
 *
 * <p><b>Закрытие условно по токену аренды и {@code finished_at IS NULL}</b>
 * (docs/adr/0067-expired-lease-closes-attempt.md). Не затронута ни одна строка — попытку уже закрыл
 * переподхват: WARN, исход и ответ не сохраняются, исключения нет.
 */
@Slf4j
public class CommunicationAttemptCloser {

    /** Начало причины {@code FAILED} у коммуникации, которой процессор запретил повтор. */
    static final String DO_NOT_RETRY = "повторять запрещено: ";

    /** Начало причины {@code FAILED} у синхронно принятой коммуникации. */
    static final String SYNC_FAILED = "синхронный приём не повторяется: ";

    /** Блокировка строки коммуникации: приём колбэка правит ту же строку (docs/adr/0099-early-callback-proves-delivery.md). */
    private static final String LOCK =
            "SELECT id::text FROM tc_communication WHERE execution_created_at = :executionCreatedAt AND id = :id FOR UPDATE";

    private static final String CLOSE = """
            UPDATE tc_communication_attempt
               SET status = :status, status_description = :reason, finished_at = now(), updated_at = now(),
                   response_payload = coalesce(CAST(:responsePayload AS jsonb), response_payload)
             WHERE execution_created_at = :executionCreatedAt
               AND id = :attemptId
               AND lease_token = :leaseToken
               AND finished_at IS NULL
            """;

    private final JdbcClient jdbc;

    private final TransactionTemplate transactions;

    private final CommunicationRetries retries;

    public CommunicationAttemptCloser(
            final javax.sql.DataSource dataSource,
            final PlatformTransactionManager transactionManager,
            final CommunicationRetries retries) {
        this.jdbc = JdbcClient.create(dataSource);
        this.transactions = CommunicationSql.transactions(transactionManager);
        this.retries = retries;
    }

    /**
     * Закрывает попытку успехом: коммуникация {@code SUCCEEDED}, ждёт ответа или передаётся в обработку
     * раннего колбэка.
     *
     * @param attempt захваченная работа
     * @param response доменная форма ответа в JSON или {@code null}
     * @param doNotAwait процессор сказал «не ждать»
     * @return {@code true}, если попытка была открыта и закрыта этим вызовом
     */
    public boolean succeed(final ClaimedCommunication attempt, final String response, final boolean doNotAwait) {
        return this.close(
                attempt,
                new Outcome("SUCCEEDED", null, response),
                jdbc -> this.retries.afterSuccess(jdbc, closed(attempt), doNotAwait));
    }

    /**
     * Закрывает попытку пропуском: процессор вызвал {@code skip(reason)} и вернулся нормально.
     *
     * @param attempt захваченная работа
     * @param reason причина пропуска
     * @param response доменная форма ответа в JSON или {@code null}
     * @return {@code true}, если попытка была открыта и закрыта этим вызовом
     */
    boolean skip(final ClaimedCommunication attempt, final String reason, final String response) {
        return this.close(
                attempt,
                new Outcome("SKIPPED", reason, response),
                jdbc -> this.retries.afterSkip(jdbc, closed(attempt), reason));
    }

    /**
     * Закрывает попытку неуспехом: коммуникация возвращается в очередь со следующей попыткой или
     * становится терминальной.
     *
     * @param attempt захваченная работа
     * @param failure причина неуспеха попытки
     * @param doNotRetry причина запрета повтора от процессора или {@code null}
     * @return {@code true}, если попытка была открыта и закрыта этим вызовом
     */
    boolean fail(final ClaimedCommunication attempt, final String failure, final String doNotRetry) {
        return this.close(
                attempt,
                new Outcome("FAILED", failure, null),
                jdbc -> this.retries.afterUnsuccessful(
                        jdbc,
                        closed(attempt),
                        doNotRetry == null,
                        doNotRetry == null ? null : DO_NOT_RETRY + doNotRetry));
    }

    /**
     * Закрывает попытку синхронного приёма неуспехом: коммуникация {@code FAILED} без повтора —
     * отвечать отправителю повтором уже некому (docs/adr/0133-synchronous-inbound-reception.md).
     *
     * @param attempt захваченная работа
     * @param failure причина неуспеха
     * @return {@code true}, если попытка была открыта и закрыта этим вызовом
     */
    boolean failWithoutRetry(final ClaimedCommunication attempt, final String failure) {
        return this.close(
                attempt,
                new Outcome("FAILED", failure, null),
                jdbc -> this.retries.afterUnsuccessful(jdbc, closed(attempt), false, SYNC_FAILED + failure));
    }

    private boolean close(
            final ClaimedCommunication attempt, final Outcome outcome, final Consumer<JdbcClient> afterClose) {
        final boolean closed = Boolean.TRUE.equals(this.transactions.execute(transaction -> {
            this.jdbc
                    .sql(LOCK)
                    .param(
                            "executionCreatedAt",
                            CommunicationSql.parameter(attempt.target().executionCreatedAt()))
                    .param("id", attempt.target().id())
                    .query(String.class)
                    .optional();
            final int updated = this.jdbc
                    .sql(CLOSE)
                    .param("status", outcome.status())
                    .param("reason", outcome.reason())
                    .param("responsePayload", outcome.response())
                    .param(
                            "executionCreatedAt",
                            CommunicationSql.parameter(attempt.target().executionCreatedAt()))
                    .param("attemptId", attempt.lease().attemptId())
                    .param("leaseToken", attempt.lease().token())
                    .update();
            if (updated == 0) {
                return false;
            }
            afterClose.accept(this.jdbc);
            return true;
        }));
        if (!closed) {
            log.warn(
                    "Попытка {} коммуникации {} с '{}' уже закрыта — аренда истекла раньше, чем обработка "
                            + "закончилась. Исход {} и ответ не сохранены; коммуникация будет или уже была "
                            + "выполнена повторно.",
                    attempt.lease().attemptId(),
                    attempt.target().id(),
                    attempt.system(),
                    outcome.status());
        }
        return closed;
    }

    private static CommunicationRetries.Closed closed(final ClaimedCommunication attempt) {
        return new CommunicationRetries.Closed(
                attempt.target(), attempt.lease().attemptId(), attempt.system(), attempt.direction(), attempt.phase());
    }

    /**
     * Исход попытки.
     *
     * @param status статус попытки
     * @param reason причина или {@code null}
     * @param response доменная форма ответа в JSON или {@code null}
     */
    private record Outcome(String status, String reason, String response) {}
}
