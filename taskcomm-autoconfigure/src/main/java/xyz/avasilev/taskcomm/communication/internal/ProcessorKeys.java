package xyz.avasilev.taskcomm.communication.internal;

import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.avasilev.taskcomm.communication.api.CorrelationKey;
import xyz.avasilev.taskcomm.communication.api.DedupKey;
import xyz.avasilev.taskcomm.execution.internal.UuidV7;

/**
 * Ключи, заданные процессором из {@code beforeProcess} и {@code process}: каждый — своей короткой
 * транзакцией {@code READ COMMITTED}, как запись обмена (docs/adr/0076-communication-deduplication.md,
 * docs/adr/0040-communication-correlation.md).
 */
class ProcessorKeys {

    private final JdbcClient jdbc;

    private final TransactionTemplate transactions;

    private final CommunicationDedup dedup;

    private final CommunicationCorrelation correlation;

    ProcessorKeys(final DataSource dataSource, final PlatformTransactionManager transactionManager, final UuidV7 ids) {
        this.jdbc = JdbcClient.create(dataSource);
        this.transactions = CommunicationSql.transactions(transactionManager);
        this.dedup = new CommunicationDedup(ids);
        this.correlation = new CommunicationCorrelation(ids);
    }

    /**
     * Регистрирует ключ дедупликации и отвечает, дубликат ли коммуникация. Ключ дубликата не хранится.
     *
     * @param attempt захваченная работа
     * @param key ключ
     * @return совпадение или пусто
     */
    Optional<CommunicationDedup.Match> dedup(final ClaimedCommunication attempt, final DedupKey key) {
        final CommunicationDedup.Owner owner =
                new CommunicationDedup.Owner(attempt.target(), attempt.direction(), attempt.system());
        return this.transactions.execute(status -> {
            final Optional<CommunicationDedup.Match> match = this.dedup.check(this.jdbc, owner, List.of(key));
            if (match.isEmpty()) {
                this.dedup.insert(this.jdbc, owner, List.of(key), false);
            }
            return match;
        });
    }

    /**
     * Регистрирует ключ корреляции за коммуникацией.
     *
     * @param attempt захваченная работа
     * @param key ключ
     */
    void correlate(final ClaimedCommunication attempt, final CorrelationKey key) {
        this.transactions.executeWithoutResult(status ->
                this.correlation.register(this.jdbc, attempt.target(), attempt.execution(), attempt.system(), key));
    }
}
