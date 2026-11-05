package xyz.avasilev.taskcomm.task.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import xyz.avasilev.taskcomm.TaskcommException;

/** Курсор страницы задач: туда и обратно без потерь, чужая строка — исключение стартера. */
class TaskCursorTest {

    @Test
    void roundTrip() {
        final TaskCursor cursor = new TaskCursor(
                Instant.parse("2026-01-01T00:00:00.123456Z"), Instant.parse("2026-02-03T04:05:06Z"), UUID.randomUUID());

        assertThat(TaskCursor.decode(cursor.encode())).isEqualTo(cursor);
    }

    @Test
    void foreignStringIsRejected() {
        assertThatThrownBy(() -> TaskCursor.decode("не курсор")).isInstanceOf(TaskcommException.class);
        assertThatThrownBy(() -> TaskCursor.decode("YWJj")).isInstanceOf(TaskcommException.class);
    }
}
