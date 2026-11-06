package xyz.avasilev.taskcomm.communication.internal;

import java.time.Duration;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.transaction.PlatformTransactionManager;
import xyz.avasilev.taskcomm.execution.internal.UuidV7;

/**
 * Наблюдаемость движка коммуникаций: префикс ключей MDC, метрики и такт снимка на всю схему
 * (docs/rules/observability.md).
 *
 * @param mdcPrefix префикс ключей MDC — {@code taskcomm.logging.mdc-prefix}
 * @param metrics метрики или {@link CommunicationMetrics#NONE}; с {@code NONE} снимок не считается
 * @param snapshotInterval такт снимка — {@code taskcomm.metrics.interval}
 */
public record CommunicationObservation(String mdcPrefix, CommunicationMetrics metrics, Duration snapshotInterval) {

    /** Такт снимка по умолчанию. */
    public static final Duration DEFAULT_SNAPSHOT_INTERVAL = Duration.ofSeconds(30);

    /**
     * Проверяет, что поля заданы, а такт положителен.
     *
     * @throws NullPointerException если поле пустое
     * @throws IllegalArgumentException если такт не положителен
     */
    public CommunicationObservation {
        Objects.requireNonNull(mdcPrefix, "mdcPrefix");
        Objects.requireNonNull(metrics, "metrics");
        Objects.requireNonNull(snapshotInterval, "snapshotInterval");
        if (snapshotInterval.isNegative() || snapshotInterval.isZero()) {
            throw new IllegalArgumentException("Такт снимка метрик должен быть положительным: " + snapshotInterval);
        }
    }

    /**
     * Такт снимка метрик на всю схему для движка (docs/adr/0102-schema-wide-metrics-snapshot.md).
     *
     * @param dataSource {@code DataSource} стартера
     * @param transactionManager менеджер транзакций стартера
     * @param ids генератор идентификаторов
     * @param settings итоговые настройки коммуникаций — раскладка поллеров
     * @param registry реестр процессоров — известные системы
     * @return такт снимка или {@code null}, если метрик нет и снимок не считается
     */
    Runnable snapshotTick(
            final DataSource dataSource,
            final PlatformTransactionManager transactionManager,
            final UuidV7 ids,
            final CommunicationSettings settings,
            final CommunicationProcessorRegistry registry) {
        if (this.metrics.equals(CommunicationMetrics.NONE)) {
            return null;
        }
        final CommunicationMetricsSnapshot snapshot = new CommunicationMetricsSnapshot(
                dataSource,
                transactionManager,
                ids,
                settings.pollers(),
                registry.systems(),
                this.snapshotInterval,
                this.metrics);
        return snapshot::tick;
    }
}
