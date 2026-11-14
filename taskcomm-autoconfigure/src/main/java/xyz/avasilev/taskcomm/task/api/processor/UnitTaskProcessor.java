package xyz.avasilev.taskcomm.task.api.processor;

import java.util.List;
import java.util.stream.Stream;
import xyz.avasilev.taskcomm.task.api.Task;
import xyz.avasilev.taskcomm.task.api.TaskUnit;

/**
 * Задача с подзадачами: процессор раскрывает задачу в подзадачи, обрабатывает каждую и собирает
 * свод. Форма контракта в базе — {@code UNIT}. Каждый переход асинхронный:
 * {@code expand → process каждой подзадачи → collect}.
 *
 * <pre>{@code
 * @Component
 * public class OrderSyncProcessor implements UnitTaskProcessor<
 *         SyncPeriod,     // TTaskPayload — параметры задачи: период
 *         OrderRef,       // TUnitPayload — один заказ
 *         OrderOutcome,   // TUnitResult — что вышло с заказом
 *         SyncReport> {   // TTaskResult — свод по задаче
 *
 *     public String taskType() {
 *         return "order-sync";
 *     }
 *
 *     public void expand(Task<SyncPeriod> task, Emitter<OrderRef> units, ProcessingContext context) {
 *         orders.findChanged(task.payload()).forEach(units::emit);
 *     }
 *
 *     public OrderOutcome process(TaskUnit<OrderRef> unit, ProcessingContext context) {
 *         return client.push(unit.payload());
 *     }
 *
 *     public SyncReport collect(
 *             Task<SyncPeriod> task, Stream<CollectedUnit<OrderRef, OrderOutcome>> units,
 *             ProcessingContext context) {
 *         return SyncReport.of(units);
 *     }
 * }
 * }</pre>
 *
 * <p><b>Гарантия — at-least-once, не exactly-once</b>: каждый метод обязан быть идемпотентным.
 *
 * @param <TTaskPayload> параметры задачи
 * @param <TUnitPayload> один бизнес-объект подзадачи
 * @param <TUnitResult> результат обработки подзадачи; {@link Void} — не хранить
 * @param <TTaskResult> результат сборки задачи; {@link Void} — не хранить
 */
public non-sealed interface UnitTaskProcessor<TTaskPayload, TUnitPayload, TUnitResult, TTaskResult>
        extends TaskProcessor<TTaskPayload> {

    /**
     * Раскрывает задачу в подзадачи. Повтор раскрытия ничего не удаляет: новая попытка порождает
     * своих детей, дети неуспешной попытки становятся {@code ABANDONED}. Источник перечисляется
     * с начала при каждой попытке.
     *
     * @param task задача
     * @param units приёмник подзадач; действителен только внутри вызова
     * @param context контекст обработки
     */
    void expand(Task<TTaskPayload> task, Emitter<TUnitPayload> units, ProcessingContext context);

    /**
     * Обрабатывает одну подзадачу. Неуспех повторяет только эту подзадачу.
     *
     * @param unit подзадача
     * @param context контекст обработки
     * @return результат обработки; {@code null} не хранится
     */
    TUnitResult process(TaskUnit<TUnitPayload> unit, ProcessingContext context);

    /**
     * Собирает свод, когда у последнего раскрытия не осталось незавершённых подзадач, — в том числе
     * после раскрытия с нулём подзадач. Поток ленивый: подзадач может быть миллион, материализовать
     * его нельзя. Неуспешная сборка делает задачу терминальной и при полностью успешных подзадачах.
     *
     * @param task задача
     * @param units все терминальные подзадачи последнего раскрытия
     * @param context контекст обработки
     * @return результат задачи; {@code null} не хранится
     */
    TTaskResult collect(
            Task<TTaskPayload> task, Stream<CollectedUnit<TUnitPayload, TUnitResult>> units, ProcessingContext context);

    /**
     * Поля нагрузки подзадачи, по которым разрешён поиск.
     *
     * @return поля поиска по нагрузке подзадачи; по умолчанию — ни одного
     */
    default List<SearchableField> searchableUnitFields() {
        return List.of();
    }

    /**
     * Шаблон названия одной подзадачи — для карточки в интерфейсе
     * (docs/adr/0162-task-title-template.md), например {@code "Позиция {sku}"}. Подстановка
     * {@code {имя}} — поле из {@link #searchableUnitFields()}; не из них — падение при старте.
     *
     * <p>Это уточнение, а не полное название: интерфейс пишет его после {@link #title()} —
     * «Выгрузка каталога · Позиция SKU-1», чтобы было видно, частью чего подзадача является.
     *
     * @return шаблон или {@code null}; по умолчанию — {@code null}, и подзадача называется {@link #title()}
     */
    default String unitTitle() {
        return null;
    }
}
