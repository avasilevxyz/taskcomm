package xyz.avasilev.taskcomm.task.internal.observation;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Публикация снимка на всю схему (docs/adr/0102-schema-wide-metrics-snapshot.md): метки по метрике,
 * причина строчными, исчезнувшая строка перестаёт публиковаться, возраст снимка растёт с чтения,
 * а в описании сказано про {@code max()}.
 */
class MicrometerTaskMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private final MicrometerTaskMetrics metrics = new MicrometerTaskMetrics(this.registry);

    @Test
    void publishesSnapshotRowsWithTheirLabels() {
        this.metrics.snapshot(
                List.of(
                        new TaskMetrics.SnapshotRow("QUEUE", "report", "main", "", 5),
                        new TaskMetrics.SnapshotRow("DELAYED", "report", "main", "RETRY", 2),
                        new TaskMetrics.SnapshotRow("ORPHANED", "forgotten", "", "", 1)),
                3.0);

        assertThat(this.registry
                        .get("tc.task.queue")
                        .tags("type", "report", "poller", "main")
                        .gauge()
                        .value())
                .isEqualTo(5.0);
        assertThat(this.registry
                        .get("tc.task.delayed")
                        .tags("type", "report", "poller", "main", "reason", "retry")
                        .gauge()
                        .value())
                .isEqualTo(2.0);
        assertThat(this.registry
                        .get("tc.task.orphaned")
                        .tags("type", "forgotten")
                        .gauge()
                        .value())
                .isEqualTo(1.0);
        assertThat(this.registry.get("tc.task.queue").gauge().getId().getDescription())
                .contains("max()");
        assertThat(this.registry
                        .get("tc.metrics.snapshot.age")
                        .tags("subsystem", "task")
                        .gauge()
                        .value())
                .isGreaterThanOrEqualTo(3.0);
    }

    @Test
    void rowMissingFromNextSnapshotStopsBeingPublished() {
        this.metrics.snapshot(List.of(new TaskMetrics.SnapshotRow("ORPHANED", "forgotten", "", "", 1)), 0.0);
        this.metrics.snapshot(List.of(), 0.0);

        assertThat(this.registry.find("tc.task.orphaned").gauges()).isEmpty();
    }

    @Test
    void ageIsUnknownUntilFirstRead() {
        assertThat(this.registry
                        .get("tc.metrics.snapshot.age")
                        .tags("subsystem", "task")
                        .gauge()
                        .value())
                .isNaN();
    }
}
