package xyz.avasilev.taskcomm.communication.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import xyz.avasilev.taskcomm.communication.api.Communication;
import xyz.avasilev.taskcomm.communication.api.CommunicationContext;
import xyz.avasilev.taskcomm.communication.api.KafkaCommunicationProcessor;

/**
 * Нагрузочный сценарий захвата коммуникаций (docs/adr/0081-claim-scan-filters-load-scenario.md,
 * docs/adr/0137-rate-limit-and-circuit-breaker.md). Запускается задачей {@code loadTest}, вне {@code check}.
 *
 * <p>Два прогона на свежих схемах: базовый — только очередь живой системы; с помехами — та же очередь
 * плюс разомкнутая система с накопленным отставанием, миллион коммуникаций осиротевшей системы, очередь
 * отставшего выделенного поллера, отложенные строки живой системы по окну {@code priority × K}
 * и партиции за три года. Все мешающие системы основной поллер не берёт — разомкнутую исключает
 * размыкатель, выделенную — раскладка, осиротевшую — реестр. Критерий, зафиксированный до первого
 * прогона, — как у задач (docs/adr/0128-claim-load-scenario.md): с помехами не хуже 2× базового по
 * медиане и p95 такта и по времени опустошения очереди.
 *
 * <p>Захваченное после каждого такта закрывается настоящим закрытием попытки, вне замера такта
 * (docs/adr/0130-load-scenario-closes-claimed-work.md). База — на пуле соединений
 * (docs/adr/0132-load-scenario-on-pool.md).
 */
class CommunicationClaimLoadScenario {

    private static final String LIVE = "live";

    private static final int LIVE_COMMUNICATIONS = 20_000;

    private static final int OPEN_BREAKER = 200_000;

    private static final int ORPHANS = 1_000_000;

    private static final int LAGGING = 200_000;

    private static final int DEFERRED = 200_000;

    private static final int MONTHS_BACK = 36;

    private static final int TICK = 100;

    private static final double MAX_DEGRADATION = 2.0;

    private static final Map<String, ContractShape> SYSTEMS = Map.of(LIVE, ContractShape.KAFKA);

    /** Готовые исходящие с открытой незахваченной попыткой — как их создаёт стартер. */
    private static final String READY = """
            WITH r AS (SELECT gen_random_uuid() AS id,
                              now() - make_interval(secs => :ageSeconds) + make_interval(secs => g * 0.001) AS at
                         FROM generate_series(1, :count) g),
                 c AS (INSERT INTO tc_communication (
                           id, created_at, updated_at, execution_id, execution_created_at, direction, destination,
                           request_payload, phase, status, priority, next_attempt_at, claim_order_at,
                           retry_cycle_started_at)
                       SELECT r.id, now(), now(), gen_random_uuid(), :executionCreatedAt, 'OUTBOUND', :system,
                              '{}'::jsonb, 'PROCESS', 'PENDING', 100, r.at, r.at + interval '100 minutes', now()
                         FROM r)
            INSERT INTO tc_communication_attempt (
                id, created_at, updated_at, execution_created_at, communication_id, phase, lease_renewals)
            SELECT gen_random_uuid(), now(), now(), :executionCreatedAt, r.id, 'PROCESS', 0 FROM r
            """;

    /** Отложенные строки живой системы: готовность через час-три, приоритет от 1 до 200 — на строку. */
    private static final String DEFERRED_ROWS = """
            WITH r AS (SELECT gen_random_uuid() AS id,
                              now() + interval '1 hour' + random() * interval '2 hours' AS at,
                              1 + floor(random() * 200)::int AS priority
                         FROM generate_series(1, :count)),
                 c AS (INSERT INTO tc_communication (
                           id, created_at, updated_at, execution_id, execution_created_at, direction, destination,
                           request_payload, phase, status, priority, next_attempt_at, claim_order_at,
                           retry_cycle_started_at)
                       SELECT r.id, now(), now(), gen_random_uuid(), :executionCreatedAt, 'OUTBOUND', :system,
                              '{}'::jsonb, 'PROCESS', 'PENDING', r.priority, r.at,
                              r.at + make_interval(secs => r.priority * 60), now()
                         FROM r)
            INSERT INTO tc_communication_attempt (
                id, created_at, updated_at, execution_created_at, communication_id, phase, lease_renewals)
            SELECT gen_random_uuid(), now(), now(), :executionCreatedAt, r.id, 'PROCESS', 0 FROM r
            """;

    @Test
    void claimOfLiveSystemDegradesAtMostTwiceUnderInterference() {
        final Run baseline = run(false);
        report("базовый", baseline);
        final Run interfered = run(true);
        report("с помехами", interfered);
        System.out.printf(
                Locale.ROOT,
                "[loadTest] отношение: медиана %.2f×, p95 %.2f×, опустошение %.2f× (порог %.1f×)%n",
                interfered.medianMillis() / baseline.medianMillis(),
                interfered.p95Millis() / baseline.p95Millis(),
                interfered.drainMillis() / baseline.drainMillis(),
                MAX_DEGRADATION);

        assertThat(baseline.ticks()).hasSize(LIVE_COMMUNICATIONS / TICK);
        assertThat(interfered.ticks()).hasSize(LIVE_COMMUNICATIONS / TICK);
        assertThat(interfered.medianMillis()).isLessThanOrEqualTo(baseline.medianMillis() * MAX_DEGRADATION);
        assertThat(interfered.p95Millis()).isLessThanOrEqualTo(baseline.p95Millis() * MAX_DEGRADATION);
        assertThat(interfered.drainMillis()).isLessThanOrEqualTo(baseline.drainMillis() * MAX_DEGRADATION);
    }

    private static Run run(final boolean interference) {
        final long setup = System.nanoTime();
        final CommunicationTestDatabase db = CommunicationTestDatabase.createPooled();
        if (interference) {
            final Instant current = db.currentMonth();
            for (int month = MONTHS_BACK; month >= 1; month--) {
                db.createMonth(
                        current.atOffset(ZoneOffset.UTC).minusMonths(month).toInstant());
            }
        }
        final Instant partition = db.now();
        insert(db.jdbc, READY, partition, LIVE, LIVE_COMMUNICATIONS, 3600);
        if (interference) {
            insert(db.jdbc, READY, partition, "broken", OPEN_BREAKER, 7200);
            insert(db.jdbc, READY, partition, "gone", ORPHANS, 10_800);
            insert(db.jdbc, READY, partition, "late", LAGGING, 7200);
            insert(db.jdbc, DEFERRED_ROWS, partition, LIVE, DEFERRED, 0);
        }
        db.jdbc.sql("ANALYZE").update();
        db.advanceHorizon();
        System.out.printf(
                Locale.ROOT,
                "[loadTest] %s, подготовка: %.1f с%n",
                interference ? "с помехами" : "базовый",
                (System.nanoTime() - setup) / 1e9);
        return drain(db);
    }

    private static Run drain(final CommunicationTestDatabase db) {
        final CommunicationSettings settings = CommunicationSettings.resolve(
                CommunicationTestSettings.global(3, Duration.ZERO, Duration.ofMinutes(30)),
                Map.of(),
                new CommunicationProcessorRegistry(List.of(new LiveProcessor()), true),
                CommunicationTestSettings.POLLERS,
                Timing.DEFAULT);
        final CommunicationClaimer claimer = db.claimer(settings);
        final CommunicationAttemptCloser closer = new CommunicationAttemptCloser(
                db.dataSource, db.transactionManager, new CommunicationRetries(settings, () -> 0.5, db.ids));
        final List<Long> ticks = new ArrayList<>();
        List<ClaimedCommunication> claimed;
        long closing = 0;
        final long started = System.nanoTime();
        do {
            final long tick = System.nanoTime();
            claimed = claimer.claim(CommunicationTestSettings.MAIN, SYSTEMS, TICK);
            if (!claimed.isEmpty()) {
                ticks.add(System.nanoTime() - tick);
            }
            final long closed = System.nanoTime();
            claimed.forEach(attempt -> closer.succeed(attempt, null, true));
            closing += System.nanoTime() - closed;
        } while (!claimed.isEmpty());
        return new Run(ticks, System.nanoTime() - started - closing);
    }

    private static void insert(
            final JdbcClient jdbc,
            final String sql,
            final Instant partition,
            final String system,
            final int count,
            final int ageSeconds) {
        final JdbcClient.StatementSpec statement = jdbc.sql(sql)
                .param("executionCreatedAt", CommunicationSql.parameter(partition))
                .param("system", system)
                .param("count", count);
        if (sql.contains(":ageSeconds")) {
            statement.param("ageSeconds", ageSeconds);
        }
        statement.update();
    }

    private static long percentile(final List<Long> values, final double share) {
        final List<Long> sorted = values.stream().sorted().toList();
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.floor(share * sorted.size())));
    }

    private static void report(final String name, final Run run) {
        System.out.printf(
                Locale.ROOT,
                "[loadTest] коммуникации, %s: тактов %d, медиана %.2f мс, p95 %.2f мс, опустошение %.0f мс%n",
                name,
                run.ticks().size(),
                run.medianMillis(),
                run.p95Millis(),
                run.drainMillis());
    }

    /**
     * Итог прогона.
     *
     * @param ticks длительности тактов, в которых что-то захвачено, в наносекундах
     * @param drainNanos время опустошения очереди без времени закрытия захваченного
     */
    private record Run(List<Long> ticks, long drainNanos) {

        double medianMillis() {
            return percentile(this.ticks, 0.5) / 1e6;
        }

        double p95Millis() {
            return percentile(this.ticks, 0.95) / 1e6;
        }

        double drainMillis() {
            return this.drainNanos / 1e6;
        }
    }

    /** Живая система. */
    static class LiveProcessor implements KafkaCommunicationProcessor<String, Void, Void> {

        @Override
        public String externalSystem() {
            return LIVE;
        }

        @Override
        public Void process(final Communication<String> communication, final CommunicationContext context) {
            return null;
        }
    }
}
