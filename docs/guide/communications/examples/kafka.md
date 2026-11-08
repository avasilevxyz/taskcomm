# Приём из Kafka

**Задача.** Платёжный шлюз публикует события в топик `payments`. Каждое событие надо обработать
ровно один раз с точки зрения бизнеса — при том что Kafka после ребалансировки или падения
доставляет записи повторно.

Слушатель — ваш код на Spring for Apache Kafka; стартер типов Kafka не знает и не тянет.
Слушатель только принимает запись, а обрабатывает её движок, с повторами:

```java
@Component
@RequiredArgsConstructor
@Slf4j
class PaymentsListener {

    private final CommunicationService communications;

    @KafkaListener(topics = "payments")
    void onPayment(ConsumerRecord<String, String> record) {
        CommunicationHeaders headers = CommunicationHeaders.empty();
        for (Header header : record.headers()) {
            if (header.value() != null) {
                headers = headers.with(header.key(), new String(header.value(), StandardCharsets.UTF_8));
            }
        }
        String eventId = headers.first("event-id")
                .orElse(record.topic() + ":" + record.partition() + ":" + record.offset());

        InboundRequest request = InboundRequest.of(record.value())
                .withHeaders(headers)
                .withRequestId(eventId)
                .withRequestAt(Instant.ofEpochMilli(record.timestamp()))
                .withDedupKey(eventId);

        switch (communications.receive(PaymentsBusProcessor.class, request)) {
            case ReceiveResult.Accepted<PaymentEvent> accepted -> { }
            case ReceiveResult.Duplicate<PaymentEvent> duplicate ->
                    log.debug("событие {} уже принято как {}", eventId, duplicate.duplicateOf());
            case ReceiveResult.Unparseable<PaymentEvent> bad ->
                    log.warn("событие {} не разобрано: {}", eventId, bad.reason());
        }
    }
}
```

```java
@Component
public class PaymentsBusProcessor implements KafkaCommunicationProcessor<PaymentEvent, Void, Void> {

    @Override
    public String externalSystem() {
        return "payments-bus";
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

- **смещение коммитится после `receive`** — так подтверждает запись контейнер Spring for Apache
  Kafka по умолчанию, когда слушатель вернул управление. Коммуникация уже в базе, когда Kafka
  узнаёт, что запись прочитана. Упали между этими моментами — запись придёт снова, станет
  `Duplicate` и `SKIPPED`, и работа не задвоится. Не включайте автокоммит клиента
  `enable.auto.commit`: он сдвигает смещение по таймеру, и запись можно потерять до приёма;
- **ключ дедупликации — идентификатор события**, ключ по всей истории. Нет своего идентификатора —
  координаты записи `топик:партиция:смещение`: они ловят повторную доставку той же записи,
  но не повторную публикацию того же события продюсером;
- **неразбираемое событие не бросайте обратно в Kafka.** Исключение из слушателя заставит
  контейнер читать ту же запись снова и снова. Коммуникация уже сохранена `FAILED` с сырым телом —
  её видно в REST и метриках, а после исправления процессора её можно перезапустить;
- **`@Transactional` на слушателе нужен, только если** вместе с приёмом вы пишете свои данные.
  Без транзакции `receive` откроет свою, короткую;
- тело по умолчанию — JSON, его разбирает маппер нагрузки стартера. Другой формат — переопределите
  `decodeRequest` в процессоре, см. [Тела сообщений и заголовки](../bodies.md).
