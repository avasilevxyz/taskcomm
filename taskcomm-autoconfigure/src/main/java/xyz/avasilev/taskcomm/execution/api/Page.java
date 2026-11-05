package xyz.avasilev.taskcomm.execution.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Страница выдачи: элементы, курсор следующей и окно, в котором искали (docs/adr/0138-inspection-api.md).
 *
 * @param <T> элемент выдачи
 */
public final class Page<T> {

    private final List<T> items;

    private final String next;

    private final HistoryWindow window;

    private Page(final List<T> items, final String next, final HistoryWindow window) {
        this.items = List.copyOf(items);
        this.next = next;
        this.window = Objects.requireNonNull(window, "window");
    }

    /**
     * Собирает страницу.
     *
     * @param items элементы
     * @param next курсор следующей или {@code null} — дальше нет
     * @param window окно, в котором искали
     * @param <T> элемент выдачи
     * @return страница
     */
    public static <T> Page<T> of(final List<T> items, final String next, final HistoryWindow window) {
        return new Page<>(items, next, window);
    }

    /**
     * Элементы страницы.
     *
     * @return элементы
     */
    public List<T> items() {
        return this.items;
    }

    /**
     * Курсор следующей страницы.
     *
     * @return курсор или пусто — это последняя
     */
    public Optional<String> next() {
        return Optional.ofNullable(this.next);
    }

    /**
     * Окно, в котором искали.
     *
     * @return окно
     */
    public HistoryWindow window() {
        return this.window;
    }
}
