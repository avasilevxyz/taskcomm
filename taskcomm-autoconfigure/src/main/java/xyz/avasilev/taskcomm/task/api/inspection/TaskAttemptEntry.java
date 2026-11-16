package xyz.avasilev.taskcomm.task.api.inspection;

import java.time.Instant;
import java.util.UUID;

/**
 * Строка журнала попыток без результата — для схемы дела (docs/adr/0166-execution-outline-units-and-attempts.md):
 * чья попытка, фаза и исход. По ней дети раскрытия и созданные сущности встают под попытку, которая их создала.
 *
 * @param level уровень сущности
 * @param targetId сущность
 * @param id попытка
 * @param phase фаза: {@code PROCESS}, {@code EXPAND}, {@code COLLECT}
 * @param status исход или {@code null} у открытой попытки
 * @param statusDescription причина исхода или {@code null}
 * @param startedAt начало
 * @param finishedAt конец или {@code null} у открытой попытки
 */
public record TaskAttemptEntry(
        TaskSnapshot.Level level,
        UUID targetId,
        UUID id,
        String phase,
        String status,
        String statusDescription,
        Instant startedAt,
        Instant finishedAt) {}
