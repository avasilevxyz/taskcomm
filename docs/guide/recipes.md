# Рецепты

Сквозные случаи от постановки до результата. Каждый — задача, решение и на что обратить внимание.
Справочник по каждой возможности — в [Задачах](tasks.md) и [Коммуникациях](communications.md).

## Массовая обработка списка

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

## Обработка партиями

**Задача.** Обновить цены по всем поставщикам. Прайс-лист поставщика — один тяжёлый запрос
на тысячу позиций, и тысячу раз его делать нельзя.

**Решение** — задача с группами: группа на поставщика скачивает прайс-лист один раз и кладёт
цену каждой позиции в её подзадачу. Полный код — в [Задачах](tasks.md#задача-с-группами).

**На что обратить внимание:**

- в нагрузку подзадачи кладутся **данные**, а не ссылка на прайс-лист: подзадача должна
  повторяться без группы, хоть через неделю;
- прайс-лист упал на середине — повтор группы скачает его заново и породит новый набор
  подзадач; прежние станут `ABANDONED`, и чистить их не нужно;
- цены пришли неверные — перезапустите **группу**, а не подзадачи: она заново сходит
  за прайс-листом;
- если общие данные слишком велики, чтобы копировать их в каждую подзадачу, храните их у себя
  по `unit.batchId()`.

## Отложенное действие

**Задача.** Через сутки после регистрации напомнить пользователю подтвердить почту — если он
к тому времени не подтвердил.

```java
@Transactional
public void register(User user) {
    users.save(user);
    Task<Reminder> reminder = taskService.submit(ConfirmReminder.class, new Reminder(user.id()),
            TaskSubmitOptions.defaults().notBefore(Instant.now().plus(Duration.ofDays(1))));
    user.setReminderTask(reminder.id());
}

@Transactional
public void confirm(User user) {
    user.confirm();
    // напоминание больше не нужно; дело создано при регистрации — окно вокруг неё
    HistoryWindow window = HistoryWindow.between(user.registeredAt().minus(Duration.ofMinutes(5)), Instant.now());
    taskInspection.find(TaskSnapshot.Level.TASK, user.reminderTask(), window)
            .ifPresent(taskService::cancel);
}
```

```java
@Override
public Void process(Task<Reminder> task, ProcessingContext context) {
    User user = users.load(task.payload().userId());
    if (user.confirmed()) {
        context.skip("почта уже подтверждена");
        return null;
    }
    mail.sendReminder(user);
    return null;
}
```

**На что обратить внимание:**

- проверка в процессоре нужна, даже если вы отменяете: подтверждение и запуск напоминания
  могут разминуться на миллисекунды;
- отсрочка точна до такта поллера, а момент сравнивается с часами базы;
- задача, отложенная на месяцы, держит свою партицию от удаления — для дальних сроков храните
  дату у себя и ставьте задачу ближе к делу.

## Вызов внешнего REST с повторами

**Задача.** Отправлять заказы в службу доставки. Служба иногда отвечает `503` и иногда лежит
часами; положить её волной повторов нельзя.

```java
@Component
@RequiredArgsConstructor
@CommunicationProcessorSettings(maxAttempts = 10, maxDelay = "30m", rateLimitPerInstance = 5)
public class DeliveryProcessor implements RestCommunicationProcessor<Shipment, ShipmentAck, Void> {

    private final RestClient delivery;     // с тайм-аутом соединения и чтения
    private final JsonMapper json;

    @Override
    public String externalSystem() {
        return "delivery";
    }

    @Override
    public ShipmentAck process(Communication<Shipment> communication, CommunicationContext context) {
        if (!(communication instanceof Communication.Outbound<Shipment> outbound)) {
            context.doNotRetry("служба доставки нам не пишет");
            throw new UnsupportedOperationException("входящая от delivery");
        }
        String body = json.writeValueAsString(outbound.requestPayload());
        context.recordRequest(ExchangeMessage.of(body).withId(outbound.id().toString()));
        String answer = delivery.post().uri("/shipments")
                .header("Idempotency-Key", outbound.id().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((request, response) -> {
                    String text = response.bodyTo(String.class);
                    context.recordResponse(ExchangeMessage.of(text).withStatus(response.getStatusCode().value()));
                    if (response.getStatusCode().is4xxClientError()) {
                        context.doNotRetry("служба отвергла заказ: " + response.getStatusCode());
                        throw new IllegalStateException(text);
                    }
                    return text;
                });
        return json.readValue(answer, ShipmentAck.class);
    }
}
```

```yaml
taskcomm:
  communication:
    systems:
      delivery:
        circuit-breaker: { enabled: true }
```

**На что обратить внимание:**

- `503` и `429` — неуспех по умолчанию, хотя процессор вернулся нормально: стартер видит
  записанный статус и повторит сам;
- `4xx` — ошибка в данных, повтор не поможет: `doNotRetry` перед исключением;
- идентификатор коммуникации уходит в `Idempotency-Key` — повторная отправка после истёкшей
  аренды не создаст второй доставки, если служба это поддерживает;
- размыкатель остановит отправку, пока служба лежит, и попытки не сгорят; после восстановления
  очередь пойдёт со скоростью `rate-limit-per-instance` на экземпляр.

## Задача, которая зовёт внешнюю систему

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

## Приём вебхука

**Задача.** Магазин присылает события об оплатах. Ответить надо быстро, обработать — надёжно,
повторную доставку — отсеять.

```java
@RestController
@RequiredArgsConstructor
class PaymentWebhook {

    private final CommunicationService communications;

    @PostMapping("/webhooks/payments")
    @Transactional
    ResponseEntity<Void> onPayment(@RequestBody String body, @RequestHeader("X-Event-Id") String eventId) {
        InboundRequest request = InboundRequest.of(body)
                .withRequestId(eventId)
                .withDedupKey(eventId);
        return switch (communications.receive(PaymentsProcessor.class, request)) {
            case ReceiveResult.Accepted<PaymentEvent> accepted -> ResponseEntity.accepted().build();
            case ReceiveResult.Duplicate<PaymentEvent> duplicate -> ResponseEntity.ok().build();
            case ReceiveResult.Unparseable<PaymentEvent> bad -> ResponseEntity.badRequest().build();
        };
    }
}
```

```java
@Component
public class PaymentsProcessor implements RestCommunicationProcessor<PaymentEvent, Void, Void> {

    @Override
    public String externalSystem() {
        return "shop";
    }

    @Override
    public Void process(Communication<PaymentEvent> communication, CommunicationContext context) {
        if (communication instanceof Communication.Inbound<PaymentEvent> inbound) {
            payments.apply(inbound.requestPayload());   // идемпотентно по номеру платежа
        }
        return null;
    }
}
```

**На что обратить внимание:**

- контроллер отвечает `202` сразу, обработка — в фоне, с повторами;
- повторная доставка того же события станет `SKIPPED`, а контроллер ответит `200` — для
  отправителя это успех;
- неразбираемое тело сохраняется `FAILED` с сырым текстом — отвечайте отправителю `400`;
- `X-Event-Id` уходит в `requestId` — по нему потом находится коммуникация при разборе.

## Синхронный обмен с ответом отправителю

**Задача.** Партнёр присылает заявку и ждёт в том же соединении номер, который мы ей присвоили.

```java
@PostMapping("/api/applications")
ResponseEntity<?> apply(@RequestBody String body, @RequestHeader("X-Request-Id") String requestId) {
    InboundRequest request = InboundRequest.of(body)
            .withRequestId(requestId)
            .withDedupKey(requestId, sha256(body));          // по последней успешной
    return switch (communications.receiveAndProcess(ApplicationsProcessor.class, request)) {
        case ReceiveAndProcessResult.Processed<Application, ApplicationNumber> ok -> ResponseEntity.ok(ok.response());
        case ReceiveAndProcessResult.Duplicate<Application, ApplicationNumber> dup -> ResponseEntity.status(409).build();
        case ReceiveAndProcessResult.Unparseable<Application, ApplicationNumber> bad -> ResponseEntity.badRequest().build();
        case ReceiveAndProcessResult.Failed<Application, ApplicationNumber> failed -> ResponseEntity.internalServerError().build();
    };
}
```

```java
@Component
@CommunicationProcessorSettings(maxAttempts = 1)          // обязательно для синхронного приёма
public class ApplicationsProcessor implements RestCommunicationProcessor<Application, ApplicationNumber, Void> {
    ...
}
```

**На что обратить внимание:**

- **`maxAttempts = 1`** — иначе после падения вашего потока коммуникация повторится в фоне,
  а ответ уже некому будет отдать;
- метод контроллера **без** `@Transactional`: внутри транзакции синхронный приём бросает
  исключение;
- ключ дедупликации — **по последней успешной**: партнёр, получив `500`, пришлёт заявку снова,
  и ключ по всей истории совпал бы с упавшей;
- отвечайте тем, что вернул процессор, без изменений — оно сохранено как отданный ответ.

## Отправка и ожидание колбэка

**Задача.** Банк принимает платёжное поручение, отвечает «принято, номер Y», а результат
присылает через час на наш адрес с тем же номером.

```java
@Override
public BankAck process(Communication<PaymentOrder> communication, CommunicationContext context) {
    Communication.Outbound<PaymentOrder> outbound = (Communication.Outbound<PaymentOrder>) communication;
    BankAck ack = bank.submit(outbound.requestPayload(), context);   // записывает обмен
    context.registerCorrelationId(ack.reference());                  // теперь ждём колбэка
    return ack;
}

@Override
public void handleResponse(Communication.Outbound<PaymentOrder> communication, BankResult result,
        CommunicationContext context) {
    payments.settle(communication.requestPayload().paymentId(), result);   // идемпотентно
}
```

```java
@PostMapping("/callbacks/bank")
@Transactional
ResponseEntity<Void> onBankResult(@RequestBody String body, @RequestParam("ref") String reference) {
    communications.receiveResponse("bank", CallbackRequest.of(reference, body));
    return ResponseEntity.ok().build();
}
```

```yaml
taskcomm:
  communication:
    systems:
      bank:
        response-timeout: 4h
```

**На что обратить внимание:**

- регистрация ключа и есть ожидание: после успешной отправки коммуникация `AWAITING_RESPONSE`;
- колбэк ляжет в ту же коммуникацию — запрос, «принято» и результат видны в одной истории;
- нет колбэка за `response-timeout` — поручение **уйдёт повторно**. Если банк этого не переносит,
  поставьте срок с запасом или договоритесь о повторной отправке с тем же номером;
- колбэк может прийти раньше, чем закончилась отправка, — это нормально, он дождётся;
- колбэк, пришедший к уже закрытой коммуникации, не обрабатывается никем — его видно как
  `FAILED`-входящую в том же деле.

## Защита от дублей

**Задача.** Каталог шлёт поставщику цены при каждом сохранении товара, но сохраняют его часто,
а цена меняется редко. Отправлять надо только изменившиеся.

```java
@Transactional
public void save(Product product) {
    products.save(product);
    communicationService.send(SupplierProcessor.class, new PriceUpdate(product.sku(), product.price()),
            CommunicationSendOptions.defaults()
                    .dedupKey(product.sku(), product.price().toPlainString()));   // предмет и значение
}
```

Ключ **по последней успешной**: та же цена того же товара, что ушла последней, станет `SKIPPED`;
другая цена — уйдёт; вернувшаяся прежняя цена — тоже уйдёт, потому что последней ушла другая.

Для событий с собственным идентификатором — платёж, запись Kafka — берите ключ **по всей
истории**: одно событие обрабатывается один раз.

**На что обратить внимание:**

- ключ при создании требует изоляции `READ COMMITTED` — это умолчание; в более строгой задайте
  ключ в `beforeProcess` процессора;
- **не меняйте формулу ключа** у работающей системы: старые ключи с новыми не совпадут, и на
  переходе дубликаты пройдут;
- горизонт защиты — глубина хранения: после удаления партиции ключ снова свободен.

## Миграция контракта процессора

**Задача.** В нагрузке задачи `order-sync` вместо диапазона дат нужен список складов. Задачи
старого формата лежат в базе и ещё выполняются.

**Решение** — новый тип задачи, а не правка старого. Тип задачи — ключ данных: процессор
с новым контрактом под старым именем получил бы нагрузки старого формата.

1. Скопируйте процессор под новым типом с суффиксом версии — `order-sync-v2` — и новым
   контрактом.
2. Переключите постановку на новый класс.
3. Пометьте старый процессор `@Deprecated`. Постановка задач старого типа теперь отвергается,
   а уже поставленные выполняются как обычно; при старте стартер напечатает список устаревших
   типов.
4. Дождитесь, пока у старого типа не останется незавершённых: `tc.task.unfinished{type="order-sync"}`
   или сводка по типам в [REST](rest-api.md).
5. **Не удаляйте старый процессор.** Без него упавшую задачу из истории некому перезапустить,
   а новая выкатка без него превратит хвост в осиротевшие задачи.

Полный разбор того, какие изменения ломают данные молча, — в [Совместимости](compatibility.md).
