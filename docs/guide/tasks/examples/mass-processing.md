# Массовая обработка списка

**Задача.** Ночью синхронизировать с учётной системой все заказы за сутки — десятки тысяч.
Упавший заказ должен повторяться сам, не задерживая остальные, а утром нужна сводка.

**Решение** — задача с подзадачами: раскрытие перечисляет заказы, каждый обрабатывается
отдельно, сборка считает итог.

```java
public record SyncDay(LocalDate day) {}
public record OrderRef(String id) {}
public record SyncSummary(int synced, List<String> failed) {}

@Component
@RequiredArgsConstructor
@TaskRetry(maxAttempts = 5)
public class DailyOrderSync implements UnitTaskProcessor<SyncDay, OrderRef, Void, SyncSummary> {

    private final OrderRepository orders;
    private final AccountingClient accounting;

    @Override
    public String taskType() {
        return "daily-order-sync";
    }

    @Override
    @TaskLease(maxExecutionTime = "1h")
    public void expand(Task<SyncDay> task, Emitter<OrderRef> units, ProcessingContext context) {
        try (Stream<String> ids = orders.streamIdsCreatedOn(task.payload().day())) {
            ids.forEach(id -> units.emit(new OrderRef(id)));
        }
    }

    @Override
    public Void process(TaskUnit<OrderRef> unit, ProcessingContext context) {
        MDC.put("orderId", unit.payload().id());
        accounting.upsertOrder(orders.load(unit.payload().id()));   // upsert — идемпотентно
        return null;
    }

    @Override
    public SyncSummary collect(Task<SyncDay> task, Stream<CollectedUnit<OrderRef, Void>> units,
            ProcessingContext context) {
        int synced = 0;
        List<String> failed = new ArrayList<>();
        for (CollectedUnit<OrderRef, Void> unit : (Iterable<CollectedUnit<OrderRef, Void>>) units::iterator) {
            if (unit.status() == TaskUnitStatus.SUCCEEDED) {
                synced++;
            } else {
                failed.add(unit.payload().id());
            }
        }
        return new SyncSummary(synced, failed);
    }

    @Override
    public List<SearchableField> searchableUnitFields() {
        return List.of(SearchableField.string("order_id", "$.id", "Номер заказа"));
    }
}
```

Ставит задачу ваш планировщик — стартер расписаний не ведёт:

```java
@Scheduled(cron = "0 0 2 * * *")
public void nightly() {
    taskService.submit(DailyOrderSync.class, new SyncDay(LocalDate.now().minusDays(1)));
}
```

**На что обратить внимание:**

- раскрытие и обработка одного заказа длятся по-разному — предел задан на метод;
- обработка идемпотентна — `upsert`, а не `insert`;
- в сборку приходят все заказы, и упавшие тоже, — сводка называет их поимённо. Если упавших
  десятки тысяч, храните в сводке число, а не список;
- поле `order_id` объявлено — утром «что с заказом A-1024» находится поиском;
- несколько экземпляров с `@Scheduled` поставят задачу несколько раз. Защищайтесь своей
  блокировкой планировщика или проверкой «за этот день уже поставлено» через `findInExecution`
  или свою таблицу.
