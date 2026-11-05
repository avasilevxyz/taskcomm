package xyz.avasilev.taskcomm.rest;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.UUID;
import xyz.avasilev.taskcomm.execution.api.ExecutionRef;
import xyz.avasilev.taskcomm.execution.api.PageRequest;

/**
 * Общие параметры запроса: время в ISO-8601, дело парой {@code executionId} и {@code executionCreatedAt},
 * окно, страница. Ошибка разбора — {@link IllegalArgumentException}, то есть {@code 400}.
 */
final class RestParams {

    /** Размер страницы по умолчанию. */
    static final int DEFAULT_SIZE = 50;

    private final Map<String, String> params;

    RestParams(final Map<String, String> params) {
        this.params = params;
    }

    String text(final String name) {
        final String value = this.params.get(name);
        return value == null || value.isBlank() ? null : value;
    }

    Instant instant(final String name) {
        final String value = this.text(name);
        try {
            return value == null ? null : Instant.parse(value);
        } catch (final DateTimeParseException bad) {
            throw new IllegalArgumentException("Параметр " + name + " — не момент ISO-8601: " + value, bad);
        }
    }

    UUID uuid(final String name) {
        final String value = this.text(name);
        return value == null ? null : UUID.fromString(value);
    }

    boolean everywhere() {
        return Boolean.parseBoolean(this.text("everywhere"));
    }

    /**
     * Дело из пары параметров.
     *
     * @return дело или {@code null}
     * @throws IllegalArgumentException если задан только один из двух
     */
    ExecutionRef execution() {
        final UUID id = this.uuid("executionId");
        final Instant createdAt = this.instant("executionCreatedAt");
        if (id == null && createdAt == null) {
            return null;
        }
        if (id == null || createdAt == null) {
            throw new IllegalArgumentException("Дело задаётся парой executionId и executionCreatedAt");
        }
        return new ExecutionRef(id, createdAt);
    }

    PageRequest page() {
        final String size = this.text("size");
        final int value = size == null ? DEFAULT_SIZE : Integer.parseInt(size);
        final String cursor = this.text("cursor");
        return cursor == null ? PageRequest.first(value) : PageRequest.after(cursor, value);
    }
}
