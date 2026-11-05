package xyz.avasilev.taskcomm.task.internal;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Pattern;
import xyz.avasilev.taskcomm.TaskcommException;

/**
 * Курсор страницы выдачи задач — ключ последней строки {@code (execution_created_at, created_at, id)},
 * непрозрачной строкой (docs/adr/0138-inspection-api.md). Свой у подсистемы: общего кода с
 * коммуникациями нет.
 *
 * @param executionCreatedAt ключ партиционирования
 * @param createdAt время создания
 * @param id идентификатор
 */
record TaskCursor(Instant executionCreatedAt, Instant createdAt, UUID id) {

    private static final Pattern BAR = Pattern.compile("\\|");

    /**
     * Строка курсора.
     *
     * @return непрозрачная строка
     */
    String encode() {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString((this.executionCreatedAt + "|" + this.createdAt + "|" + this.id)
                        .getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Курсор из строки.
     *
     * @param value строка из выдачи
     * @return курсор
     * @throws TaskcommException если строка — не курсор
     */
    static TaskCursor decode(final String value) {
        try {
            final String[] parts =
                    BAR.split(new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8), 3);
            return new TaskCursor(Instant.parse(parts[0]), Instant.parse(parts[1]), UUID.fromString(parts[2]));
        } catch (final IllegalArgumentException | ArrayIndexOutOfBoundsException | DateTimeException bad) {
            throw new TaskcommException("Курсор страницы не разбирается: " + value, bad);
        }
    }
}
