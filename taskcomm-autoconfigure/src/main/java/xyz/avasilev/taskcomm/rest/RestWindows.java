package xyz.avasilev.taskcomm.rest;

import java.time.Duration;
import java.time.Instant;
import xyz.avasilev.taskcomm.execution.api.HistoryWindow;

/**
 * Окно чтения истории из параметров запроса (docs/adr/0101-active-partition-horizon.md,
 * docs/adr/0138-inspection-api.md): {@code everywhere=true} снимает окно; иначе {@code from} и {@code to},
 * а недостающее берётся от умолчания {@code taskcomm.rest.search-window} назад от {@code to} или от сейчас.
 */
public class RestWindows {

    /** Ширина окна, задающего ровно одну точку {@code execution_created_at}, — точность {@code timestamptz}. */
    private static final Duration ONE_TICK = Duration.ofNanos(1000);

    private final Duration searchWindow;

    /**
     * Собирает окна.
     *
     * @param searchWindow окно по умолчанию
     */
    public RestWindows(final Duration searchWindow) {
        this.searchWindow = searchWindow;
    }

    /**
     * Окно списка или поиска.
     *
     * @param from начало или {@code null}
     * @param to конец или {@code null}
     * @param everywhere искать во всех партициях
     * @return окно
     */
    HistoryWindow resolve(final Instant from, final Instant to, final boolean everywhere) {
        if (everywhere) {
            return HistoryWindow.everywhere();
        }
        final Instant end = to == null ? Instant.now() : to;
        return HistoryWindow.between(from == null ? end.minus(this.searchWindow) : from, end);
    }

    /**
     * Окно одной сущности: известно время создания дела — ровно его партиция, иначе как у списка.
     *
     * @param executionCreatedAt время создания дела или {@code null}
     * @param from начало или {@code null}
     * @param to конец или {@code null}
     * @param everywhere искать во всех партициях
     * @return окно
     */
    HistoryWindow entity(
            final Instant executionCreatedAt, final Instant from, final Instant to, final boolean everywhere) {
        return executionCreatedAt == null
                ? this.resolve(from, to, everywhere)
                : HistoryWindow.between(executionCreatedAt, executionCreatedAt.plus(ONE_TICK));
    }
}
