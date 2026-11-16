# Входящие

## Входящие: асинхронный приём

Входящую коммуникацию создаёт ваш контроллер или слушатель. Асинхронный приём — `receive` —
создаёт её в вашей транзакции, а обрабатывает движок, с повторами, как исходящую. Контроллер
отвечает «принято» сразу.

```java
@PostMapping("/webhooks/shop")
@Transactional
public ResponseEntity<Void> onShopEvent(@RequestBody String body, @RequestHeader HttpHeaders headers) {
    InboundRequest request = InboundRequest.of(body)
            .withHeaders(CommunicationHeaders.of(headers.asMultiValueMap()))
            .withRequestId(headers.getFirst("X-Event-Id"))
            .withDedupKey(headers.getFirst("X-Event-Id"));
    return switch (communicationService.receive(ShopProcessor.class, request)) {
        case ReceiveResult.Accepted<ShopEvent> accepted -> ResponseEntity.accepted().build();
        case ReceiveResult.Duplicate<ShopEvent> duplicate -> ResponseEntity.ok().build();
        case ReceiveResult.Unparseable<ShopEvent> bad -> ResponseEntity.badRequest().build();
    };
}
```

- **Тело разбирается сразу, в потоке контроллера** — так ошибку разбора можно вернуть отправителю
  в его формате. Неразбираемое тело не теряется: коммуникация создаётся сразу `FAILED`, с сырым
  телом, без повторов, а `receive` вернёт `Unparseable`.
- **Повторная доставка** — новая коммуникация. С тем же ключом дедупликации она станет `SKIPPED`,
  а `receive` вернёт `Duplicate` с идентификатором первой. Упавшую коммуникацию повторная доставка
  не восстанавливает — только ручной перезапуск.
- **`InboundRequest` — значение, а не билдер.** Построить его мало — передайте в `receive`.
  Забытый вызов оставит неиспользованное значение, которое подсветит IDE.

Процессор входящей вызывается движком, возможно не с первой попытки и не на этом экземпляре.
Идемпотентность обязательна так же, как у исходящей.

## Входящие: синхронный приём

Иногда отправителю нужен результат обработки в том же соединении. `receiveAndProcess` создаёт
коммуникацию и сразу обрабатывает её **в вашем потоке**, а возвращённое
процессором значение `TSyncResponse` отдаёт вам для ответа.

```java
@PostMapping("/api/orders")
public ResponseEntity<?> placeOrder(@RequestBody String body) {
    return switch (communicationService.receiveAndProcess(OrderIntakeProcessor.class, InboundRequest.of(body))) {
        case ReceiveAndProcessResult.Processed<OrderRequest, OrderAnswer> ok -> ResponseEntity.ok(ok.response());
        case ReceiveAndProcessResult.Duplicate<OrderRequest, OrderAnswer> dup -> ResponseEntity.ok().build();
        case ReceiveAndProcessResult.Unparseable<OrderRequest, OrderAnswer> bad -> ResponseEntity.badRequest().build();
        case ReceiveAndProcessResult.Failed<OrderRequest, OrderAnswer> failed -> ResponseEntity.internalServerError().build();
    };
}
```

Такая коммуникация пишется в режиме запуска `SYNC`: движок её не захватывает и фоном
не повторяет. **Повторы идут в вашем потоке** по [настройкам повторов](retries.md) системы — паузу
между попытками ждёт сам вызов; нужен один заход без повторов — задайте системе `max-attempts: 1`.
Если ваш поток умрёт или зависнет дольше предела обработки, переподхват закроет попытку,
а коммуникацию — `FAILED`: её ответ уже некому отдать.

Два правила:

- **вызывайте вне своей транзакции.** Внутри неё синхронный приём бросает исключение:
  коммуникация коммитится до вызова процессора, и откат ваших данных её бы не откатил;
- **отвечайте тем, что вернул процессор, как есть** — стартер сохранил именно это значение как
  отданный ответ.

Попытки кончились или повтор запрещён — коммуникация `FAILED`, вы получаете `Failed` с причиной.
Вручную такую коммуникацию перезапустить можно: перезапуск ставит её в фоновую обработку,
дальше её ведёт движок ([Отмена и перезапуск](cancel-restart.md)).
