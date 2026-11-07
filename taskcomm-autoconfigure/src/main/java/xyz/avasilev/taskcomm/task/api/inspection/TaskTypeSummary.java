package xyz.avasilev.taskcomm.task.api.inspection;

/**
 * Строка сводки по типам: сколько сущностей уровня и типа в статусе (docs/adr/0138-inspection-api.md).
 * На сводке держится шаг миграции контракта «дождаться обнуления» (docs/guide/evolution/contract-migration.md).
 *
 * @param level уровень
 * @param taskType тип задачи
 * @param status статус
 * @param count число сущностей
 * @param orphan у типа нет процессора
 */
public record TaskTypeSummary(TaskSnapshot.Level level, String taskType, String status, long count, boolean orphan) {}
