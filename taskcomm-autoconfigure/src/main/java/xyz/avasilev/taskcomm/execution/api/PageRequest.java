package xyz.avasilev.taskcomm.execution.api;

import java.util.Objects;
import java.util.Optional;

/**
 * Запрос страницы выдачи — по ключу, а не по смещению (docs/adr/0138-inspection-api.md): курсор —
 * непрозрачная строка из предыдущей страницы. Дальние страницы стоят как первая; номера страницы
 * и общего числа нет.
 */
public final class PageRequest {

    /** Наибольший размер страницы. */
    public static final int MAX_SIZE = 1000;

    private final String cursor;

    private final int size;

    private PageRequest(final String cursor, final int size) {
        if (size < 1 || size > MAX_SIZE) {
            throw new IllegalArgumentException("Размер страницы " + size + " вне [1, " + MAX_SIZE + "]");
        }
        this.cursor = cursor;
        this.size = size;
    }

    /**
     * Первая страница.
     *
     * @param size размер страницы, от 1 до {@link #MAX_SIZE}
     * @return запрос
     */
    public static PageRequest first(final int size) {
        return new PageRequest(null, size);
    }

    /**
     * Страница после курсора предыдущей.
     *
     * @param cursor курсор из {@link Page#next()}
     * @param size размер страницы
     * @return запрос
     */
    public static PageRequest after(final String cursor, final int size) {
        return new PageRequest(Objects.requireNonNull(cursor, "cursor"), size);
    }

    /**
     * Курсор.
     *
     * @return курсор или пусто у первой страницы
     */
    public Optional<String> cursor() {
        return Optional.ofNullable(this.cursor);
    }

    /**
     * Размер страницы.
     *
     * @return размер
     */
    public int size() {
        return this.size;
    }
}
