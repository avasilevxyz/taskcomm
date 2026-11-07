# Отправка и ожидание колбэка

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
