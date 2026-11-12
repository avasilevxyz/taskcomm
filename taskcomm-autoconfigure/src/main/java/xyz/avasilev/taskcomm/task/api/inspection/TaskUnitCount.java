package xyz.avasilev.taskcomm.task.api.inspection;

import java.util.UUID;

/**
 * Сколько подзадач родителя в статусе (docs/adr/0150-execution-outline-and-meta.md) — прогресс задачи
 * и группы без чтения самих подзадач.
 *
 * @param taskId задача подзадач
 * @param batchId группа подзадач или {@code null} у подзадач без групп
 * @param status статус — значение {@code TaskUnitStatus}
 * @param count число подзадач
 */
public record TaskUnitCount(UUID taskId, UUID batchId, String status, long count) {}
