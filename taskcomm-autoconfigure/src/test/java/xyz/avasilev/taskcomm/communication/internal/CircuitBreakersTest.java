package xyz.avasilev.taskcomm.communication.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import xyz.avasilev.taskcomm.communication.api.CircuitBreakerSnapshot;
import xyz.avasilev.taskcomm.communication.api.Communication;
import xyz.avasilev.taskcomm.communication.api.CommunicationContext;
import xyz.avasilev.taskcomm.communication.api.KafkaCommunicationProcessor;

/**
 * Размыкатель на подставных часах (docs/adr/0065-communication-circuit-breaker.md): размыкание по доле
 * отказов при минимальном числе вызовов, ожидание до пробы, проба удаётся только целиком, срок пробы,
 * отложенная проба без воркеров; ограничение скорости — разрешения не чаще лимита.
 */
class CircuitBreakersTest {

    private static final Set<String> SYSTEMS = Set.of("stock");

    private final AtomicLong clock = new AtomicLong();

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private final CircuitBreakers breakers = new CircuitBreakers(
            CommunicationSettings.resolve(
                    CommunicationTestSettings.global(3, Duration.ZERO, Duration.ofSeconds(30)),
                    Map.of(),
                    new CommunicationProcessorRegistry(List.of(new StockProcessor()), true),
                    CommunicationTestSettings.POLLERS,
                    Timing.DEFAULT,
                    new BreakerSettings(
                            true,
                            Duration.ofSeconds(60),
                            4,
                            0.5,
                            new RetryPolicy(1, Duration.ofSeconds(10), 2, Duration.ofMinutes(10), 0.1),
                            2,
                            Duration.ofMinutes(1))),
            this.clock::get,
            () -> 0.5,
            new MicrometerCommunicationMetrics(this.meters));

    @Test
    void stateGaugeAndTransitionsCounter() {
        this.breakers.record("stock", true);
        assertThat(this.meters
                        .get("tc.communication.breaker.state")
                        .tag("system", "stock")
                        .gauge()
                        .value())
                .isEqualTo(0.0);
        this.breakers.record("stock", true);
        this.breakers.record("stock", true);
        this.breakers.record("stock", true);

        assertThat(this.meters
                        .get("tc.communication.breaker.state")
                        .tag("system", "stock")
                        .gauge()
                        .value())
                .isEqualTo(2.0);
        assertThat(this.meters
                        .get("tc.communication.breaker.transitions")
                        .tags("system", "stock", "to", "open")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    void opensOnlyWhenMinimumCallsReachedAndRateExceeded() {
        this.breakers.record("stock", true);
        this.breakers.record("stock", true);
        this.breakers.record("stock", false);
        assertThat(this.breakers.claimable("stock")).isTrue();

        this.breakers.record("stock", true);

        assertThat(this.breakers.claimable("stock")).isFalse();
        assertThat(this.breakers.dueProbes(SYSTEMS)).isEmpty();
    }

    @Test
    void snapshotShowsStateWindowAndNextProbe() {
        this.breakers.record("stock", true);
        this.breakers.record("stock", false);

        assertThat(this.breakers.snapshots("i-1"))
                .containsExactly(new CircuitBreakerSnapshot("i-1", "stock", "CLOSED", 2, 0.5, null));

        final Instant before = Instant.now();
        this.open();

        assertThat(this.breakers.snapshots("i-1")).singleElement().satisfies(snapshot -> {
            assertThat(snapshot.state()).isEqualTo("OPEN");
            assertThat(snapshot.calls()).isZero();
            assertThat(snapshot.nextProbeAt()).isAfter(before.plusSeconds(5));
        });
    }

    @Test
    void probeClosesOnlyWhenAllProbeCallsSucceed() {
        this.open();
        this.advance(Duration.ofSeconds(11));

        assertThat(this.breakers.dueProbes(SYSTEMS)).containsExactly("stock");
        this.breakers.probeClaimed("stock", 2);
        this.breakers.record("stock", false);
        assertThat(this.breakers.claimable("stock")).isFalse();
        this.breakers.record("stock", false);

        assertThat(this.breakers.claimable("stock")).isTrue();
    }

    @Test
    void failedProbeReopensWithLongerWait() {
        this.open();
        this.advance(Duration.ofSeconds(11));
        this.breakers.dueProbes(SYSTEMS);
        this.breakers.probeClaimed("stock", 2);

        this.breakers.record("stock", true);

        this.advance(Duration.ofSeconds(11));
        assertThat(this.breakers.dueProbes(SYSTEMS)).isEmpty();
        this.advance(Duration.ofSeconds(10));
        assertThat(this.breakers.dueProbes(SYSTEMS)).containsExactly("stock");
    }

    @Test
    void probeWithoutAllOutcomesInTimeReopens() {
        this.open();
        this.advance(Duration.ofSeconds(11));
        this.breakers.dueProbes(SYSTEMS);
        this.breakers.probeClaimed("stock", 2);
        this.breakers.record("stock", false);

        this.advance(Duration.ofMinutes(2));

        assertThat(this.breakers.claimable("stock")).isFalse();
    }

    @Test
    void postponedProbeIsRetriedNextTickAndEmptyProbeCloses() {
        this.open();
        this.advance(Duration.ofSeconds(11));
        this.breakers.dueProbes(SYSTEMS);
        this.breakers.postponeProbe("stock");

        assertThat(this.breakers.dueProbes(SYSTEMS)).containsExactly("stock");
        this.breakers.probeClaimed("stock", 0);
        assertThat(this.breakers.claimable("stock")).isTrue();
    }

    @Test
    void rateLimiterSpacesPermits() throws InterruptedException {
        final RateLimiters limiters = new RateLimiters(
                CommunicationSettings.resolve(
                        CommunicationTestSettings.global(3, Duration.ZERO, Duration.ofSeconds(30)),
                        Map.of(
                                "stock",
                                new SystemOverrides(
                                        new SettingsOverride(
                                                null,
                                                LeaseOverride.NONE,
                                                RetryOverride.NONE,
                                                null,
                                                null,
                                                10.0,
                                                BreakerOverride.NONE),
                                        Map.of())),
                        new CommunicationProcessorRegistry(List.of(new StockProcessor()), true),
                        CommunicationTestSettings.POLLERS,
                        Timing.DEFAULT),
                System::nanoTime);
        final long started = System.nanoTime();

        for (int i = 0; i < 4; i++) {
            assertThat(limiters.acquire("stock", () -> false)).isTrue();
        }

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(Duration.ofMillis(250));
        assertThat(limiters.acquire("other", () -> false)).isTrue();
    }

    private void open() {
        for (int i = 0; i < 4; i++) {
            this.breakers.record("stock", true);
        }
        assertThat(this.breakers.claimable("stock")).isFalse();
    }

    private void advance(final Duration duration) {
        this.clock.addAndGet(duration.toNanos());
    }

    /** Система склада. */
    static class StockProcessor implements KafkaCommunicationProcessor<String, Void, Void> {

        @Override
        public String externalSystem() {
            return "stock";
        }

        @Override
        public Void process(final Communication<String> communication, final CommunicationContext context) {
            return null;
        }
    }
}
