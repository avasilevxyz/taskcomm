package xyz.avasilev.taskcomm.communication.internal;

import java.util.List;
import java.util.function.IntSupplier;

/**
 * Метрики подсистемы коммуникаций (docs/rules/observability.md): попытки и размыкатели этого
 * экземпляра — их сумма по экземплярам верна — и снимок на всю схему, прочитанный из
 * {@code tc_metrics_snapshot} (docs/adr/0102-schema-wide-metrics-snapshot.md).
 *
 * <p>Без Micrometer у потребителя — {@link #NONE}: стартер молчит, ничего не тянет и снимок
 * не считает.
 */
public interface CommunicationMetrics {

    /** Метрик нет: нет Micrometer или реестра у потребителя. */
    CommunicationMetrics NONE = new NoCommunicationMetrics();

    /**
     * Попытка закрыта этим экземпляром.
     *
     * @param system внешняя система
     * @param direction направление: {@code inbound}, {@code outbound}
     * @param phase фаза: {@code process}, {@code handle_response}
     * @param outcome исход: {@code succeeded}, {@code failed}, {@code skipped}, {@code lease_lost}
     * @param retry повтор ли это
     * @param nanos длительность попытки в наносекундах
     */
    void attempt(String system, String direction, String phase, String outcome, boolean retry, long nanos);

    /**
     * Размыкатель системы заведён на этом экземпляре.
     *
     * @param system внешняя система
     * @param state текущее состояние: 0 — замкнут, 1 — проба, 2 — разомкнут
     */
    void breaker(String system, IntSupplier state);

    /**
     * Переход размыкателя.
     *
     * @param system внешняя система
     * @param to новое состояние: {@code closed}, {@code probing}, {@code open}
     */
    void breakerTransition(String system, String to);

    /**
     * Снимок на всю схему прочитан: публикуемые значения заменяются целиком. Строки, которых
     * в снимке больше нет, перестают публиковаться.
     *
     * @param rows строки снимка подсистемы коммуникаций
     * @param ageSeconds возраст снимка в момент чтения, в секундах; {@link Double#NaN} — снимка нет
     */
    void snapshot(List<SnapshotRow> rows, double ageSeconds);

    /**
     * Строка снимка — значение одной метрики с её метками.
     *
     * @param metric метрика: {@code QUEUE}, {@code DELAYED}, {@code OLDEST_CLAIMABLE_AGE},
     *     {@code OVERDUE}, {@code UNFINISHED}, {@code ORPHANED}
     * @param system внешняя система
     * @param poller поллер; пустая строка — у метрики нет такой метки
     * @param reason причина у {@code DELAYED}: {@code SCHEDULED} или {@code RETRY}; у остальных пусто
     * @param value значение на всю схему
     */
    record SnapshotRow(String metric, String system, String poller, String reason, double value) {}
}
