package xyz.avasilev.taskcomm.communication.internal.settings;

import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.ClassUtils;
import xyz.avasilev.taskcomm.TaskcommConfigurationException;

/**
 * Разрешение лимита скорости и размыкателя системы: {@code yaml} системы сильнее аннотации на классе
 * (docs/adr/0061-processor-configuration-annotations.md, docs/adr/0065-communication-circuit-breaker.md,
 * docs/adr/0137-rate-limit-and-circuit-breaker.md).
 *
 * <p>Падение при старте: лимит не больше нуля; исключение размыкателя — не класс или не
 * {@link Throwable}. Предупреждение: список исключений у системы с выключенным размыкателем; срок пробы
 * меньше предела обработки отправки.
 */
@Slf4j
final class GuardResolution {

    private GuardResolution() {}

    /**
     * Лимит отправок в секунду.
     *
     * @param system внешняя система
     * @param yaml уровень системы в {@code yaml}
     * @param onClass аннотация на классе
     * @return лимит или {@code null} — не ограничено
     */
    static Double rateLimit(final String system, final SettingsOverride yaml, final SettingsOverride onClass) {
        final Double chosen = yaml.rateLimit() != null ? yaml.rateLimit() : onClass.rateLimit();
        if (chosen != null && !(chosen > 0)) {
            throw new TaskcommConfigurationException("Внешняя система '" + system + "': rate-limit-per-instance = "
                    + chosen + ", а должен быть больше нуля.");
        }
        return chosen;
    }

    /**
     * Размыкатель системы.
     *
     * @param system внешняя система
     * @param settings пороги подсистемы
     * @param yaml уровень системы в {@code yaml}
     * @param onClass аннотация на классе
     * @param sendLease аренда отправки — для предупреждения о сроке пробы
     * @return размыкатель
     */
    static BreakerPolicy breaker(
            final String system,
            final BreakerSettings settings,
            final SettingsOverride yaml,
            final SettingsOverride onClass,
            final LeaseSettings sendLease) {
        final boolean enabled =
                yaml.breaker().enabled() != null ? yaml.breaker().enabled() : settings.enabled();
        final List<String> names = yaml.breaker().exceptions() != null
                ? yaml.breaker().exceptions()
                : onClass.breaker().exceptions();
        final List<Class<?>> exceptions = new ArrayList<>();
        if (names != null) {
            names.forEach(name -> exceptions.add(throwable(system, name)));
            if (!enabled && !names.isEmpty()) {
                log.warn(
                        "Внешняя система '{}': список исключений размыкателя задан, а размыкатель выключен — список "
                                + "ни на что не действует.",
                        system);
            }
        }
        if (enabled && settings.probeTimeout().compareTo(sendLease.maxExecutionTime()) < 0) {
            log.warn(
                    "Внешняя система '{}': срок пробы размыкателя {} меньше предела обработки {} — проба провалится "
                            + "раньше, чем закончится нормальный медленный вызов.",
                    system,
                    settings.probeTimeout(),
                    sendLease.maxExecutionTime());
        }
        return new BreakerPolicy(settings, enabled, exceptions);
    }

    private static Class<?> throwable(final String system, final String name) {
        final Class<?> type;
        try {
            type = ClassUtils.forName(name, GuardResolution.class.getClassLoader());
        } catch (final ClassNotFoundException | LinkageError missing) {
            throw new TaskcommConfigurationException(
                    "Внешняя система '" + system + "': исключения размыкателя '" + name + "' нет в classpath.",
                    missing);
        }
        if (!Throwable.class.isAssignableFrom(type)) {
            throw new TaskcommConfigurationException(
                    "Внешняя система '" + system + "': '" + name + "' в исключениях размыкателя — не исключение.");
        }
        return type;
    }
}
