# Задача с группами

Уровень группы нужен, когда обработке объекта требуются общие данные, которые дорого получать
поштучно: прайс-лист на тысячу позиций, справочник курсов, пакетный ответ внешнего API.

```java
@Component
@RequiredArgsConstructor
public class PriceUpdateProcessor implements BatchTaskProcessor<
        UpdateParams,     // TTaskPayload  — параметры задачи
        SupplierBatch,    // TBatchPayload — параметры группы
        ItemPrice,        // TUnitPayload  — один товар
        ItemOutcome,      // TUnitResult   — что вышло с товаром
        SupplierReport,   // TBatchResult  — свод по поставщику
        UpdateReport> {   // TTaskResult   — свод по задаче

    private final SupplierApi supplierApi;

    @Override
    public String taskType() {
        return "price-update";
    }

    @Override
    public void expand(Task<UpdateParams> task, Emitter<SupplierBatch> batches, ProcessingContext context) {
        task.payload().supplierIds().forEach(id -> batches.emit(new SupplierBatch(id)));
    }

    @Override
    public void expandBatch(TaskBatch<SupplierBatch> batch, Emitter<ItemPrice> units, ProcessingContext context) {
        // один поход во внешнюю систему на всю группу
        PriceList list = supplierApi.fetchPriceList(batch.payload().supplierId());
        list.items().forEach(item -> units.emit(new ItemPrice(item.sku(), item.price())));
    }

    @Override
    public ItemOutcome process(TaskUnit<ItemPrice> unit, ProcessingContext context) {
        return apply(unit.payload());   // данные уже внутри подзадачи
    }

    @Override
    public SupplierReport collectBatch(
            TaskBatch<SupplierBatch> batch, Stream<CollectedUnit<ItemPrice, ItemOutcome>> units,
            ProcessingContext context) {
        return SupplierReport.of(batch.payload().supplierId(), units);
    }

    @Override
    public UpdateReport collect(
            Task<UpdateParams> task, Stream<CollectedBatch<SupplierBatch, SupplierReport>> batches,
            ProcessingContext context) {
        return UpdateReport.of(batches);
    }
}
```

**Данные, полученные при раскрытии группы, кладутся в нагрузку каждой подзадачи.** Подзадача
самодостаточна: её повтор через месяц не требует ни группы, ни задачи. Поэтому `TBatchPayload` — параметры
группы («какой поставщик»), а не собранные данные («его прайс-лист»).

Если общие данные объёмны и дублировать их в каждой подзадаче дорого, храните их у себя, ключуя
по `unit.batchId()`. Своего хранилища общих данных у стартера нет.

Группа проходит те же фазы, что задача с подзадачами: `expandBatch` — раскрытие, `collectBatch` —
сборка. Подзадачи группы идут в работу, как только раскрыта их группа, не дожидаясь соседних.
