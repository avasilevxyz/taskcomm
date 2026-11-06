package xyz.avasilev.taskcomm.autoconfigure;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import xyz.avasilev.taskcomm.TaskcommConfigurationException;
import xyz.avasilev.taskcomm.communication.internal.settings.AttemptSettings;
import xyz.avasilev.taskcomm.communication.internal.settings.BreakerOverride;
import xyz.avasilev.taskcomm.communication.internal.settings.BreakerSettings;
import xyz.avasilev.taskcomm.communication.internal.settings.ClaimForm;
import xyz.avasilev.taskcomm.communication.internal.settings.LeaseOverride;
import xyz.avasilev.taskcomm.communication.internal.settings.LeaseSettings;
import xyz.avasilev.taskcomm.communication.internal.settings.PollerLayout;
import xyz.avasilev.taskcomm.communication.internal.settings.PollerSettings;
import xyz.avasilev.taskcomm.communication.internal.settings.RetryOverride;
import xyz.avasilev.taskcomm.communication.internal.settings.RetryPolicy;
import xyz.avasilev.taskcomm.communication.internal.settings.SettingsOverride;
import xyz.avasilev.taskcomm.communication.internal.settings.SystemOverrides;
import xyz.avasilev.taskcomm.communication.internal.settings.Timing;

/**
 * Перевод свойств {@code taskcomm.communication.*} во внутренние значения подсистемы коммуникаций.
 * Проверки значений живут во внутренних типах; здесь к сообщению добавляется путь свойства. Свой
 * у подсистемы, как и её внутренние типы.
 */
final class CommunicationPropertiesMapping {

    private static final String PREFIX = "taskcomm.communication.";

    private CommunicationPropertiesMapping() {}

    /**
     * Глобальные настройки попыток — уровень 5 разрешения.
     *
     * @param communication свойства коммуникаций
     * @return настройки попыток
     */
    static AttemptSettings global(final TaskcommProperties.Communication communication) {
        final TaskcommProperties.CommunicationRetry retry = communication.getRetry();
        final TaskcommProperties.CommunicationLease lease = communication.getLease();
        return new AttemptSettings(
                withPath(
                        PREFIX + "retry",
                        () -> new RetryPolicy(
                                retry.getMaxAttempts(),
                                retry.getInitialDelay(),
                                retry.getMultiplier(),
                                retry.getMaxDelay(),
                                retry.getJitter())),
                withPath(
                        PREFIX + "lease",
                        () -> new LeaseSettings(
                                lease.getDuration(), lease.getHeartbeatInterval(), lease.getMaxExecutionTime())));
    }

    /**
     * Переопределения систем — уровни 1 и 2 разрешения.
     *
     * @param communication свойства коммуникаций
     * @return система → переопределения
     */
    static Map<String, SystemOverrides> systems(final TaskcommProperties.Communication communication) {
        final Map<String, SystemOverrides> result = new LinkedHashMap<>();
        communication.getSystems().forEach((system, settings) -> {
            final Map<String, SettingsOverride> phases = new LinkedHashMap<>();
            settings.getPhases()
                    .forEach((phase, phaseSettings) -> phases.put(
                            phase,
                            new SettingsOverride(
                                    phaseSettings.getPriority(),
                                    lease(phaseSettings.getLease()),
                                    retry(phaseSettings.getRetry()),
                                    phaseSettings.getFailureStatuses(),
                                    phaseSettings.getResponseTimeout(),
                                    phaseSettings.getRateLimitPerInstance(),
                                    breaker(phaseSettings.getCircuitBreaker()))));
            result.put(
                    system,
                    new SystemOverrides(
                            new SettingsOverride(
                                    settings.getPriority(),
                                    lease(settings.getLease()),
                                    retry(settings.getRetry()),
                                    settings.getFailureStatuses(),
                                    settings.getResponseTimeout(),
                                    settings.getRateLimitPerInstance(),
                                    breaker(settings.getCircuitBreaker())),
                            phases));
        });
        return result;
    }

    /**
     * Раскладка поллеров: основной и выделенные; не заданное у выделенного берётся у основного.
     *
     * @param communication свойства коммуникаций
     * @return раскладка
     */
    static PollerLayout pollers(final TaskcommProperties.Communication communication) {
        final TaskcommProperties.Poll main = communication.getPoll();
        final PollerSettings mainPoller = withPath(
                PREFIX + "poll",
                () -> new PollerSettings(
                        PollerSettings.MAIN,
                        Set.of(),
                        main.getInterval(),
                        main.getBatchSize(),
                        main.getWorkers(),
                        ClaimForm.valueOf(main.getClaimOrder().name()),
                        main.getDeadlineStep(),
                        main.getFifoShare()));
        final List<PollerSettings> dedicated = communication.getPollers().entrySet().stream()
                .map(entry -> dedicated(entry.getKey(), entry.getValue(), main))
                .toList();
        return withPath(PREFIX + "pollers", () -> new PollerLayout(mainPoller, dedicated));
    }

    /**
     * Срок ожидания ответа по умолчанию и такт подстраховочного поллера.
     *
     * @param communication свойства коммуникаций
     * @return сроки
     */
    static Timing timing(final TaskcommProperties.Communication communication) {
        return withPath(
                PREFIX.substring(0, PREFIX.length() - 1),
                () -> new Timing(
                        communication.getResponseTimeout(),
                        communication.getSafety().getInterval()));
    }

    /**
     * Пороги размыкателя подсистемы.
     *
     * @param communication свойства коммуникаций
     * @return пороги
     */
    static BreakerSettings breaker(final TaskcommProperties.Communication communication) {
        final TaskcommProperties.CommunicationBreaker breaker = communication.getCircuitBreaker();
        final TaskcommProperties.BreakerWait wait = breaker.getWait();
        return withPath(
                PREFIX + "circuit-breaker",
                () -> new BreakerSettings(
                        breaker.isEnabled(),
                        breaker.getWindow(),
                        breaker.getMinimumCalls(),
                        breaker.getFailureRateThreshold(),
                        new RetryPolicy(
                                1, wait.getInitialDelay(), wait.getMultiplier(), wait.getMaxDelay(), wait.getJitter()),
                        breaker.getProbe().getCalls(),
                        breaker.getProbe().getTimeout()));
    }

    /**
     * Детализация партиций для {@code date_trunc} — для оживления старой партиции при записи.
     *
     * @param period период партиций из свойств
     * @return единица {@code date_trunc}
     */
    static String truncUnit(final PartitionPeriod period) {
        return period.name().toLowerCase(Locale.ROOT);
    }

    private static PollerSettings dedicated(
            final String name,
            final TaskcommProperties.CommunicationPoller poller,
            final TaskcommProperties.Poll main) {
        return withPath(
                PREFIX + "pollers." + name,
                () -> new PollerSettings(
                        name,
                        Set.copyOf(poller.getSystems()),
                        poller.getInterval() != null ? poller.getInterval() : main.getInterval(),
                        poller.getBatchSize() != null ? poller.getBatchSize() : main.getBatchSize(),
                        poller.getWorkers() != null ? poller.getWorkers() : main.getWorkers(),
                        ClaimForm.valueOf(
                                (poller.getClaimOrder() != null ? poller.getClaimOrder() : main.getClaimOrder())
                                        .name()),
                        poller.getDeadlineStep() != null ? poller.getDeadlineStep() : main.getDeadlineStep(),
                        poller.getFifoShare() != null ? poller.getFifoShare() : main.getFifoShare()));
    }

    private static BreakerOverride breaker(final TaskcommProperties.CommunicationSystemBreaker breaker) {
        return new BreakerOverride(breaker.getEnabled(), breaker.getExceptions());
    }

    private static LeaseOverride lease(final TaskcommProperties.LeaseOverride lease) {
        return new LeaseOverride(lease.getDuration(), lease.getHeartbeatInterval(), lease.getMaxExecutionTime());
    }

    private static RetryOverride retry(final TaskcommProperties.RetryOverride retry) {
        return new RetryOverride(
                retry.getMaxAttempts(),
                retry.getInitialDelay(),
                retry.getMultiplier(),
                retry.getMaxDelay(),
                retry.getJitter());
    }

    private static <T> T withPath(final String path, final Supplier<T> value) {
        try {
            return value.get();
        } catch (final TaskcommConfigurationException invalid) {
            throw new TaskcommConfigurationException(path + ": " + invalid.getMessage(), invalid);
        }
    }
}
