package xyz.avasilev.taskcomm.communication.internal;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;

/**
 * Метрики коммуникаций в Micrometer (docs/rules/observability.md). Создаётся только при Micrometer
 * на classpath и бине {@link MeterRegistry} у потребителя.
 *
 * <ul>
 *   <li>{@code tc.communication.attempt.duration} — время попытки: {@code system}, {@code direction},
 *       {@code phase}, {@code outcome};
 *   <li>{@code tc.communication.attempts} — число попыток: {@code system}, {@code outcome}, {@code retry};
 *   <li>{@code tc.communication.breaker.state} — состояние размыкателя на экземпляре: {@code system};
 *       0 — замкнут, 1 — проба, 2 — разомкнут;
 *   <li>{@code tc.communication.breaker.transitions} — переходы: {@code system}, {@code to}.
 * </ul>
 */
public final class MicrometerCommunicationMetrics implements CommunicationMetrics {

    /** Время попытки. */
    static final String DURATION = "tc.communication.attempt.duration";

    /** Число попыток. */
    static final String ATTEMPTS = "tc.communication.attempts";

    /** Состояние размыкателя. */
    static final String BREAKER_STATE = "tc.communication.breaker.state";

    /** Переходы размыкателя. */
    static final String BREAKER_TRANSITIONS = "tc.communication.breaker.transitions";

    private final MeterRegistry registry;

    /**
     * Собирает метрики.
     *
     * @param registry реестр потребителя
     */
    public MicrometerCommunicationMetrics(final MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void attempt(
            final String system,
            final String direction,
            final String phase,
            final String outcome,
            final boolean retry,
            final long nanos) {
        Timer.builder(DURATION)
                .description("Время попытки коммуникации на этом экземпляре: от захвата до закрытия")
                .tag("system", system)
                .tag("direction", direction)
                .tag("phase", phase)
                .tag("outcome", outcome)
                .register(this.registry)
                .record(nanos, TimeUnit.NANOSECONDS);
        Counter.builder(ATTEMPTS)
                .description("Попытки коммуникаций, закрытые этим экземпляром; retry=true — повторы")
                .tag("system", system)
                .tag("outcome", outcome)
                .tag("retry", Boolean.toString(retry))
                .register(this.registry)
                .increment();
    }

    @Override
    public void breaker(final String system, final IntSupplier state) {
        Gauge.builder(BREAKER_STATE, state, IntSupplier::getAsInt)
                .description("Состояние размыкателя системы на этом экземпляре: 0 — замкнут, 1 — проба, 2 — разомкнут")
                .tag("system", system)
                .strongReference(true)
                .register(this.registry);
    }

    @Override
    public void breakerTransition(final String system, final String to) {
        Counter.builder(BREAKER_TRANSITIONS)
                .description("Переходы размыкателя системы на этом экземпляре")
                .tag("system", system)
                .tag("to", to)
                .register(this.registry)
                .increment();
    }
}
