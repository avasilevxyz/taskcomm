package xyz.avasilev.taskcomm.communication.internal;

import java.util.List;
import java.util.Objects;

/**
 * Всё, что задаёт один уровень разрешения настроек коммуникаций
 * (docs/adr/0061-processor-configuration-annotations.md). Приоритет и отказные статусы — свойства
 * системы: они осмыслены только в {@code yaml} системы и в аннотации на классе
 * (docs/adr/0134-communication-public-api-shape.md).
 *
 * @param priority приоритет по умолчанию или {@code null}
 * @param lease аренда
 * @param retry повторы
 * @param failureStatuses отказные статусы или {@code null} — не заданы
 */
public record SettingsOverride(
        Integer priority, LeaseOverride lease, RetryOverride retry, List<String> failureStatuses) {

    /** Ничего не задано. */
    public static final SettingsOverride NONE =
            new SettingsOverride(null, LeaseOverride.NONE, RetryOverride.NONE, null);

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
}
