package xyz.avasilev.taskcomm.task.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Снимок метрик задач на всю схему (docs/adr/0102-schema-wide-metrics-snapshot.md): очередь только
 * из захватываемого, отложенные по причине, возраст и просрочка, незавершённые и осиротевшие; один
 * подсчёт за такт под блокировкой подсистемы, удаление строк, которых в такте не было.
 */
class TaskMetricsSnapshotIT {

    private static final String KNOWN = TaskTestDatabase.FLAT_TYPE;

    private TaskTestDatabase db;

    private Recording metrics;

    @BeforeEach
    void createSchema() {
        this.db = TaskTestDatabase.create();
        this.metrics = new Recording();
    }

    @Test
    void countsQueueDelayedAgeOverdueUnfinishedAndOrphans() {
        this.db.submit(KNOWN, 100);
        final CreatedTask overdue = this.db.submit(KNOWN, 100);
        this.db
                .jdbc
                .sql("UPDATE tc_task SET next_attempt_at = now() - interval '10 minutes',"
                        + " claim_order_at = now() - interval '5 minutes' WHERE id = :id")
                .param("id", overdue.id())
                .update();
        this.db
                .creator()
                .create(new NewTask(
                        KNOWN,
                        "{\"n\":1}",
                        100,
                        Duration.ofMinutes(1),
                        Instant.now().plus(Duration.ofHours(1)),
                        null,
                        null));
        this.retryLater(this.db.submit(KNOWN, 100));
        this.db.submit("forgotten", 100);

        assertThat(this.snapshot().refresh()).isTrue();
        this.snapshot().publish();

        assertThat(this.value("QUEUE", KNOWN, "main", "")).isEqualTo(2.0);
        assertThat(this.value("DELAYED", KNOWN, "main", "SCHEDULED")).isEqualTo(1.0);
        assertThat(this.value("DELAYED", KNOWN, "main", "RETRY")).isEqualTo(1.0);
        assertThat(this.value("OLDEST_CLAIMABLE_AGE", KNOWN, "main", "")).isCloseTo(600.0, within(30.0));
        assertThat(this.value("OVERDUE", KNOWN, "main", "")).isCloseTo(300.0, within(30.0));
        assertThat(this.value("UNFINISHED", KNOWN, "", "")).isEqualTo(4.0);
        assertThat(this.value("UNFINISHED", "forgotten", "", "")).isEqualTo(1.0);
        assertThat(this.value("ORPHANED", "forgotten", "", "")).isEqualTo(1.0);
        assertThat(this.metrics.rows)
                .noneMatch(row -> row.type().equals("forgotten") && row.metric().equals("QUEUE"));
        assertThat(this.metrics.age).isBetween(0.0, 30.0);
    }

    @Test
    void knownTypeGetsZeroRowsAndPriorityFormHasNoOverdue() {
        final TaskMetricsSnapshot snapshot = new TaskMetricsSnapshot(
                this.db.dataSource,
                this.db.transactionManager,
                this.db.ids,
                new PollerLayout(TaskTestSettings.mainPoller(ClaimForm.PRIORITY), List.of()),
                Set.of(KNOWN),
                Duration.ofSeconds(30),
                this.metrics);

        assertThat(snapshot.refresh()).isTrue();
        snapshot.publish();

        assertThat(this.metrics.rows)
                .extracting(TaskMetrics.SnapshotRow::metric)
                .containsExactlyInAnyOrder("QUEUE", "DELAYED", "DELAYED", "OLDEST_CLAIMABLE_AGE", "UNFINISHED");
        assertThat(this.metrics.rows).allMatch(row -> row.value() == 0.0);
    }

    @Test
    void freshSnapshotIsNotRecountedAndStaleRowsAreDeleted() {
        this.db.jdbc.sql("""
                        INSERT INTO tc_metrics_snapshot (id, created_at, updated_at, subsystem, metric, type_or_system,
                                                         poller, reason, value, computed_at)
                        VALUES (:id, now(), now(), 'TASK', 'ORPHANED', 'gone', '', '', 7,
                                now() - interval '1 hour')
                        """).param("id", this.db.ids.next()).update();

        assertThat(this.snapshot().refresh()).isTrue();
        assertThat(this.snapshot().refresh()).isFalse();

        assertThat(this.db
                        .jdbc
                        .sql("SELECT count(*) FROM tc_metrics_snapshot WHERE type_or_system = 'gone'")
                        .query(Integer.class)
                        .single())
                .isZero();
    }

    @Test
    void communicationRowsAreLeftAlone() {
        this.db.jdbc.sql("""
                        INSERT INTO tc_metrics_snapshot (id, created_at, updated_at, subsystem, metric, type_or_system,
                                                         poller, reason, value, computed_at)
                        VALUES (:id, now(), now(), 'COMMUNICATION', 'QUEUE', 'stock', 'main', '', 3,
                                now() - interval '1 hour')
                        """).param("id", this.db.ids.next()).update();

        this.snapshot().refresh();
        this.snapshot().publish();

        assertThat(this.db
                        .jdbc
                        .sql("SELECT count(*) FROM tc_metrics_snapshot WHERE subsystem = 'COMMUNICATION'")
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
        assertThat(this.metrics.rows).noneMatch(row -> row.type().equals("stock"));
    }

    @Test
    void lockHeldElsewhereSkipsCount() throws Exception {
        try (Connection other = this.db.dataSource.getConnection();
                Statement statement = other.createStatement()) {
            statement.execute("SELECT pg_advisory_lock(" + TaskMetricsSnapshot.LOCK + ", 0)");

            assertThat(this.snapshot().refresh()).isFalse();

            statement.execute("SELECT pg_advisory_unlock(" + TaskMetricsSnapshot.LOCK + ", 0)");
        }
        assertThat(this.snapshot().refresh()).isTrue();
    }

    @Test
    void emptySnapshotPublishesNoAge() {
        this.snapshot().publish();

        assertThat(this.metrics.rows).isEmpty();
        assertThat(this.metrics.age).isNaN();
    }

    private TaskMetricsSnapshot snapshot() {
        return new TaskMetricsSnapshot(
                this.db.dataSource,
                this.db.transactionManager,
                this.db.ids,
                TaskTestSettings.layout(),
                Set.of(KNOWN),
                Duration.ofSeconds(30),
                this.metrics);
    }

    /** Задача упала и ждёт повтора: закрытая попытка и время повтора в будущем. */
    private void retryLater(final CreatedTask task) {
        this.db
                .jdbc
                .sql("""
                        INSERT INTO tc_task_attempt (id, created_at, updated_at, execution_created_at, level,
                                                     target_id, phase, locked_by, locked_until, lease_token,
                                                     started_at, finished_at, status)
                        SELECT :attemptId, now(), now(), t.execution_created_at, 'TASK', t.id, 'PROCESS', 'test',
                               now(), :token, now(), now(), 'FAILED'
                          FROM tc_task t WHERE t.id = :id
                        """)
                .param("attemptId", this.db.ids.next())
                .param("token", UUID.randomUUID())
                .param("id", task.id())
                .update();
        this.db
                .jdbc
                .sql("UPDATE tc_task SET next_attempt_at = now() + interval '1 hour' WHERE id = :id")
                .param("id", task.id())
                .update();
    }

    private double value(final String metric, final String type, final String poller, final String reason) {
        return this.metrics.rows.stream()
                .filter(row -> row.metric().equals(metric)
                        && row.type().equals(type)
                        && row.poller().equals(poller)
                        && row.reason().equals(reason))
                .findFirst()
                .orElseThrow(() -> new AssertionError("нет строки " + metric + " " + type + " " + reason))
                .value();
    }

    /** Запоминает последний прочитанный снимок. */
    private static final class Recording implements TaskMetrics {

        private List<SnapshotRow> rows = List.of();

        private double age;

        @Override
        public void attempt(
                final String type,
                final String level,
                final String phase,
                final String outcome,
                final boolean retry,
                final long nanos) {
            // попытки здесь не нужны
        }

        @Override
        public void snapshot(final List<SnapshotRow> rows, final double ageSeconds) {
            this.rows = List.copyOf(rows);
            this.age = ageSeconds;
        }
    }
}
