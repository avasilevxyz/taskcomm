# Вызов внешнего REST с повторами

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
