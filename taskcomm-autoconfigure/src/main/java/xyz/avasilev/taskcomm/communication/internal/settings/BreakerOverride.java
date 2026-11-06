package xyz.avasilev.taskcomm.communication.internal.settings;

import java.util.List;

/**
 * Размыкатель системы на одном уровне разрешения (docs/adr/0065-communication-circuit-breaker.md).
 * Незаданное — {@code null}: берётся с уровня слабее.
 *
 * @param enabled включён ли размыкатель системы или {@code null}
 * @param exceptions считающиеся исключения — полные имена классов — или {@code null}; пустой список — все
 */
public record BreakerOverride(Boolean enabled, List<String> exceptions) {

    /** Ничего не задано. */
    public static final BreakerOverride NONE = new BreakerOverride(null, null);

    /** Копирует список исключений. */
    public BreakerOverride {
        exceptions = exceptions == null ? null : List.copyOf(exceptions);
    }
}
