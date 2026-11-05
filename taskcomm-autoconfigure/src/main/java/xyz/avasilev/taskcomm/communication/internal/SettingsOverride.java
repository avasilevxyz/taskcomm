package xyz.avasilev.taskcomm.communication.internal;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Всё, что задаёт один уровень разрешения настроек коммуникаций
 * (docs/adr/0061-processor-configuration-annotations.md). Приоритет, отказные статусы, срок ожидания
 * ответа, лимит скорости и размыкатель — свойства системы: они осмыслены только в {@code yaml} системы
 * и в аннотации на классе (docs/adr/0134-communication-public-api-shape.md).
 *
 * @param priority приоритет по умолчанию или {@code null}
 * @param lease аренда
 * @param retry повторы
 * @param failureStatuses отказные статусы или {@code null} — не заданы
 * @param responseTimeout срок ожидания асинхронного ответа или {@code null} — не задан
 * @param rateLimit отправок в секунду на экземпляр или {@code null} — не ограничено
 * @param breaker размыкатель
 */
public record SettingsOverride(
        Integer priority,
        LeaseOverride lease,
        RetryOverride retry,
        List<String> failureStatuses,
        Duration responseTimeout,
        Double rateLimit,
        BreakerOverride breaker) {

    /** Ничего не задано. */
    public static final SettingsOverride NONE =
            new SettingsOverride(null, LeaseOverride.NONE, RetryOverride.NONE, null, null, null, BreakerOverride.NONE);

    /**
     * Проверяет, что группы настроек заданы, хотя бы пустыми, и копирует список статусов.
     *
     * @throws NullPointerException если группа пуста
     */
    public SettingsOverride {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(retry, "retry");
        Objects.requireNonNull(breaker, "breaker");
        failureStatuses = failureStatuses == null ? null : List.copyOf(failureStatuses);
    }

    /**
     * Уровень только с приоритетом, арендой, повторами и отказными статусами.
     *
     * @param priority приоритет по умолчанию или {@code null}
     * @param lease аренда
     * @param retry повторы
     * @param failureStatuses отказные статусы или {@code null}
     */
    public SettingsOverride(
            final Integer priority,
            final LeaseOverride lease,
            final RetryOverride retry,
            final List<String> failureStatuses) {
        this(priority, lease, retry, failureStatuses, null, null, BreakerOverride.NONE);
    }

    /**
     * Задано ли что-нибудь из свойств системы — на фазе это ошибка.
     *
     * @return {@code true}, если задано
     */
    boolean hasSystemSettings() {
        return Stream.of(this.priority, this.failureStatuses, this.responseTimeout, this.rateLimit)
                        .anyMatch(Objects::nonNull)
                || !BreakerOverride.NONE.equals(this.breaker);
    }
}
