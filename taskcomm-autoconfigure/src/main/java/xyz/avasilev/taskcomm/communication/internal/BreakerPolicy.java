package xyz.avasilev.taskcomm.communication.internal;

import java.util.List;

/**
 * Итоговый размыкатель одной системы: пороги подсистемы, включён ли он у системы и какие исключения
 * считает (docs/adr/0065-communication-circuit-breaker.md).
 *
 * @param settings пороги
 * @param enabled включён ли у системы
 * @param exceptions считающиеся исключения; пусто — все
 */
record BreakerPolicy(BreakerSettings settings, boolean enabled, List<Class<?>> exceptions) {

    /**
     * Копирует список.
     */
    BreakerPolicy {
        exceptions = List.copyOf(exceptions);
    }

    /**
     * Считает ли размыкатель это исключение отказом.
     *
     * @param failure исключение процессора
     * @return {@code true}, если список пуст или исключение в нём
     */
    boolean counts(final Throwable failure) {
        return this.exceptions.isEmpty() || this.exceptions.stream().anyMatch(type -> type.isInstance(failure));
    }
}
