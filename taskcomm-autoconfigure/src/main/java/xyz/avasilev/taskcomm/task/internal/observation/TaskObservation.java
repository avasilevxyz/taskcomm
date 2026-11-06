package xyz.avasilev.taskcomm.task.internal.observation;

import java.time.Duration;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.transaction.PlatformTransactionManager;
import xyz.avasilev.taskcomm.execution.internal.UuidV7;
import xyz.avasilev.taskcomm.task.internal.processor.TaskProcessorRegistry;
import xyz.avasilev.taskcomm.task.internal.settings.TaskSettings;

/**
 * Наблюдаемость движка задач: префикс ключей MDC, метрики и такт снимка на всю схему
 * (docs/rules/observability.md).
 *
 * @param mdcPrefix префикс ключей MDC — {@code taskcomm.logging.mdc-prefix}
 * @param metrics метрики или {@link TaskMetrics#NONE}; с {@code NONE} снимок не считается
 * @param snapshotInterval такт снимка — {@code taskcomm.metrics.interval}
 */
public record TaskObservation(String mdcPrefix, TaskMetrics metrics, Duration snapshotInterval) {

    /** Такт снимка по умолчанию. */
    public static final Duration DEFAULT_SNAPSHOT_INTERVAL = Duration.ofSeconds(30);

    /**
     * Проверяет, что поля заданы, а такт положителен.
     *
     * @throws NullPointerException если поле пустое
     * @throws IllegalArgumentException если такт не положителен
     */
    public TaskObservation {
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
     * @param settings итоговые настройки задач — раскладка поллеров
     * @param registry реестр процессоров — известные типы
     * @return такт снимка или {@code null}, если метрик нет и снимок не считается
     */
    public Runnable snapshotTick(
            final DataSource dataSource,
            final PlatformTransactionManager transactionManager,
            final UuidV7 ids,
            final TaskSettings settings,
            final TaskProcessorRegistry registry) {
        if (this.metrics.equals(TaskMetrics.NONE)) {
            return null;
        }
        final TaskMetricsSnapshot snapshot = new TaskMetricsSnapshot(
                dataSource,
                transactionManager,
                ids,
                settings.pollers(),
                registry.types(),
                this.snapshotInterval,
                this.metrics);
        return snapshot::tick;
    }

    /**
     * Без метрик, с префиксом по умолчанию — для тестов и сборки без Micrometer.
     *
     * @return наблюдаемость по умолчанию
     */
    public static TaskObservation defaults() {
        return new TaskObservation("tc.", TaskMetrics.NONE, DEFAULT_SNAPSHOT_INTERVAL);
    }
}
