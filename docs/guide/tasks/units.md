# Задача с подзадачами

Задача проходит три фазы: **раскрытие** порождает подзадачи, каждая **обрабатывается** отдельно,
**сборка** сводит их результаты в результат задачи.

```java
@Component
@RequiredArgsConstructor
public class OrderSyncProcessor
        implements UnitTaskProcessor<
                SyncPeriod,       // TTaskPayload — период синхронизации
                OrderRef,         // TUnitPayload — один заказ
                OrderOutcome,     // TUnitResult  — что вышло с заказом
                SyncSummary> {    // TTaskResult  — свод по задаче

    private final OrderRepository orders;

    @Override
    public String taskType() {
        return "order-sync";
    }

    @Override
    public void expand(Task<SyncPeriod> task, Emitter<OrderRef> units, ProcessingContext context) {
        orders.streamIds(task.payload().from(), task.payload().to())
                .forEach(id -> units.emit(new OrderRef(id)));
    }

    @Override
    public OrderOutcome process(TaskUnit<OrderRef> unit, ProcessingContext context) {
        return sync(unit.payload());   // один заказ
    }

    @Override
    public SyncSummary collect(
            Task<SyncPeriod> task, Stream<CollectedUnit<OrderRef, OrderOutcome>> units, ProcessingContext context) {
        return units.reduce(SyncSummary.empty(), SyncSummary::plus, SyncSummary::plus);
    }
}
```

**Раскрытие.** Порождённое отдаётся в `Emitter`, а не возвращается списком: стартер копит
подзадачи в буфере и пишет пачками, каждую своей короткой транзакцией. Ни миллион объектов
в памяти, ни миллион строк в одной транзакции. Отсюда правила:

- `Emitter` действителен только внутри вызова `expand` и не потокобезопасен. Сохранили в поле
  и вызвали позже — `IllegalStateException`.
- Раскрытие не атомарно. Упало посередине — уже записанные подзадачи становятся `ABANDONED`,
  а повтор раскрытия перечисляет источник **с начала** и порождает свой набор. Ни удалять
  прежних, ни отсеивать повторы не нужно.
- Раскрытие может честно вернуть **ноль** подзадач — это успех, а не ошибка. Сборка всё равно
  запустится, с пустым потоком.

Подзадачи идут в работу, когда раскрытие закончилось успехом.

**Обработка.** `process` вызывается для каждой подзадачи. Упала — повторяется **только она**,
соседи и задача не трогаются.

**Сборка.** Когда все подзадачи закончились, задача возвращается в очередь и получает вызов
`collect`. Что в него приходит:

- **поток, а не список** — читается страницами по мере потребления. Обрабатывайте лениво:
  `toList()` на миллионе подзадач положит ваше приложение;
- **все терминальные подзадачи**, а не только успешные. Каждая — `CollectedUnit` с id,
  нагрузкой, результатом, статусом и его описанием. Так в сводке можно назвать конкретный
  упавший объект. У неуспешных и у вернувших `null` результат пуст.

Сборка выполняется под арендой, попадает под предел обработки и может быть повторена — она
обязана быть идемпотентной наравне с `process`.

Статус задачи после сборки ставит стартер по детям:

| Дети                                                | Задача      |
|-----------------------------------------------------|-------------|
| все успешны (пропущенные не мешают)                 | `SUCCEEDED` |
| часть упала или отменена                            | `WARNING`   |
| ни одного выполненного, есть упавшие или отменённые | `FAILED`    |
| все пропущены                                       | `SKIPPED`   |
| детей нет                                           | `SUCCEEDED` |

Упала сама сборка и исчерпала попытки — задача `FAILED`, даже если все подзадачи успешны.
