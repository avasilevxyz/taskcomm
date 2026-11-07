# Исходящие

```java
@Transactional
public void changePrice(String sku, BigDecimal price) {
    catalog.updatePrice(sku, price);
    SendResult<PriceUpdate> result = communicationService.send(SupplierProcessor.class, new PriceUpdate(sku, price));
}
```

Коммуникация пишется в вашу транзакцию и уходит после коммита. Дальше движок захватывает её
и вызывает `process`, в котором вы отправляете запрос.

`send` — по классу процессора или по имени системы: `send("supplier", payload)`. Системы без
процессора не существует для стартера — создание бросит исключение. Опции:

```java
communicationService.send(SupplierProcessor.class, update, CommunicationSendOptions.defaults()
        .priority(10)
        .notBefore(tomorrowMorning)
        .dedupKey(update.sku(), hash(update))      // не отправлять то же, что уже ушло
        .correlationId(requestNumber));            // ждать колбэка с этим номером
```

| Опция                                            | Что делает                                                               |
|--------------------------------------------------|--------------------------------------------------------------------------|
| `inExecution(ref)`, `inNewExecution()`           | Дело, как у задач                                                        |
| `priority(n)`                                    | Приоритет, меньше — важнее                                               |
| `notBefore(instant)`                             | Не отправлять раньше                                                     |
| `dedupKey(key)`, `dedupKey(subject, key)`        | Ключ дедупликации, см. [Дедупликация](dedup-correlation.md#дедупликация) |
| `correlationId(id)`, `correlationId(system, id)` | Ключ корреляции, см. [Асинхронный ответ](callback.md)                    |

`SendResult` — запечатанный: `Created` или `Duplicate` с идентификатором совпавшей коммуникации,
если ключ дедупликации совпал прямо при создании.

**Отправка в процессоре.** Запишите запрос до отправки и ответ — как только получили, даже
отказной, и до того, как бросите исключение:

```java
private Ack send(Communication.Outbound<Order> outbound, CommunicationContext context) {
    String body = json.writeValueAsString(outbound.requestPayload());
    context.recordRequest(ExchangeMessage.of(body).withId(requestId));
    String answer = client.post().uri("/orders").body(body)
            .exchange((request, response) -> {
                String text = response.bodyTo(String.class);
                context.recordResponse(ExchangeMessage.of(text)
                        .withStatus(response.getStatusCode().value())
                        .withHeaders(CommunicationHeaders.of(response.getHeaders().asMultiValueMap())));
                return text;
            });
    return json.readValue(answer, Ack.class);
}
```

Каждая запись идёт в базу сразу, своей короткой транзакцией: упадёт экземпляр после отправки —
то, что ушло, всё равно останется в истории. Без записанного ответа стартер не узнает статус:
`503` пройдёт как успех, повтора не будет, а размыкатель не узнает об отказе. Проверить это
за вас нечем; REST- или SOAP-процессор, вернувшийся без записанного ответа, даёт WARN.

**У клиента внешней системы обязан быть тайм-аут, и короче предела обработки.** Прервать ваш код
стартер не может: зависший вызов держит воркера, сколько висит. Предел обработки закроет попытку
неуспешной, но поток останется занятым, пока клиент не вернёт управление, а размыкатель превышение
предела не считает — система, которая не отвечает вовсе, одного за другим заберёт воркеров пула.
Тайм-аут на вашей стороне превращает зависание в исключение, а исключение размыкатель считает.
