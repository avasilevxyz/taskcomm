package xyz.avasilev.taskcomm.communication.internal;

import java.util.function.IntSupplier;

/**
 * Метрики того, что сделал сам экземпляр, — попытки коммуникаций и размыкатели
 * (docs/rules/observability.md). В снимок на всю схему не идут: их сумма по экземплярам верна
 * (docs/adr/0102-schema-wide-metrics-snapshot.md).
 *
 * <p>Без Micrometer у потребителя — {@link #NONE}: стартер молчит и ничего не тянет.
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
}
