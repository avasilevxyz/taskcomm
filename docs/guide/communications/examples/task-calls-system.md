# Задача, которая зовёт внешнюю систему

**Задача.** Подзадача обработки заказа должна уведомить склад. Уведомление — отдельная
коммуникация со своими повторами, а не часть подзадачи: упадёт склад — повторится только
уведомление.

```java
@Override
public OrderOutcome process(TaskUnit<OrderRef> unit, ProcessingContext context) {
    Order order = orders.load(unit.payload().id());
    order.markProcessed();
    communicationService.send(WarehouseProcessor.class, new StockReservation(order.id(), order.lines()),
            CommunicationSendOptions.defaults().dedupKey("reservation-" + order.id()));
    return OrderOutcome.ok(order.id());
}
```

**На что обратить внимание:**

- коммуникация создаётся в потоке процессора и попадает **в то же дело** с указателем на
  подзадачу — на экране дела видно, какая подзадача что отправила;
- процессор работает вне транзакции, и `send` открывает свою. Подзадача упадёт после `send` —
  уведомление уже создано, а повтор подзадачи создаст ещё одно; ключ дедупликации по заказу
  превратит второе в `SKIPPED`;
- создаёте из другого потока — передайте `context.executionRef()` явно, иначе связь с делом
  потеряется молча.
