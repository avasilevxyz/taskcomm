package xyz.avasilev.taskcomm.task.internal;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;

/**
 * Метрики попыток задач в Micrometer (docs/rules/observability.md). Создаётся только при Micrometer
 * на classpath и бине {@link MeterRegistry} у потребителя.
 *
 * <ul>
 *   <li>{@code tc.task.attempt.duration} — время попытки: {@code type}, {@code level}, {@code phase},
 *       {@code outcome};
 *   <li>{@code tc.task.attempts} — число попыток: {@code type}, {@code outcome}, {@code retry}. Доля
 *       ошибок и число повторов считаются из него.
 * </ul>
 */
public final class MicrometerTaskMetrics implements TaskMetrics {

    /** Время попытки. */
    static final String DURATION = "tc.task.attempt.duration";

    /** Число попыток. */
    static final String ATTEMPTS = "tc.task.attempts";

    private final MeterRegistry registry;

    /**
     * Собирает метрики.
     *
     * @param registry реестр потребителя
     */
    public MicrometerTaskMetrics(final MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void attempt(
            final String type,
            final String level,
            final String phase,
            final String outcome,
            final boolean retry,
            final long nanos) {
        Timer.builder(DURATION)
                .description("Время попытки задачи на этом экземпляре: от захвата до закрытия")
                .tag("type", type)
                .tag("level", level)
                .tag("phase", phase)
                .tag("outcome", outcome)
                .register(this.registry)
                .record(nanos, TimeUnit.NANOSECONDS);
        Counter.builder(ATTEMPTS)
                .description("Попытки задач, закрытые этим экземпляром; retry=true — повторы")
                .tag("type", type)
                .tag("outcome", outcome)
                .tag("retry", Boolean.toString(retry))
                .register(this.registry)
                .increment();
    }
}
