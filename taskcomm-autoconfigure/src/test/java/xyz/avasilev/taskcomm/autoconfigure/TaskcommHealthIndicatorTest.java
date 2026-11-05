package xyz.avasilev.taskcomm.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

/** Здоровье — живость потоков: DOWN, только если поток молчит дольше заданного числа своих интервалов. */
class TaskcommHealthIndicatorTest {

    private static final Duration SECOND = Duration.ofSeconds(1);

    @Test
    void upWhileEveryMechanismTicksWithinLimit() {
        final Health health = new TaskcommHealthIndicator(
                        Map.of("task", () -> List.of(mechanism("poller.main", Duration.ofSeconds(9)))), 10)
                .health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsKey("task.poller.main");
    }

    @Test
    void downWhenAnyMechanismIsSilentBeyondLimit() {
        final Health health = new TaskcommHealthIndicator(
                        Map.of(
                                "task", () -> List.of(mechanism("poller.main", Duration.ZERO)),
                                "communication", () -> List.of(mechanism("heartbeat", Duration.ofSeconds(11)))),
                        10)
                .health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails().get("communication.heartbeat").toString())
                .contains("STALLED");
    }

    @Test
    void stoppedEnginesReportNothingAndStayUp() {
        final Health health = new TaskcommHealthIndicator(Map.of("task", List::of), 10).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).isEmpty();
    }

    private static TaskcommHealthIndicator.Mechanism mechanism(final String name, final Duration silence) {
        return new TaskcommHealthIndicator.Mechanism(name, SECOND, silence);
    }
}
