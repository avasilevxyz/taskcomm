package xyz.avasilev.taskcomm.communication.internal;

import java.util.Objects;

/**
 * Наблюдаемость движка коммуникаций: префикс ключей MDC и метрики (docs/rules/observability.md).
 *
 * @param mdcPrefix префикс ключей MDC — {@code taskcomm.logging.mdc-prefix}
 * @param metrics метрики или {@link CommunicationMetrics#NONE}
 */
public record CommunicationObservation(String mdcPrefix, CommunicationMetrics metrics) {

    /**
     * Проверяет, что оба поля заданы.
     *
     * @throws NullPointerException если поле пустое
     */
    public CommunicationObservation {
        Objects.requireNonNull(mdcPrefix, "mdcPrefix");
        Objects.requireNonNull(metrics, "metrics");
    }
}
