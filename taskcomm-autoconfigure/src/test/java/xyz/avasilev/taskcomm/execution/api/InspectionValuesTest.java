package xyz.avasilev.taskcomm.execution.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Окно чтения истории и запрос страницы (docs/adr/0138-inspection-api.md): границы и проверки. */
class InspectionValuesTest {

    private static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void windowRequiresEndAfterStart() {
        assertThatThrownBy(() -> HistoryWindow.between(FROM, FROM)).isInstanceOf(IllegalArgumentException.class);
        final HistoryWindow window = HistoryWindow.between(FROM, FROM.plusSeconds(1));

        assertThat(window.from()).hasValue(FROM);
        assertThat(window.isEverywhere()).isFalse();
        assertThat(window).isEqualTo(HistoryWindow.between(FROM, FROM.plusSeconds(1)));
        assertThat(HistoryWindow.everywhere().from()).isEmpty();
        assertThat(HistoryWindow.everywhere().isEverywhere()).isTrue();
    }

    @Test
    void pageSizeIsBounded() {
        assertThatThrownBy(() -> PageRequest.first(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PageRequest.first(PageRequest.MAX_SIZE + 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(PageRequest.first(PageRequest.MAX_SIZE).cursor()).isEmpty();
        assertThat(PageRequest.after("c", 5).cursor()).hasValue("c");
    }
}
