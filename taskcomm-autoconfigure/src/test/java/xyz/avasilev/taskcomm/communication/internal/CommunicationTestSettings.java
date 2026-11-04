package xyz.avasilev.taskcomm.communication.internal;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/** Настройки подсистемы коммуникаций для тестов. */
final class CommunicationTestSettings {

    /** Основной поллер: форма {@code deadline}, четыре воркера. */
    static final PollerSettings MAIN = new PollerSettings(
            PollerSettings.MAIN,
            Set.of(),
            Duration.ofSeconds(1),
            10,
            4,
            ClaimForm.DEADLINE,
            Duration.ofMinutes(1),
            0.1);

    /** Раскладка из одного основного поллера. */
    static final PollerLayout POLLERS = new PollerLayout(MAIN, List.of());

    private CommunicationTestSettings() {}

    /**
     * Глобальные настройки попыток.
     *
     * @param maxAttempts лимит попыток
     * @param initialDelay первая задержка backoff
     * @param maxExecutionTime предел обработки
     * @return настройки попыток
     */
    static AttemptSettings global(final int maxAttempts, final Duration initialDelay, final Duration maxExecutionTime) {
        return new AttemptSettings(
                new RetryPolicy(maxAttempts, initialDelay, 2, initialDelay.plusHours(1), 0.2),
                new LeaseSettings(Duration.ofSeconds(6), Duration.ofSeconds(1), maxExecutionTime));
    }
}
