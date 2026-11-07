# Синхронный обмен с ответом отправителю

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
