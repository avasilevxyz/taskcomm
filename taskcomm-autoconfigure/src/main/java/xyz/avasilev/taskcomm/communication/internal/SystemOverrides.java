package xyz.avasilev.taskcomm.communication.internal;

import java.util.Map;
import java.util.Objects;

/**
 * Переопределения внешней системы из {@code yaml}: на уровне системы и по фазам
 * ({@code taskcomm.communication.systems.<имя>}, docs/rules/configuration.md).
 *
 * @param system уровень системы
 * @param phases уровень фазы по ключу из {@code yaml} — {@code process}, {@code handle-response};
 *     неизвестный ключ и настройки системы на фазе — падение при разрешении настроек
 */
public record SystemOverrides(SettingsOverride system, Map<String, SettingsOverride> phases) {

    /** Система в {@code yaml} не упомянута. */
    public static final SystemOverrides NONE = new SystemOverrides(SettingsOverride.NONE, Map.of());

    /**
     * Копирует фазы и проверяет, что уровень системы задан.
     *
     * @throws NullPointerException если уровень системы пуст
     */
    public SystemOverrides {
        Objects.requireNonNull(system, "system");
        phases = Map.copyOf(phases);
    }
}
