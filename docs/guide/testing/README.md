# Тестирование

Как тестировать свои процессоры и приложение со стартером. Отдельной тестовой библиотеки
у стартера нет — она и не нужна: процессор — обычный класс, а для интеграционных тестов хватает
PostgreSQL в Testcontainers.

## Модульные тесты процессора

Процессор — обычный класс, а всё, что приходит в его методы, — интерфейсы и записи. Контекст
обработки и `Emitter` подменяются заглушкой или лямбдой:

```java
@Test
void expandEmitsEveryOrderOfThePeriod() {
    OrderSyncProcessor processor = new OrderSyncProcessor(ordersWith("A-1", "A-2"));
    List<OrderRef> emitted = new ArrayList<>();

    processor.expand(task(new SyncPeriod(march)), emitted::add, mock(ProcessingContext.class));

    assertThat(emitted).extracting(OrderRef::id).containsExactly("A-1", "A-2");
}

@Test
void collectCountsFailures() {
    Stream<CollectedUnit<OrderRef, OrderOutcome>> units = Stream.of(
            new CollectedUnit<>(UUID.randomUUID(), new OrderRef("A-1"), synced, TaskUnitStatus.SUCCEEDED, null),
            new CollectedUnit<>(UUID.randomUUID(), new OrderRef("A-2"), null, TaskUnitStatus.FAILED, "тайм-аут"));

    SyncSummary summary = processor.collect(task(period), units, mock(ProcessingContext.class));

    assertThat(summary.failed()).containsExactly("A-2");
}
```

`Task`, `TaskBatch`, `TaskUnit` и `Communication` — интерфейсы: соберите заглушку своей записью
или моком. Проверяйте и неочевидное:

- **повторный вызов** с тем же объектом — процессор обязан быть идемпотентным;
- **`skip` и `doNotRetry`** — `verify(context).skip(...)`; не забудьте, что `doNotRetry` осмыслен
  только вместе с исключением;
- **сборку на смешанных исходах** — в поток приходят и упавшие дети, с пустым результатом.

У процессора коммуникаций проверьте, что запрос и ответ записываются методами контекста,
включая отказной ответ, — от этого зависит исход попытки.

## Интеграционные тесты

**Только PostgreSQL.** H2 и другие встроенные базы не подходят: в них нет партиционирования,
`jsonb`, `SKIP LOCKED` в семантике PostgreSQL и advisory-блокировок. Возьмите Testcontainers:

```java
@SpringBootTest
@Testcontainers
class OrderSyncIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");
}
```

```yaml
# src/test/resources/application-test.yml
taskcomm:
  schema:
    initialize: always      # применить схему при старте теста
```

`initialize: always` удобнее всего в тестах. Если ваша рабочая схема применяется Liquibase
с нашим changelog, тест на тех же миграциях проверит заодно и их.

Такты по умолчанию рассчитаны на рабочую систему. В тестах их стоит укоротить, чтобы задача
бралась сразу, а повтор не ждал секунды:

```yaml
taskcomm:
  task:
    poll: { interval: 100ms }
    retry: { initial-delay: 100ms, max-delay: 1s }
  communication:
    poll: { interval: 100ms }
    retry: { initial-delay: 100ms, max-delay: 1s }
```

Следите за проверками при старте: например, `heartbeat-interval` обязан быть меньше
`lease.duration`, а `initial-delay` — не больше `max-delay`.

## Время в тестах

**Время считает база.** Аренда, паузы перед повтором, `notBefore` и ожидание асинхронного ответа
отсчитываются от `now()` PostgreSQL, а не от часов приложения. Подменённые часы — свой `Clock`,
мок `Instant.now()` — на них не действуют, и «перемотать» отсрочку в тесте нельзя. Что из этого
следует:

- **паузы укорачивайте настройками**, как в примере выше, а не часами;
- **отложенный запуск проверяйте реальным ожиданием**: `notBefore` на несколько секунд вперёд,
  проверка, что задача ещё `PENDING`, и ожидание `SUCCEEDED`. Точность отсрочки — такт поллера
  плюс расхождение часов, на доли секунды она не рассчитана;
- **часы контейнера могут расходиться с часами машины** — например, в виртуальной машине
  Docker Desktop. Момент, посчитанный по часам теста, наступит по часам базы раньше или позже;
  закладывайте запас в обе стороны;
- **истечение аренды** в тесте ждать по умолчанию долго — задайте короткие `lease.duration`
  и `heartbeat-interval`, помня, что второй обязан быть меньше первого.

## Дождаться выполнения

Работа идёт в фоне, поэтому тест ждёт её завершения — например, Awaitility и
[API чтения](../api/java.md):

```java
Task<SyncPeriod> task = taskService.submit(OrderSyncProcessor.class, period);

await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
        assertThat(taskInspection.find(TaskSnapshot.Level.TASK, task.id(), HistoryWindow.everywhere()))
                .get()
                .extracting(TaskSnapshot::status)
                .isEqualTo("SUCCEEDED"));

assertThat(taskService.result(task, SyncSummary.class)).contains(expectedSummary);
```

Постановка в `@Transactional` тесте, который откатывается в конце, воркеру не видна никогда:
задача станет видимой только после коммита. Ставьте задачу вне транзакции теста или
в `TransactionTemplate`, который коммитится.

## Выключить taskcomm в тестах

Тестам, которым стартер не нужен, — например, срезам веб-слоя — его можно выключить целиком:

```yaml
taskcomm:
  enabled: false
```

Тогда не будет ни поллеров, ни проверок схемы при старте — но не будет и бинов `TaskService`,
`CommunicationService` и остальных. Код, который их внедряет, подмените моком
(`@MockitoBean TaskService taskService`). Выключить можно и одну подсистему —
`taskcomm.task.enabled: false` или `taskcomm.communication.enabled: false`.
