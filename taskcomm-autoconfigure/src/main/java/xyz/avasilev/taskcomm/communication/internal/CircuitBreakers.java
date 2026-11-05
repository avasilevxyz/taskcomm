package xyz.avasilev.taskcomm.communication.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;
import lombok.extern.slf4j.Slf4j;
import xyz.avasilev.taskcomm.communication.api.CircuitBreakerSnapshot;

/**
 * Размыкатели исходящих по системам — <b>в памяти экземпляра</b> (docs/adr/0065-communication-circuit-breaker.md).
 *
 * <ul>
 *   <li><b>Замкнут</b>: исходы отправок за окно; доля отказов не меньше порога при минимальном числе
 *       вызовов — размыкается.
 *   <li><b>Разомкнут</b>: система не захватывается. Ожидание до пробы растёт по правилам backoff
 *       с джиттером.
 *   <li><b>Проба</b>: отдельный захват не больше {@code probe.calls} строк системы; удалась, только если
 *       все взятые вызовы успешны — тогда замыкается. Любой отказ или истёкший срок пробы — снова
 *       разомкнут, следующее ожидание длиннее. Взять оказалось нечего — замыкается: отправлять нечего.
 * </ul>
 *
 * <p>Не считаются вовсе исключение после {@code doNotRetry}, исключение вне списка и потеря аренды.
 *
 * <p>Каждый переход — WARN с системой, причиной и долей отказов и счётчик переходов; состояние видно метрикой
 * (docs/rules/observability.md). Проба, отложенная за отсутствием свободных воркеров, переходом не считается:
 * размыкатель остаётся разомкнутым.
 */
@Slf4j
class CircuitBreakers {

    private final CommunicationSettings settings;

    private final LongSupplier nanoClock;

    private final DoubleSupplier random;

    private final CommunicationMetrics metrics;

    private final Map<String, Breaker> breakers = new ConcurrentHashMap<>();

    CircuitBreakers(
            final CommunicationSettings settings,
            final LongSupplier nanoClock,
            final DoubleSupplier random,
            final CommunicationMetrics metrics) {
        this.settings = settings;
        this.nanoClock = nanoClock;
        this.random = random;
        this.metrics = metrics;
    }

    /**
     * Можно ли захватывать систему основным захватом: замкнут или размыкатель выключен.
     *
     * @param system внешняя система
     * @return {@code true}, если можно
     */
    boolean claimable(final String system) {
        final Breaker breaker = this.breakers.get(system);
        return breaker == null || breaker.claimable(this.nanoClock.getAsLong());
    }

    /**
     * Системы, чья проба наступила: размыкатель переводится в пробу.
     *
     * @param systems системы поллера
     * @return системы, которым нужен пробный захват
     */
    List<String> dueProbes(final Set<String> systems) {
        final List<String> due = new ArrayList<>();
        final long now = this.nanoClock.getAsLong();
        for (final String system : systems) {
            final Breaker breaker = this.breakers.get(system);
            if (breaker != null && breaker.startProbe(now)) {
                due.add(system);
            }
        }
        return due;
    }

    /**
     * Пробу не на чем выполнить — свободных воркеров нет: остаётся разомкнутым, проба — в следующем
     * такте, без удлинения ожидания.
     *
     * @param system внешняя система
     */
    void postponeProbe(final String system) {
        final Breaker breaker = this.breakers.get(system);
        if (breaker != null) {
            breaker.postpone();
        }
    }

    /**
     * Пробный захват выполнен.
     *
     * @param system внешняя система
     * @param claimed сколько строк взято
     */
    void probeClaimed(final String system, final int claimed) {
        final Breaker breaker = this.breakers.get(system);
        if (breaker != null) {
            breaker.probeClaimed(claimed);
        }
    }

    /**
     * Исход отправки.
     *
     * @param system внешняя система
     * @param failure отказ ли это
     */
    void record(final String system, final boolean failure) {
        final BreakerPolicy policy = this.settings.breaker(system);
        if (policy.enabled()) {
            this.breakers
                    .computeIfAbsent(system, key -> this.create(key, policy.settings()))
                    .record(failure, this.nanoClock.getAsLong());
        }
    }

    /**
     * Снимок размыкателей этого экземпляра — только систем, у которых размыкатель уже заведён.
     *
     * @param instance имя экземпляра
     * @return снимки по системам
     */
    List<CircuitBreakerSnapshot> snapshots(final String instance) {
        final long now = this.nanoClock.getAsLong();
        final Instant wall = Instant.now();
        return this.breakers.values().stream()
                .map(breaker -> breaker.snapshot(instance, now, wall))
                .sorted(Comparator.comparing(CircuitBreakerSnapshot::externalSystem))
                .toList();
    }

    private Breaker create(final String system, final BreakerSettings breakerSettings) {
        final Breaker breaker = new Breaker(system, breakerSettings);
        this.metrics.breaker(system, breaker::stateCode);
        return breaker;
    }

    /** Состояние размыкателя одной системы. */
    private final class Breaker {

        private final String system;

        private final BreakerSettings settings;

        private final Deque<long[]> window = new ArrayDeque<>();

        private State state = State.CLOSED;

        private int openings;

        private long nextProbeAt;

        private long probeDeadline;

        private int probeRemaining;

        Breaker(final String system, final BreakerSettings settings) {
            this.system = system;
            this.settings = settings;
        }

        synchronized int stateCode() {
            return switch (this.state) {
                case CLOSED -> 0;
                case PROBING -> 1;
                case OPEN -> 2;
            };
        }

        synchronized boolean claimable(final long now) {
            this.expireProbe(now);
            return this.state == State.CLOSED;
        }

        synchronized CircuitBreakerSnapshot snapshot(final String instance, final long now, final Instant wall) {
            this.expireProbe(now);
            final long failures =
                    this.window.stream().filter(entry -> entry[1] == 1).count();
            return new CircuitBreakerSnapshot(
                    instance,
                    this.system,
                    this.state.name(),
                    this.window.size(),
                    this.window.isEmpty() ? 0 : (double) failures / this.window.size(),
                    this.state == State.OPEN ? wall.plusNanos(Math.max(0, this.nextProbeAt - now)) : null);
        }

        synchronized boolean startProbe(final long now) {
            this.expireProbe(now);
            if (this.state != State.OPEN || now < this.nextProbeAt) {
                return false;
            }
            this.state = State.PROBING;
            this.probeDeadline = now + this.settings.probeTimeout().toNanos();
            this.probeRemaining = this.settings.probeCalls();
            return true;
        }

        synchronized void postpone() {
            if (this.state == State.PROBING) {
                this.state = State.OPEN;
            }
        }

        synchronized void probeClaimed(final int claimed) {
            if (this.state == State.PROBING) {
                this.probeRemaining = claimed;
                if (claimed == 0) {
                    this.close("пробовать нечего — отправок нет");
                } else {
                    this.transition("probing");
                    log.warn(
                            "Размыкатель системы '{}': проба на {} отправках; доля отказов {}.",
                            this.system,
                            claimed,
                            this.rate());
                }
            }
        }

        synchronized void record(final boolean failure, final long now) {
            switch (this.state) {
                case CLOSED -> this.count(failure, now);
                case PROBING -> {
                    if (failure) {
                        this.open(now, "отказ в пробе");
                    } else {
                        this.probeRemaining--;
                        if (this.probeRemaining <= 0) {
                            this.close("проба удалась");
                        }
                    }
                }
                case OPEN -> {
                    // исход отправки, начатой до размыкания, — не в счёт
                }
            }
        }

        private void count(final boolean failure, final long now) {
            this.window.addLast(new long[] {now, failure ? 1 : 0});
            final long horizon = now - this.settings.window().toNanos();
            while (!this.window.isEmpty() && this.window.peekFirst()[0] < horizon) {
                this.window.removeFirst();
            }
            final long failures =
                    this.window.stream().filter(entry -> entry[1] == 1).count();
            final double rate = (double) failures / this.window.size();
            if (this.window.size() >= this.settings.minimumCalls() && rate >= this.settings.failureRateThreshold()) {
                this.open(now, "доля отказов за окно не меньше порога");
            }
        }

        private void expireProbe(final long now) {
            if (this.state == State.PROBING && now > this.probeDeadline) {
                this.open(now, "срок пробы истёк");
            }
        }

        private void open(final long now, final String reason) {
            final String rate = this.rate();
            this.openings++;
            final Duration wait = this.settings.backoff().delay(this.openings, CircuitBreakers.this.random);
            this.state = State.OPEN;
            this.nextProbeAt = now + wait.toNanos();
            this.window.clear();
            this.transition("open");
            log.warn(
                    "Размыкатель системы '{}' разомкнут: {}; доля отказов {}; проба через {} "
                            + "(docs/adr/0065-communication-circuit-breaker.md).",
                    this.system,
                    reason,
                    rate,
                    wait);
        }

        private void close(final String reason) {
            final String rate = this.rate();
            this.state = State.CLOSED;
            this.openings = 0;
            this.window.clear();
            this.transition("closed");
            log.warn("Размыкатель системы '{}' замкнут: {}; доля отказов {}.", this.system, reason, rate);
        }

        private void transition(final String to) {
            CircuitBreakers.this.metrics.breakerTransition(this.system, to);
        }

        /** Доля отказов в текущем окне; окно пусто — «нет вызовов». */
        private String rate() {
            final long failures =
                    this.window.stream().filter(entry -> entry[1] == 1).count();
            return this.window.isEmpty()
                    ? "— (вызовов в окне нет)"
                    : String.format(
                            Locale.ROOT,
                            "%.2f из %d вызовов",
                            (double) failures / this.window.size(),
                            this.window.size());
        }
    }

    /** Состояние размыкателя. */
    private enum State {
        CLOSED,
        OPEN,
        PROBING
    }
}
