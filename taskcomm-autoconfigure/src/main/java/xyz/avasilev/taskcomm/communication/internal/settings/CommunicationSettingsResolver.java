package xyz.avasilev.taskcomm.communication.internal.settings;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import xyz.avasilev.taskcomm.TaskcommConfigurationException;
import xyz.avasilev.taskcomm.communication.internal.CommunicationPhase;
import xyz.avasilev.taskcomm.communication.internal.ContractShape;
import xyz.avasilev.taskcomm.communication.internal.processor.CommunicationProcessorRegistry;
import xyz.avasilev.taskcomm.communication.internal.processor.RegisteredProcessor;

/**
 * Разрешение настроек коммуникаций по пяти уровням, от сильного к слабому: {@code yaml} системы
 * и фазы, {@code yaml} системы, аннотация на методе, аннотация на классе, глобальное значение
 * (docs/adr/0061-processor-configuration-annotations.md). Любое значение из {@code yaml} сильнее любой
 * аннотации. Итог по каждой системе и фазе печатается с уровнем каждого значения.
 *
 * <p>Падение при старте: недопустимые значения аренды и повторов (docs/rules/configuration.md);
 * приоритет не больше нуля; приоритет или отказные статусы на фазе {@code yaml}; неизвестная фаза;
 * неверный отказной статус. Предупреждение: heartbeat больше трети аренды; аренда не длиннее такта
 * поллера системы.
 */
@Slf4j
final class CommunicationSettingsResolver {

    private static final String SYSTEMS = "taskcomm.communication.systems.";

    private final AttemptSettings global;

    private final Map<String, SystemOverrides> yaml;

    private final PollerLayout pollers;

    private final Timing timing;

    private final BreakerSettings breaker;

    private final Map<String, Map<CommunicationPhase, SettingsOverride>> phases = new HashMap<>();

    private final List<String> report = new ArrayList<>();

    CommunicationSettingsResolver(
            final AttemptSettings global,
            final Map<String, SystemOverrides> yaml,
            final PollerLayout pollers,
            final Timing timing,
            final BreakerSettings breaker) {
        this.timing = timing;
        this.breaker = breaker;
        this.global = global;
        this.yaml = Map.copyOf(yaml);
        this.pollers = pollers;
    }

    CommunicationSettings resolve(final CommunicationProcessorRegistry registry) {
        this.yaml.forEach(this::readPhases);
        final Map<CommunicationSettings.Key, AttemptSettings> attempts = new HashMap<>();
        final Map<String, Integer> priorities = new HashMap<>();
        final Map<String, FailureStatuses> statuses = new HashMap<>();
        final Map<String, Duration> timeouts = new HashMap<>();
        final Map<String, Double> rateLimits = new HashMap<>();
        final Map<String, BreakerPolicy> breakers = new HashMap<>();
        for (final RegisteredProcessor processor : registry.all()) {
            final String system = processor.system();
            final SettingsOverride onClass = ProcessorSettingsAnnotations.onClass(processor.processorClass());
            final int priority = this.priority(system, onClass);
            priorities.put(system, priority);
            final FailureStatuses failure = this.failureStatuses(system, onClass, processor.shape());
            statuses.put(system, failure);
            final Duration timeout = this.responseTimeout(system, onClass);
            timeouts.put(system, timeout);
            this.report.add("система '" + system + "' (" + processor.shape() + "), приоритет " + priority
                    + ", отказные статусы " + failure.values() + ", срок ожидания ответа " + timeout);
            for (final CommunicationPhase phase : CommunicationPhase.values()) {
                final SettingsOverride onMethod =
                        ProcessorSettingsAnnotations.onMethod(processor.processorClass(), phase);
                attempts.put(
                        new CommunicationSettings.Key(system, phase), this.attempt(system, phase, onMethod, onClass));
            }
            this.guards(
                    system,
                    onClass,
                    attempts.get(new CommunicationSettings.Key(system, CommunicationPhase.PROCESS))
                            .lease(),
                    rateLimits,
                    breakers);
        }
        for (final String system : this.yaml.keySet()) {
            if (registry.find(system).isEmpty()) {
                this.report.add("система '" + system + "' — без процессора, только yaml");
                priorities.put(system, this.priority(system, SettingsOverride.NONE));
                timeouts.put(system, this.responseTimeout(system, SettingsOverride.NONE));
                for (final CommunicationPhase phase : CommunicationPhase.values()) {
                    attempts.put(
                            new CommunicationSettings.Key(system, phase),
                            this.attempt(system, phase, SettingsOverride.NONE, SettingsOverride.NONE));
                }
            }
        }
        log.info(
                "Итоговые настройки коммуникаций по системам и фазам, в скобках — уровень значения:\n  {}",
                String.join("\n  ", this.report));
        return new CommunicationSettings(
                this.global,
                attempts,
                priorities,
                statuses,
                timeouts,
                this.timing,
                new CommunicationSettings.Guards(rateLimits, breakers),
                this.heartbeatTick(attempts),
                this.pollers);
    }

    /** Лимит скорости и размыкатель системы (docs/adr/0137-rate-limit-and-circuit-breaker.md). */
    private void guards(
            final String system,
            final SettingsOverride onClass,
            final LeaseSettings sendLease,
            final Map<String, Double> rateLimits,
            final Map<String, BreakerPolicy> breakers) {
        final SettingsOverride yamlSystem =
                this.yaml.getOrDefault(system, SystemOverrides.NONE).system();
        final Double rate = GuardResolution.rateLimit(system, yamlSystem, onClass);
        if (rate != null) {
            rateLimits.put(system, rate);
        }
        final BreakerPolicy policy = GuardResolution.breaker(system, this.breaker, yamlSystem, onClass, sendLease);
        breakers.put(system, policy);
        this.report.add("  лимит скорости " + (rate == null ? "нет" : rate + " в секунду") + ", размыкатель "
                + (policy.enabled() ? "включён" : "выключен"));
    }

    private void readPhases(final String system, final SystemOverrides overrides) {
        final Map<CommunicationPhase, SettingsOverride> byPhase = new EnumMap<>(CommunicationPhase.class);
        overrides.phases().forEach((key, settings) -> {
            final CommunicationPhase phase = CommunicationPhase.ofYamlKey(key);
            final String path = SYSTEMS + system + ".phases." + key;
            if (phase == null) {
                throw new TaskcommConfigurationException(
                        path + ": фазы '" + key + "' нет. Фазы — process, handle-response.");
            }
            if (settings.hasSystemSettings()) {
                throw new TaskcommConfigurationException(
                        path + ": приоритет, отказные статусы, срок ожидания ответа, лимит скорости и размыкатель "
                                + "на фазе запрещены — "
                                + "это настройки системы. Задайте их в " + SYSTEMS + system + ".");
            }
            byPhase.put(phase, settings);
        });
        this.phases.put(system, byPhase);
    }

    private int priority(final String system, final SettingsOverride onClass) {
        final Integer fromYaml =
                this.yaml.getOrDefault(system, SystemOverrides.NONE).system().priority();
        if (fromYaml != null && fromYaml <= 0) {
            throw new TaskcommConfigurationException(SYSTEMS + system + ".priority = " + fromYaml
                    + ", а должен быть больше нуля. Шкала по возрастанию: меньше значит важнее.");
        }
        if (onClass.priority() != null && onClass.priority() <= 0) {
            throw new TaskcommConfigurationException("Приоритет системы '" + system + "' в аннотации = "
                    + onClass.priority() + ", а должен быть больше нуля.");
        }
        if (fromYaml != null) {
            return fromYaml;
        }
        return onClass.priority() != null ? onClass.priority() : CommunicationSettings.DEFAULT_PRIORITY;
    }

    private Duration responseTimeout(final String system, final SettingsOverride onClass) {
        final Duration fromYaml =
                this.yaml.getOrDefault(system, SystemOverrides.NONE).system().responseTimeout();
        final Duration chosen = fromYaml != null ? fromYaml : onClass.responseTimeout();
        if (chosen == null) {
            return this.timing.responseTimeout();
        }
        build(
                () -> {
                    Timing.positive("response-timeout", chosen);
                    return chosen;
                },
                "внешняя система '" + system + "'");
        return chosen;
    }

    private FailureStatuses failureStatuses(
            final String system, final SettingsOverride onClass, final ContractShape shape) {
        final List<String> fromYaml =
                this.yaml.getOrDefault(system, SystemOverrides.NONE).system().failureStatuses();
        final List<String> chosen = fromYaml != null ? fromYaml : onClass.failureStatuses();
        if (chosen == null) {
            return FailureStatuses.defaultFor(shape);
        }
        return build(() -> new FailureStatuses(chosen), "внешняя система '" + system + "'");
    }

    private AttemptSettings attempt(
            final String system,
            final CommunicationPhase phase,
            final SettingsOverride onMethod,
            final SettingsOverride onClass) {
        final List<Layer> layers = List.of(
                new Layer(
                        SettingsLevel.YAML_PHASE,
                        this.phases.getOrDefault(system, Map.of()).getOrDefault(phase, SettingsOverride.NONE)),
                new Layer(
                        SettingsLevel.YAML_SYSTEM,
                        this.yaml.getOrDefault(system, SystemOverrides.NONE).system()),
                new Layer(SettingsLevel.METHOD_ANNOTATION, onMethod),
                new Layer(SettingsLevel.CLASS_ANNOTATION, onClass));
        final String where = "внешняя система '" + system + "', метод " + phase.methodName();
        final LeaseSettings defaults = this.global.lease();
        final Picked<Duration> duration = pick(layers, o -> o.lease().duration(), defaults.duration());
        final Picked<Duration> heartbeat =
                pick(layers, o -> o.lease().heartbeatInterval(), defaults.heartbeatInterval());
        final Picked<Duration> maxExecution =
                pick(layers, o -> o.lease().maxExecutionTime(), defaults.maxExecutionTime());
        final LeaseSettings lease =
                build(() -> new LeaseSettings(duration.value(), heartbeat.value(), maxExecution.value()), where);
        final RetryPolicy retryDefaults = this.global.retry();
        final Picked<Integer> maxAttempts = pick(layers, o -> o.retry().maxAttempts(), retryDefaults.maxAttempts());
        final Picked<Duration> initialDelay = pick(layers, o -> o.retry().initialDelay(), retryDefaults.initialDelay());
        final Picked<Double> multiplier = pick(layers, o -> o.retry().multiplier(), retryDefaults.multiplier());
        final Picked<Duration> maxDelay = pick(layers, o -> o.retry().maxDelay(), retryDefaults.maxDelay());
        final Picked<Double> jitter = pick(layers, o -> o.retry().jitter(), retryDefaults.jitter());
        final RetryPolicy retry = build(
                () -> new RetryPolicy(
                        maxAttempts.value(),
                        initialDelay.value(),
                        multiplier.value(),
                        maxDelay.value(),
                        jitter.value()),
                where);
        this.report.add("  " + phase.methodName() + ": lease.duration " + duration + ", lease.heartbeat-interval "
                + heartbeat + ", lease.max-execution-time " + maxExecution + "; retry.max-attempts " + maxAttempts
                + ", retry.initial-delay " + initialDelay + ", retry.multiplier " + multiplier + ", retry.max-delay "
                + maxDelay + ", retry.jitter " + jitter);
        this.warnAboutLease(system, lease, where);
        return new AttemptSettings(retry, lease);
    }

    private void warnAboutLease(final String system, final LeaseSettings lease, final String where) {
        if (lease.heartbeatInterval().compareTo(lease.duration().dividedBy(3)) > 0) {
            log.warn(
                    "{}: heartbeat-interval = {} больше трети аренды {}. Два пропущенных такта — и аренда истечёт "
                            + "у живого воркера, коммуникация выполнится дважды. Рекомендуемый интервал — {}.",
                    where,
                    lease.heartbeatInterval(),
                    lease.duration(),
                    lease.duration().dividedBy(3));
        }
        final Duration pollInterval = this.pollers.pollerOf(system).interval();
        if (lease.duration().compareTo(pollInterval) <= 0) {
            log.warn(
                    "{}: аренда {} не длиннее такта поллера {}: брошенная работа возвращается не раньше такта, "
                            + "а аренда истекает быстрее.",
                    where,
                    lease.duration(),
                    pollInterval);
        }
    }

    private Duration heartbeatTick(final Map<CommunicationSettings.Key, AttemptSettings> attempts) {
        return attempts.values().stream()
                .map(settings -> settings.lease().heartbeatInterval())
                .min(Duration::compareTo)
                .orElse(this.global.lease().heartbeatInterval());
    }

    private static <TValue> Picked<TValue> pick(
            final List<Layer> layers, final Function<SettingsOverride, TValue> value, final TValue globalValue) {
        for (final Layer layer : layers) {
            final TValue candidate = value.apply(layer.settings());
            if (candidate != null) {
                return new Picked<>(candidate, layer.level());
            }
        }
        return new Picked<>(globalValue, SettingsLevel.GLOBAL);
    }

    private static <TSettings> TSettings build(final Supplier<TSettings> settings, final String where) {
        try {
            return settings.get();
        } catch (final TaskcommConfigurationException invalid) {
            throw new TaskcommConfigurationException(where + ": " + invalid.getMessage(), invalid);
        }
    }

    /**
     * Уровень разрешения со своими значениями.
     *
     * @param level уровень
     * @param settings значения уровня
     */
    private record Layer(SettingsLevel level, SettingsOverride settings) {}

    /**
     * Итоговое значение и уровень, с которого оно взято.
     *
     * @param value значение
     * @param level уровень
     * @param <TValue> тип значения
     */
    private record Picked<TValue>(TValue value, SettingsLevel level) {

        @Override
        public String toString() {
            return this.value + " (" + this.level.label() + ")";
        }
    }
}
