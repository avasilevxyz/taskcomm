package xyz.avasilev.taskcomm.task.internal.observation;

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

/**
 * Метрики задач в Micrometer (docs/rules/observability.md). Создаётся только при Micrometer
 * на classpath и бине {@link MeterRegistry} у потребителя.
 *
 * <p>Метрики экземпляра — их сумма по экземплярам верна:
 *
 * <ul>
 *   <li>{@code tc.task.attempt.duration} — время попытки: {@code type}, {@code level}, {@code phase},
 *       {@code outcome};
 *   <li>{@code tc.task.attempts} — число попыток: {@code type}, {@code outcome}, {@code retry}. Доля
 *       ошибок и число повторов считаются из него.
 * </ul>
 *
 * <p>Метрики из снимка на всю схему (docs/adr/0102-schema-wide-metrics-snapshot.md) — каждый
 * экземпляр публикует одно и то же, агрегировать их {@code max()}:
 *
 * <ul>
 *   <li>{@code tc.task.queue}, {@code tc.task.oldest.claimable.age}, {@code tc.task.overdue} —
 *       {@code type}, {@code poller};
 *   <li>{@code tc.task.delayed} — {@code type}, {@code poller}, {@code reason};
 *   <li>{@code tc.task.unfinished}, {@code tc.task.orphaned} — {@code type};
 *   <li>{@code tc.metrics.snapshot.age} с {@code subsystem=task} — возраст снимка на этом экземпляре.
 * </ul>
 */
public final class MicrometerTaskMetrics implements TaskMetrics {

    /** Время попытки. */
    static final String DURATION = "tc.task.attempt.duration";

    /** Число попыток. */
    static final String ATTEMPTS = "tc.task.attempts";

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
    public MicrometerTaskMetrics(final MeterRegistry registry) {
        this.registry = registry;
        this.snapshot.put(
                "QUEUE", this.gauge("tc.task.queue", null, "Задачи, которые свободный воркер может взять сейчас"));
        this.snapshot.put(
                "DELAYED",
                this.gauge(
                        "tc.task.delayed",
                        null,
                        "Задачи, ждущие своего времени: reason=scheduled — отложенный запуск, retry — повтор"));
        this.snapshot.put(
                "OLDEST_CLAIMABLE_AGE",
                this.gauge("tc.task.oldest.claimable.age", "seconds", "Возраст самой старой захватываемой задачи"));
        this.snapshot.put(
                "OVERDUE",
                this.gauge(
                        "tc.task.overdue",
                        "seconds",
                        "Наибольшая просрочка claim_order_at у поллеров с порядком deadline: опоздание сверх форы"));
        this.snapshot.put(
                "UNFINISHED",
                this.gauge("tc.task.unfinished", null, "Незавершённые задачи, группы и подзадачи по типу"));
        this.snapshot.put(
                "ORPHANED",
                this.gauge(
                        "tc.task.orphaned", null, "Незавершённые задачи типов без процессора — их никто не захватит"));
        Gauge.builder(SNAPSHOT_AGE, this, MicrometerTaskMetrics::snapshotAge)
                .description("Возраст снимка метрик на всю схему, прочитанного этим экземпляром. Растёт — снимок "
                        + "никто не обновляет или экземпляр не может его прочитать")
                .baseUnit("seconds")
                .tag("subsystem", "task")
                .strongReference(true)
                .register(registry);
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
        Tags tags = Tags.of("type", row.type());
        if (!row.poller().isEmpty()) {
            tags = tags.and("poller", row.poller());
        }
        if (!row.reason().isEmpty()) {
            tags = tags.and("reason", row.reason().toLowerCase(Locale.ROOT));
        }
        return tags;
    }
}
