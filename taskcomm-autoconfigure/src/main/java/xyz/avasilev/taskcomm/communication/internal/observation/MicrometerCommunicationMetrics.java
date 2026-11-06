package xyz.avasilev.taskcomm.communication.internal.observation;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;

/**
 * Метрики коммуникаций в Micrometer (docs/rules/observability.md). Создаётся только при Micrometer
 * на classpath и бине {@link MeterRegistry} у потребителя.
 *
 * <p>Метрики экземпляра — их сумма по экземплярам верна:
 *
 * <ul>
 *   <li>{@code tc.communication.attempt.duration} — время попытки: {@code system}, {@code direction},
 *       {@code phase}, {@code outcome};
 *   <li>{@code tc.communication.attempts} — число попыток: {@code system}, {@code outcome}, {@code retry};
 *   <li>{@code tc.communication.breaker.state} — состояние размыкателя на экземпляре: {@code system};
 *       0 — замкнут, 1 — проба, 2 — разомкнут;
 *   <li>{@code tc.communication.breaker.transitions} — переходы: {@code system}, {@code to}.
 * </ul>
 *
 * <p>Метрики из снимка на всю схему (docs/adr/0102-schema-wide-metrics-snapshot.md) — каждый
 * экземпляр публикует одно и то же, агрегировать их {@code max()}:
 *
 * <ul>
 *   <li>{@code tc.communication.queue}, {@code tc.communication.oldest.claimable.age},
 *       {@code tc.communication.overdue} — {@code system}, {@code poller};
 *   <li>{@code tc.communication.delayed} — {@code system}, {@code poller}, {@code reason};
 *   <li>{@code tc.communication.unfinished}, {@code tc.communication.orphaned} — {@code system};
 *   <li>{@code tc.metrics.snapshot.age} с {@code subsystem=communication} — возраст снимка на этом
 *       экземпляре.
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

    /** Возраст снимка — общее имя подсистем, различаются меткой {@code subsystem}. */
    static final String SNAPSHOT_AGE = "tc.metrics.snapshot.age";

    private static final String MAX_NOTE = " Значение на всю схему: по экземплярам агрегировать max(), не sum().";

    private final MeterRegistry registry;

    /** Метрики снимка по имени метрики в {@code tc_metrics_snapshot.metric}. */
    private final Map<String, MultiGauge> snapshot = new LinkedHashMap<>();

    private volatile double ageAtRead = Double.NaN;

    private volatile long readNanos = System.nanoTime();

    /**
     * Собирает метрики и регистрирует метрики снимка; до первого чтения снимка они пусты.
     *
     * @param registry реестр потребителя
     */
    public MicrometerCommunicationMetrics(final MeterRegistry registry) {
        this.registry = registry;
        this.snapshot.put(
                "QUEUE",
                this.gauge(
                        "tc.communication.queue", null, "Коммуникации, которые свободный воркер может взять сейчас"));
        this.snapshot.put(
                "DELAYED",
                this.gauge(
                        "tc.communication.delayed",
                        null,
                        "Коммуникации, ждущие своего времени: reason=scheduled — отложенный запуск, retry — повтор"));
        this.snapshot.put(
                "OLDEST_CLAIMABLE_AGE",
                this.gauge(
                        "tc.communication.oldest.claimable.age",
                        "seconds",
                        "Возраст самой старой захватываемой коммуникации"));
        this.snapshot.put(
                "OVERDUE",
                this.gauge(
                        "tc.communication.overdue",
                        "seconds",
                        "Наибольшая просрочка claim_order_at у поллеров с порядком deadline: опоздание сверх форы"));
        this.snapshot.put(
                "UNFINISHED",
                this.gauge("tc.communication.unfinished", null, "Незавершённые коммуникации по внешней системе"));
        this.snapshot.put(
                "ORPHANED",
                this.gauge(
                        "tc.communication.orphaned",
                        null,
                        "Незавершённые коммуникации систем без процессора — их никто не захватит"));
        Gauge.builder(SNAPSHOT_AGE, this, MicrometerCommunicationMetrics::snapshotAge)
                .description("Возраст снимка метрик на всю схему, прочитанного этим экземпляром. Растёт — снимок "
                        + "никто не обновляет или экземпляр не может его прочитать")
                .baseUnit("seconds")
                .tag("subsystem", "communication")
                .strongReference(true)
                .register(registry);
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

    @Override
    public void snapshot(final List<SnapshotRow> rows, final double ageSeconds) {
        final Map<String, List<MultiGauge.Row<?>>> byMetric = new LinkedHashMap<>();
        this.snapshot.keySet().forEach(metric -> byMetric.put(metric, new ArrayList<>()));
        for (final SnapshotRow row : rows) {
            final List<MultiGauge.Row<?>> target = byMetric.get(row.metric());
            if (target != null) {
                target.add(MultiGauge.Row.of(tags(row), row.value()));
            }
        }
        byMetric.forEach((metric, gaugeRows) -> this.snapshot.get(metric).register(gaugeRows, true));
        this.readNanos = System.nanoTime();
        this.ageAtRead = ageSeconds;
    }

    /**
     * Возраст снимка сейчас: возраст при чтении плюс время с чтения. Не прочитанный ни разу снимок —
     * {@link Double#NaN}.
     */
    private double snapshotAge() {
        return this.ageAtRead + (System.nanoTime() - this.readNanos) / 1e9;
    }

    private MultiGauge gauge(final String name, final String unit, final String description) {
        final MultiGauge.Builder builder = MultiGauge.builder(name).description(description + "." + MAX_NOTE);
        if (unit != null) {
            builder.baseUnit(unit);
        }
        return builder.register(this.registry);
    }

    private static Tags tags(final SnapshotRow row) {
        Tags tags = Tags.of("system", row.system());
        if (!row.poller().isEmpty()) {
            tags = tags.and("poller", row.poller());
        }
        if (!row.reason().isEmpty()) {
            tags = tags.and("reason", row.reason().toLowerCase(Locale.ROOT));
        }
        return tags;
    }
}
