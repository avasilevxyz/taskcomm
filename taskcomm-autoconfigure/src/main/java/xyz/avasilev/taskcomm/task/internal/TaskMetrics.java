package xyz.avasilev.taskcomm.task.internal;

import java.util.List;

/**
 * Метрики подсистемы задач (docs/rules/observability.md): попытки, закрытые самим экземпляром, и
 * снимок на всю схему, прочитанный из {@code tc_metrics_snapshot}
 * (docs/adr/0102-schema-wide-metrics-snapshot.md).
 *
 * <p>Без Micrometer у потребителя — {@link #NONE}: стартер молчит, ничего не тянет и снимок
 * не считает.
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

    /**
     * Снимок на всю схему прочитан: публикуемые значения заменяются целиком. Строки, которых
     * в снимке больше нет, перестают публиковаться.
     *
     * @param rows строки снимка подсистемы задач
     * @param ageSeconds возраст снимка в момент чтения, в секундах; {@link Double#NaN} — снимка нет
     */
    default void snapshot(final List<SnapshotRow> rows, final double ageSeconds) {}

    /**
     * Строка снимка — значение одной метрики с её метками.
     *
     * @param metric метрика: {@code QUEUE}, {@code DELAYED}, {@code OLDEST_CLAIMABLE_AGE},
     *     {@code OVERDUE}, {@code UNFINISHED}, {@code ORPHANED}
     * @param type тип задачи
     * @param poller поллер; пустая строка — у метрики нет такой метки
     * @param reason причина у {@code DELAYED}: {@code SCHEDULED} или {@code RETRY}; у остальных пусто
     * @param value значение на всю схему
     */
    record SnapshotRow(String metric, String type, String poller, String reason, double value) {}
}
