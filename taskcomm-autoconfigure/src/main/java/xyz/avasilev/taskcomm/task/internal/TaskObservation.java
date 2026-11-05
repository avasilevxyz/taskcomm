package xyz.avasilev.taskcomm.task.internal;

import java.util.Objects;

/**
 * Наблюдаемость движка задач: префикс ключей MDC и метрики попыток (docs/rules/observability.md).
 *
 * @param mdcPrefix префикс ключей MDC — {@code taskcomm.logging.mdc-prefix}
 * @param metrics метрики попыток или {@link TaskMetrics#NONE}
 */
public record TaskObservation(String mdcPrefix, TaskMetrics metrics) {

    /**
     * Проверяет, что оба поля заданы.
     *
     * @throws NullPointerException если поле пустое
     */
    public TaskObservation {
        Objects.requireNonNull(mdcPrefix, "mdcPrefix");
        Objects.requireNonNull(metrics, "metrics");
    }

    /**
     * Без метрик, с префиксом по умолчанию — для тестов и сборки без Micrometer.
     *
     * @return наблюдаемость по умолчанию
     */
    public static TaskObservation defaults() {
        return new TaskObservation("tc.", TaskMetrics.NONE);
    }
}
