# Приём вебхука

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
