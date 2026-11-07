package xyz.avasilev.taskcomm.task.api.processor;

import java.util.List;
import java.util.stream.Stream;
import xyz.avasilev.taskcomm.task.api.Task;
import xyz.avasilev.taskcomm.task.api.TaskBatch;
import xyz.avasilev.taskcomm.task.api.TaskUnit;

/**
 * Задача с группами: задача раскрывается в группы, каждая группа — в подзадачи. Группа позволяет
 * один раз сходить во внешнюю систему за данными для всех своих подзадач. Форма контракта в базе —
 * {@code BATCH}. Шесть параметров типа — принятая цена типобезопасности: по нагрузке и результату
 * на каждый уровень (docs/adr/0059-collect-contract.md,
 * docs/adr/0145-meaningful-type-parameter-names.md).
 *
 * <pre>{@code
 * @Component
 * public class PriceSyncProcessor implements BatchTaskProcessor<
 *         SyncParams,      // TTaskPayload — параметры задачи: период и список поставщиков
 *         SupplierBatch,   // TBatchPayload — параметры группы: один поставщик
 *         ItemPrice,       // TUnitPayload — один товар
 *         ItemOutcome,     // TUnitResult — что вышло с товаром
 *         SupplierReport,  // TBatchResult — свод по поставщику
 *         SyncReport> {    // TTaskResult — свод по задаче
 *
 *     public String taskType() {
 *         return "price-sync";
 *     }
 *
 *     public void expand(Task<SyncParams> task, Emitter<SupplierBatch> batches, ProcessingContext context) {
 *         task.payload().suppliers().forEach(supplier -> batches.emit(new SupplierBatch(supplier)));
 *     }
 *
 *     public void expandBatch(
 *             TaskBatch<SupplierBatch> batch, Emitter<ItemPrice> units, ProcessingContext context) {
 *         priceClient.fetchCatalog(batch.payload().supplier())
 *                 .forEach(item -> units.emit(new ItemPrice(item.sku(), item.price())));
 *     }
 *
 *     public ItemOutcome process(TaskUnit<ItemPrice> unit, ProcessingContext context) {
 *         return catalog.applyPrice(unit.payload());
 *     }
 *
 *     public SupplierReport collectBatch(
 *             TaskBatch<SupplierBatch> batch, Stream<CollectedUnit<ItemPrice, ItemOutcome>> units,
 *             ProcessingContext context) {
 *         return SupplierReport.of(batch.payload(), units);
 *     }
 *
 *     public SyncReport collect(
 *             Task<SyncParams> task, Stream<CollectedBatch<SupplierBatch, SupplierReport>> batches,
 *             ProcessingContext context) {
 *         return SyncReport.of(batches);
 *     }
 * }
 * }</pre>
 *
 * <p><b>Гарантия — at-least-once, не exactly-once</b>: каждый метод обязан быть идемпотентным.
 *
 * @param <TTaskPayload> параметры задачи
 * @param <TBatchPayload> параметры группы
 * @param <TUnitPayload> один бизнес-объект подзадачи
 * @param <TUnitResult> результат обработки подзадачи; {@link Void} — не хранить
 * @param <TBatchResult> результат сборки группы; {@link Void} — не хранить
 * @param <TTaskResult> результат сборки задачи; {@link Void} — не хранить
 */
public non-sealed interface BatchTaskProcessor<
                TTaskPayload, TBatchPayload, TUnitPayload, TUnitResult, TBatchResult, TTaskResult>
        extends TaskProcessor<TTaskPayload> {

    /**
     * Раскрывает задачу в группы. Повтор раскрытия ничего не удаляет: дети неуспешной попытки
     * становятся {@code ABANDONED}.
     *
     * @param task задача
     * @param batches приёмник групп; действителен только внутри вызова
     * @param context контекст обработки
     */
    void expand(Task<TTaskPayload> task, Emitter<TBatchPayload> batches, ProcessingContext context);

    /**
     * Раскрывает группу в подзадачи. Данные, собранные здесь, кладутся в нагрузку каждой подзадачи:
     * подзадача самодостаточна. Подзадачи группы идут в работу, как только раскрыта эта группа.
     *
     * @param batch группа
     * @param units приёмник подзадач; действителен только внутри вызова
     * @param context контекст обработки
     */
    void expandBatch(TaskBatch<TBatchPayload> batch, Emitter<TUnitPayload> units, ProcessingContext context);

    /**
     * Обрабатывает одну подзадачу. Неуспех повторяет только эту подзадачу: группа целиком
     * не перезапускается.
     *
     * @param unit подзадача
     * @param context контекст обработки
     * @return результат обработки; {@code null} не хранится
     */
    TUnitResult process(TaskUnit<TUnitPayload> unit, ProcessingContext context);

    /**
     * Собирает свод группы, когда у её последнего раскрытия не осталось незавершённых подзадач.
     * Поток ленивый.
     *
     * @param batch группа
     * @param units все терминальные подзадачи последнего раскрытия группы
     * @param context контекст обработки
     * @return результат группы; {@code null} не хранится
     */
    TBatchResult collectBatch(
            TaskBatch<TBatchPayload> batch,
            Stream<CollectedUnit<TUnitPayload, TUnitResult>> units,
            ProcessingContext context);

    /**
     * Собирает свод задачи, когда у её последнего раскрытия не осталось незавершённых групп.
     *
     * @param task задача
     * @param batches все терминальные группы последнего раскрытия задачи
     * @param context контекст обработки
     * @return результат задачи; {@code null} не хранится
     */
    TTaskResult collect(
            Task<TTaskPayload> task,
            Stream<CollectedBatch<TBatchPayload, TBatchResult>> batches,
            ProcessingContext context);

    /**
     * Поля нагрузки группы, по которым разрешён поиск.
     *
     * @return поля поиска по нагрузке группы; по умолчанию — ни одного
     */
    default List<SearchableField> searchableBatchFields() {
        return List.of();
    }

    /**
     * Поля нагрузки подзадачи, по которым разрешён поиск.
     *
     * @return поля поиска по нагрузке подзадачи; по умолчанию — ни одного
     */
    default List<SearchableField> searchableUnitFields() {
        return List.of();
    }
}
