package xyz.avasilev.taskcomm.task.internal;

/**
 * Метрики того, что сделал сам экземпляр, — попытки задач (docs/rules/observability.md). В снимок
 * на всю схему не идут: их сумма по экземплярам верна (docs/adr/0102-schema-wide-metrics-snapshot.md).
 *
 * <p>Без Micrometer у потребителя — {@link #NONE}: стартер молчит и ничего не тянет.
 */
public interface TaskMetrics {

    /** Метрик нет: Micrometer'а или реестра у потребителя нет. */
    TaskMetrics NONE = (type, level, phase, outcome, retry, nanos) -> {};

    /**
     * Попытка закрыта этим экземпляром.
     *
     * @param type тип задачи
     * @param level уровень сущности: {@code task}, {@code batch}, {@code unit}
     * @param phase фаза: {@code process}, {@code expand}, {@code collect}
     * @param outcome исход: {@code succeeded}, {@code failed}, {@code skipped}, {@code lease_lost}
     * @param retry повтор ли это
     * @param nanos длительность попытки в наносекундах
     */
    void attempt(String type, String level, String phase, String outcome, boolean retry, long nanos);
}
