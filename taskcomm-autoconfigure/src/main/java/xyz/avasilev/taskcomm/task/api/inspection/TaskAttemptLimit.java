package xyz.avasilev.taskcomm.task.api.inspection;

import java.time.Duration;

/**
 * Предел одной попытки метода процессора — итог лестницы настроек {@code lease.max-execution-time}
 * (docs/adr/0171-durations-and-limits.md). Уровень и фаза однозначно называют метод: у раскрытия
 * группового процессора их два, у задачи и у группы.
 *
 * @param level уровень сущности
 * @param phase фаза: {@code PROCESS}, {@code EXPAND}, {@code COLLECT}
 * @param maxExecutionTime предел попытки
 */
public record TaskAttemptLimit(TaskSnapshot.Level level, String phase, Duration maxExecutionTime) {}
