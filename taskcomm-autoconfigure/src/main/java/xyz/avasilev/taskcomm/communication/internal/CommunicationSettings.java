package xyz.avasilev.taskcomm.communication.internal;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Итоговые настройки подсистемы коммуникаций после разрешения по пяти уровням
 * (docs/adr/0061-processor-configuration-annotations.md): настройки попыток по системе и фазе,
 * приоритет и отказные статусы по системе, раскладка поллеров. Собирается при старте один раз;
 * все проверки и печать итоговых значений — при сборке.
 */
public final class CommunicationSettings {

    /** Приоритет по умолчанию, если ни {@code yaml}, ни аннотация его не задают, — как у колонки. */
    static final int DEFAULT_PRIORITY = 100;

    private final AttemptSettings global;

    private final Map<Key, AttemptSettings> attempts;

    private final Map<String, Integer> priorities;

    private final Map<String, FailureStatuses> failureStatuses;

    private final Map<String, Duration> responseTimeouts;

    private final Timing timing;

    private final Map<String, Double> rateLimits;

    private final Map<String, BreakerPolicy> breakers;

    private final Duration heartbeatTick;

    private final PollerLayout pollers;

    CommunicationSettings(
            final AttemptSettings global,
            final Map<Key, AttemptSettings> attempts,
            final Map<String, Integer> priorities,
            final Map<String, FailureStatuses> failureStatuses,
            final Map<String, Duration> responseTimeouts,
            final Timing timing,
            final Guards guards,
            final Duration heartbeatTick,
            final PollerLayout pollers) {
        this.global = global;
        this.attempts = Map.copyOf(attempts);
        this.priorities = Map.copyOf(priorities);
        this.failureStatuses = Map.copyOf(failureStatuses);
        this.responseTimeouts = Map.copyOf(responseTimeouts);
        this.timing = timing;
        this.rateLimits = Map.copyOf(guards.rateLimits());
        this.breakers = Map.copyOf(guards.breakers());
        this.heartbeatTick = heartbeatTick;
        this.pollers = pollers;
    }

    /**
     * Разрешает настройки, проверяет их и печатает итог по каждой системе и фазе.
     *
     * @param global глобальные значения подсистемы — уровень 5
     * @param yaml переопределения систем из {@code yaml} — уровни 1 и 2
     * @param registry процессоры — их аннотации дают уровни 3 и 4
     * @param pollers раскладка поллеров
     * @param timing срок ожидания ответа по умолчанию и такт подстраховки
     * @return итоговые настройки
     * @throws xyz.avasilev.taskcomm.TaskcommConfigurationException если итоговое значение недопустимо
     */
    public static CommunicationSettings resolve(
            final AttemptSettings global,
            final Map<String, SystemOverrides> yaml,
            final CommunicationProcessorRegistry registry,
            final PollerLayout pollers,
            final Timing timing) {
        return resolve(global, yaml, registry, pollers, timing, BreakerSettings.DEFAULT);
    }

    /**
     * Разрешает настройки вместе с порогами размыкателя.
     *
     * @param global глобальные значения подсистемы — уровень 5
     * @param yaml переопределения систем из {@code yaml} — уровни 1 и 2
     * @param registry процессоры — их аннотации дают уровни 3 и 4
     * @param pollers раскладка поллеров
     * @param timing срок ожидания ответа по умолчанию и такт подстраховки
     * @param breaker пороги размыкателя подсистемы
     * @return итоговые настройки
     */
    public static CommunicationSettings resolve(
            final AttemptSettings global,
            final Map<String, SystemOverrides> yaml,
            final CommunicationProcessorRegistry registry,
            final PollerLayout pollers,
            final Timing timing,
            final BreakerSettings breaker) {
        return new CommunicationSettingsResolver(global, yaml, pollers, timing, breaker).resolve(registry);
    }

    /**
     * Лимит отправок в секунду на экземпляр.
     *
     * @param system внешняя система
     * @return лимит или {@code null} — не ограничено
     */
    Double rateLimit(final String system) {
        return this.rateLimits.get(system);
    }

    /**
     * Размыкатель системы.
     *
     * @param system внешняя система
     * @return размыкатель; у системы без процессора — выключенный
     */
    BreakerPolicy breaker(final String system) {
        return this.breakers.getOrDefault(system, new BreakerPolicy(BreakerSettings.DEFAULT, false, List.of()));
    }

    /**
     * Срок ожидания асинхронного ответа от системы (docs/adr/0086-async-response-in-outbound.md).
     *
     * @param system внешняя система
     * @return срок
     */
    Duration responseTimeout(final String system) {
        return this.responseTimeouts.getOrDefault(system, this.timing.responseTimeout());
    }

    /**
     * Такт подстраховочного поллера.
     *
     * @return такт
     */
    Duration safetyInterval() {
        return this.timing.safetyInterval();
    }

    /**
     * Итоговые настройки попыток. Система без процессора и без {@code yaml} получает глобальные
     * значения: так закрываются её брошенные попытки.
     *
     * @param system внешняя система
     * @param phase фаза попытки
     * @return настройки попыток
     */
    AttemptSettings attempt(final String system, final CommunicationPhase phase) {
        return this.attempts.getOrDefault(new Key(system, phase), this.global);
    }

    /**
     * Приоритет коммуникаций системы по умолчанию.
     *
     * @param system внешняя система
     * @return приоритет, больше нуля
     */
    int priority(final String system) {
        return this.priorities.getOrDefault(system, DEFAULT_PRIORITY);
    }

    /**
     * Отказные статусы ответа системы.
     *
     * @param system внешняя система
     * @return отказные статусы; у системы без процессора — пусто
     */
    FailureStatuses failureStatuses(final String system) {
        return this.failureStatuses.getOrDefault(system, FailureStatuses.NONE);
    }

    /**
     * Такт потока heartbeat — самый короткий итоговый интервал heartbeat.
     *
     * @return период такта
     */
    Duration heartbeatTick() {
        return this.heartbeatTick;
    }

    /**
     * Раскладка поллеров.
     *
     * @return раскладка
     */
    PollerLayout pollers() {
        return this.pollers;
    }

    /**
     * Ключ настроек попыток.
     *
     * @param system внешняя система
     * @param phase фаза
     */
    record Key(String system, CommunicationPhase phase) {}

    /**
     * Лимиты скорости и размыкатели по системам.
     *
     * @param rateLimits лимит отправок в секунду; системы без лимита нет в отображении
     * @param breakers размыкатели
     */
    record Guards(Map<String, Double> rateLimits, Map<String, BreakerPolicy> breakers) {}
}
