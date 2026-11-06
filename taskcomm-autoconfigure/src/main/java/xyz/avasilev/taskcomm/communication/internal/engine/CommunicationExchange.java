package xyz.avasilev.taskcomm.communication.internal.engine;

import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.avasilev.taskcomm.communication.api.ExchangeMessage;
import xyz.avasilev.taskcomm.communication.internal.CommunicationCodec;
import xyz.avasilev.taskcomm.communication.internal.CommunicationRef;
import xyz.avasilev.taskcomm.communication.internal.CommunicationSql;

/**
 * Запись обмена из процессора — в строку своей попытки, <b>немедленно, своей короткой транзакцией</b>
 * (docs/adr/0055-communication-exchange-storage.md): падение после отправки не должно стереть то, что
 * ушло наружу. Условий по аренде нет: в свою попытку воркер пишет всегда, даже закрытую
 * (docs/adr/0067-expired-lease-closes-attempt.md).
 */
class CommunicationExchange {

    private static final String REQUEST = """
            UPDATE tc_communication_attempt
               SET request_id = CAST(:messageId AS text), request_at = CAST(:at AS timestamptz),
                   request_headers = CAST(:headers AS jsonb), request_body = :body, updated_at = now()
             WHERE execution_created_at = :executionCreatedAt AND id = :attemptId
            """;

    private static final String RESPONSE = """
            UPDATE tc_communication_attempt
               SET response_id = CAST(:messageId AS text), response_at = coalesce(CAST(:at AS timestamptz), now()),
                   response_status = CAST(:status AS smallint), response_headers = CAST(:headers AS jsonb),
                   response_body = :body, updated_at = now()
             WHERE execution_created_at = :executionCreatedAt AND id = :attemptId
            """;

    private final JdbcClient jdbc;

    private final TransactionTemplate transactions;

    private final CommunicationCodec codec;

    CommunicationExchange(
            final DataSource dataSource,
            final PlatformTransactionManager transactionManager,
            final CommunicationCodec codec) {
        this.jdbc = JdbcClient.create(dataSource);
        this.transactions = CommunicationSql.transactions(transactionManager);
        this.codec = codec;
    }

    /**
     * Пишет запрос в попытку.
     *
     * @param target коммуникация
     * @param attemptId попытка
     * @param message запрос
     */
    void request(final CommunicationRef target, final UUID attemptId, final ExchangeMessage message) {
        this.write(REQUEST, target, attemptId, message);
    }

    /**
     * Пишет ответ в попытку; время ответа без явного — момент записи по часам базы.
     *
     * @param target коммуникация
     * @param attemptId попытка
     * @param message ответ
     */
    void response(final CommunicationRef target, final UUID attemptId, final ExchangeMessage message) {
        this.write(RESPONSE, target, attemptId, message);
    }

    private void write(
            final String sql, final CommunicationRef target, final UUID attemptId, final ExchangeMessage message) {
        this.transactions.executeWithoutResult(status -> this.jdbc
                .sql(sql)
                .param("messageId", message.id().orElse(null))
                .param("at", message.at().map(CommunicationSql::parameter).orElse(null))
                .param("status", message.status().isPresent() ? message.status().getAsInt() : null)
                .param("headers", this.codec.write(message.headers().asMap()))
                .param("body", message.body())
                .param("executionCreatedAt", CommunicationSql.parameter(target.executionCreatedAt()))
                .param("attemptId", attemptId)
                .update());
    }
}
