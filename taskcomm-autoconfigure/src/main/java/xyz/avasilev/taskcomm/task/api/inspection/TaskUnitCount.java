package xyz.avasilev.taskcomm.task.api.inspection;

import java.time.Instant;
import java.util.UUID;

/**
 * Сколько подзадач родителя в статусе (docs/adr/0150-execution-outline-and-meta.md) — прогресс задачи
 * и группы без чтения самих подзадач.
 *
 * @param taskId задача подзадач
 * @param batchId группа подзадач или {@code null} у подзадач без групп
 * @param status статус — значение {@code TaskUnitStatus}
 * @param count число подзадач
 * @param lastUpdatedAt последнее изменение подзадач этого родителя в этом статусе, у завершённых — когда
 *     закончилась последняя (docs/adr/0171-durations-and-limits.md); {@code null} у реализации, которая
 *     его не считает
 */
public record TaskUnitCount(UUID taskId, UUID batchId, String status, long count, Instant lastUpdatedAt) {

    /**
     * Счётчик без времени последнего изменения — форма до docs/adr/0171-durations-and-limits.md.
     *
     * @param taskId задача подзадач
     * @param batchId группа подзадач или {@code null} у подзадач без групп
     * @param status статус — значение {@code TaskUnitStatus}
     * @param count число подзадач
     */
    public TaskUnitCount(final UUID taskId, final UUID batchId, final String status, final long count) {
        this(taskId, batchId, status, count, null);
    }
}
