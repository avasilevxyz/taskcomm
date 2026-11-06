package xyz.avasilev.taskcomm.communication.internal.observation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xyz.avasilev.taskcomm.communication.internal.CommunicationTestDatabase;
import xyz.avasilev.taskcomm.communication.internal.CommunicationTestSettings;
import xyz.avasilev.taskcomm.communication.internal.Direction;
import xyz.avasilev.taskcomm.communication.internal.creation.CreatedCommunication;
import xyz.avasilev.taskcomm.communication.internal.creation.NewCommunication;

/**
 * Снимок метрик коммуникаций на всю схему (docs/adr/0102-schema-wide-metrics-snapshot.md): очередь
 * только из захватываемого, отложенные по причине — повтор отличается закрытой попыткой, возраст
 * и просрочка, незавершённые и осиротевшие; подсчёт под своей блокировкой, строки задач не трогаются.
 */
class CommunicationMetricsSnapshotIT {

    private static final String KNOWN = "stock";

    private CommunicationTestDatabase db;

    private Recording metrics;

    @BeforeEach
    void createSchema() {
        this.db = CommunicationTestDatabase.create();
        this.metrics = new Recording();
    }

    @Test
    void countsQueueDelayedAgeOverdueUnfinishedAndOrphans() {
        this.create(KNOWN, null);
        final CreatedCommunication late = this.create(KNOWN, null);
        this.db
                .jdbc
                .sql("UPDATE tc_communication SET next_attempt_at = now() - interval '10 minutes',"
                        + " claim_order_at = now() - interval '5 minutes' WHERE id = :id")
                .param("id", late.id())
                .update();
        this.create(KNOWN, Instant.now().plus(Duration.ofHours(1)));
        final CreatedCommunication retried = this.create(KNOWN, null);
        this.db
                .jdbc
                .sql("UPDATE tc_communication_attempt SET finished_at = now(), status = 'FAILED' WHERE id = :id")
                .param("id", retried.attemptId())
                .update();
        this.db
                .jdbc
                .sql("UPDATE tc_communication SET next_attempt_at = now() + interval '1 hour' WHERE id = :id")
                .param("id", retried.id())
                .update();
        this.create("gone", null);

        assertThat(this.snapshot().refresh()).isTrue();
        this.snapshot().publish();

        assertThat(this.value("QUEUE", KNOWN, "main", "")).isEqualTo(2.0);
        assertThat(this.value("DELAYED", KNOWN, "main", "SCHEDULED")).isEqualTo(1.0);
        assertThat(this.value("DELAYED", KNOWN, "main", "RETRY")).isEqualTo(1.0);
        assertThat(this.value("OLDEST_CLAIMABLE_AGE", KNOWN, "main", "")).isCloseTo(600.0, within(30.0));
        assertThat(this.value("OVERDUE", KNOWN, "main", "")).isCloseTo(300.0, within(30.0));
        assertThat(this.value("UNFINISHED", KNOWN, "", "")).isEqualTo(4.0);
        assertThat(this.value("ORPHANED", "gone", "", "")).isEqualTo(1.0);
        assertThat(this.metrics.age).isBetween(0.0, 30.0);
    }

    @Test
    void freshSnapshotIsNotRecountedAndTaskRowsAreLeftAlone() {
        this.db.jdbc.sql("""
                        INSERT INTO tc_metrics_snapshot (id, created_at, updated_at, subsystem, metric, type_or_system,
                                                         poller, reason, value, computed_at)
                        VALUES (:id, now(), now(), 'TASK', 'QUEUE', 'report', 'main', '', 3,
                                now() - interval '1 hour')
                        """).param("id", this.db.ids.next()).update();

        assertThat(this.snapshot().refresh()).isTrue();
        assertThat(this.snapshot().refresh()).isFalse();
        this.snapshot().publish();

        assertThat(this.db
                        .jdbc
                        .sql("SELECT count(*) FROM tc_metrics_snapshot WHERE subsystem = 'TASK'")
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
        assertThat(this.metrics.rows).allMatch(row -> row.system().equals(KNOWN));
    }

    @Test
    void lockHeldElsewhereSkipsCount() throws Exception {
        try (Connection other = this.db.dataSource.getConnection();
                Statement statement = other.createStatement()) {
            statement.execute("SELECT pg_advisory_lock(" + CommunicationMetricsSnapshot.LOCK + ", 0)");

            assertThat(this.snapshot().refresh()).isFalse();

            statement.execute("SELECT pg_advisory_unlock(" + CommunicationMetricsSnapshot.LOCK + ", 0)");
        }
        assertThat(this.snapshot().refresh()).isTrue();
    }

    private CommunicationMetricsSnapshot snapshot() {
        return new CommunicationMetricsSnapshot(
                this.db.dataSource,
                this.db.transactionManager,
                this.db.ids,
                CommunicationTestSettings.POLLERS,
                Set.of(KNOWN),
                Duration.ofSeconds(30),
                this.metrics);
    }

    private CreatedCommunication create(final String system, final Instant notBefore) {
        return this.db
                .creator()
                .create(new NewCommunication(
                        Direction.OUTBOUND,
                        system,
                        "{\"sku\":1}",
                        null,
                        new NewCommunication.Scheduling(100, Duration.ofMinutes(1), notBefore),
                        new NewCommunication.Placement(null, null),
                        NewCommunication.Start.OUTBOUND,
                        NewCommunication.Keys.NONE));
    }

    private double value(final String metric, final String system, final String poller, final String reason) {
        return this.metrics.rows.stream()
                .filter(row -> row.metric().equals(metric)
                        && row.system().equals(system)
                        && row.poller().equals(poller)
                        && row.reason().equals(reason))
                .findFirst()
                .orElseThrow(() -> new AssertionError("нет строки " + metric + " " + system + " " + reason))
                .value();
    }

    /** Запоминает последний прочитанный снимок. */
    private static final class Recording implements CommunicationMetrics {

        private List<SnapshotRow> rows = List.of();

        private double age;

        @Override
        public void attempt(
                final String system,
                final String direction,
                final String phase,
                final String outcome,
                final boolean retry,
                final long nanos) {
            // попытки здесь не нужны
        }

        @Override
        public void breaker(final String system, final IntSupplier state) {
            // размыкатели здесь не нужны
        }

        @Override
        public void breakerTransition(final String system, final String to) {
            // размыкатели здесь не нужны
        }

        @Override
        public void snapshot(final List<SnapshotRow> rows, final double ageSeconds) {
            this.rows = List.copyOf(rows);
            this.age = ageSeconds;
        }
    }
}
