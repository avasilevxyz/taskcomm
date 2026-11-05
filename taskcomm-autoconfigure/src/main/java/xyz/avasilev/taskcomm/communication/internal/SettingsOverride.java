package xyz.avasilev.taskcomm.communication.internal;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Всё, что задаёт один уровень разрешения настроек коммуникаций
 * (docs/adr/0061-processor-configuration-annotations.md). Приоритет, отказные статусы и срок ожидания
 * ответа — свойства системы: они осмыслены только в {@code yaml} системы и в аннотации на классе
 * (docs/adr/0134-communication-public-api-shape.md).
 *
 * @param priority приоритет по умолчанию или {@code null}
 * @param lease аренда
 * @param retry повторы
 * @param failureStatuses отказные статусы или {@code null} — не заданы
 * @param responseTimeout срок ожидания асинхронного ответа или {@code null} — не задан
 */
public record SettingsOverride(
        Integer priority,
        LeaseOverride lease,
        RetryOverride retry,
        List<String> failureStatuses,
        Duration responseTimeout) {

    /** Ничего не задано. */
    public static final SettingsOverride NONE =
            new SettingsOverride(null, LeaseOverride.NONE, RetryOverride.NONE, null, null);

    /**
     * Проверяет, что группы настроек заданы, хотя бы пустыми, и копирует список статусов.
     *
     * @throws NullPointerException если группа пуста
     */
    public SettingsOverride {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(retry, "retry");
        failureStatuses = failureStatuses == null ? null : List.copyOf(failureStatuses);
    }

    /**
     * Уровень без срока ожидания ответа.
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
        this(priority, lease, retry, failureStatuses, null);
    }
}
