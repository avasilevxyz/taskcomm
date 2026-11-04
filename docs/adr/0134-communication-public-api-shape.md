# 0134. Форма публичного API коммуникаций

- **Дата:** 2026-11-05
- **Статус:** принято
- **Изменено:** [0135](0135-communication-engine.md) — `jackson-dataformat-xml` транзитивно не приезжает: SOAP-потребитель
  подключает её сам, без неё SOAP-процессор с разбором по умолчанию — падение при старте.

## Контекст

Этап 5a заводит типы `communication.api` так же, как этап 2 завёл типы задач: процессор должен
компилироваться, ничего не запуская. Прежние решения оставили форму открытой: имена методов
контекста и приёма «условные» ([0076](0076-communication-deduplication.md),
[0086](0086-async-response-in-outbound.md), [0104](0104-wait-defined-by-correlation-key.md)),
форма значения-запроса и ответа вызывающему «утверждаются вместе с API»
([0074](0074-uniform-communication-processing.md), [0133](0133-synchronous-inbound-reception.md)),
состав атрибутов `@CommunicationProcessorSettings` — «на этапе публичного API»
([0066](0066-processor-settings-annotation-names.md)). Не было решено и то, чем стартер разбирает
тело SOAP: XML-библиотеки в зависимостях нет.

## Рассмотренные варианты

### Модель

- **Записи `Inbound(source, requestPayload)` / `Outbound(destination, requestPayload)`**, как
  набросано в словаре CLAUDE.md. Распаковка в `switch` работает, но коммуникации нужны ещё
  идентификатор, дело, статус, причина, время, создатель — и каждое новое поле меняло бы
  канонический конструктор и шаблоны распаковки у потребителя, то есть требовало бы мажорной версии.
  Отвергнуто.
- **Запечатанный интерфейс с двумя `non-sealed` интерфейсами направлений — выбрано**, по образцу
  `Task` ([0111](0111-task-public-api-shape.md)).

### Разбор тела

- **Только JSON у REST и Kafka, методы разбора у SOAP и RAW** — без новой зависимости, но SOAP
  остался бы без разбора по умолчанию, хотя XML у него — сам протокол.
- **XML-кодек стартера для SOAP, методы разбора у всех контрактов — выбрано.**

### Неразбираемое тело

- **Callback в значении-запросе формирует ответ, стартер пишет его в `response_*`** — так задумывал
  [0053](0053-inbound-reception-modes.md). Ещё одно поле запроса ради одного исхода, и это
  расходилось бы с [0133](0133-synchronous-inbound-reception.md): у асинхронного приёма `response_*`
  пуст. Отвергнуто.
- **Итог метода приёма сообщает о неразбираемом теле, ошибку отвечает вызывающий — выбрано.**

### Итог синхронного приёма

- **Возвращать `R`, неуспех и дубликат — исключениями.** Короче в простом случае, но исход
  передаётся исключением, и его разбор не проверяет компилятор. Отвергнуто.
- **Запечатанный итог с вариантами — выбрано**, по образцу `TaskCancelResult`
  ([0125](0125-task-cancel-api.md)).

### Итог создания

- **`send` возвращает `Outbound`, идентификатор совпавшей — методом коммуникации.** Для чтения
  из базы понадобилась бы колонка `duplicate_of`. Отвергнуто.
- **Запечатанные итоги и у `send`, и у `receive` — выбрано**: единообразно с синхронным приёмом,
  ценой обёртки на каждом вызове.

## Решение

### Модель и процессор

- `sealed interface Communication<C> permits Inbound, Outbound`; `Inbound` и `Outbound` —
  `non-sealed` интерфейсы, реализует их стартер. `requestPayload()` у входящей с неразобранным телом —
  `null`. `CommunicationStatus` — семь значений, тест сверяет их с `CHECK`.
- Ключ процессора — **`externalSystem()`**: так называется колонка `external_system` и ключ
  `systems.<имя>`.
- Методы `CommunicationProcessor<C, R, A>`: `process`; **`beforeProcess`** — вызывается при захвате
  перед `process`, по умолчанию ничего не делает, ключи дедупликации задаёт контекстом; `handleResponse`
  для колбэка на исходящую. Третья буква — **`A`**.

### Разбор тела

- Методы **`decodeRequest`** и **`decodeResponse`** есть у каждого контракта. У REST и Kafka
  по умолчанию — JSON маппером нагрузки стартера, у SOAP — XML XML-маппером стартера; разбор
  по умолчанию передаётся в метод аргументом `BodyDecoder<T>`, уже настроенным на тип из дженерика,
  и метод можно переопределить. У RAW оба метода обязательны и аргумента разбора не получают.
- Исключение из метода разбора — неразбираемое тело.
- XML-маппер — **новая зависимость `jackson-dataformat-xml`** ветки Jackson 3; подключается
  на этапе 5b вместе с реализацией разбора. В публичных сигнатурах её типов нет.

### Сервис

- Исходящая — **`send`**: по имени системы, с делом, с `CommunicationSendOptions`, и по классу
  процессора. Возвращает `SendResult<C>`: `Created` или `Duplicate` с идентификатором совпавшей.
- Входящая — **`receive`** (асинхронно) и **`receiveAndProcess`** (синхронно), по имени системы
  и по классу процессора; значение-запрос `InboundRequest` одно на оба метода.
  - `receive` возвращает `ReceiveResult<C>`: `Accepted`, `Duplicate`, `Unparseable`.
  - `receiveAndProcess` возвращает `ReceiveAndProcessResult<C, R>`: `Processed` с ответом процессора,
    `Failed`, `Duplicate`, `Unparseable`.
- Колбэк — **`receiveResponse`** со значением-запросом `CallbackRequest`, итог `ReceiveResponseResult`:
  `Attached`, `Separate`, `Unmatched`, `Unparseable`.
- `findInExecution` — коммуникации дела с одной внешней системой
  ([0110](0110-execution-composition-reads-in-subsystems.md)).

### Значения

- Значения-запросы `InboundRequest`, `CallbackRequest` и сообщение обмена `ExchangeMessage` —
  неизменяемые, настраиваются методами `with…`. Опции исходящей `CommunicationSendOptions` — в форме
  `TaskSubmitOptions`.
- Ключи — `DedupKey` (запечатанный: `WholeHistory`, `LastSucceeded`) и `CorrelationKey`
  (необязательная явная система).
- Заголовки — **`CommunicationHeaders`**: `MessageHeaders` занято Spring, `Headers` — Kafka. Имена
  хранятся как переданы, поиск по имени не различает регистр.

### Контекст

`CommunicationContext`: `skip`, `doNotRetry`, `shouldStop`, `executionRef`, `attemptNumber`,
`isRetry`; **`recordRequest(ExchangeMessage)`**, **`recordResponse(ExchangeMessage)`**;
**`registerCorrelationId(id)`** и **`registerCorrelationId(system, id)`** — их Javadoc говорит, что
регистрация включает ожидание; **`doNotAwaitResponse()`**; **`dedupKey(key)`**
и **`dedupKey(subject, key)`**, отвечающие, дубликат ли коммуникация.

### Аннотация настроек

`@CommunicationProcessorSettings`: аренда и повторы — как у `@TaskProcessorSettings`, на классе
и на методе; `priority`, `responseTimeout`, `failureStatuses`, `circuitBreakerExceptions` — только
на классе. Атрибута `rate-limit-per-instance` пока нет: единица лимита не определена, атрибут
появится вместе с ограничителем на этапе 5d. Сокращений по образцу `@TaskRetry` нет.

## Последствия

- Словарь CLAUDE.md показывает направления интерфейсами; распаковки записи в `switch` у потребителя
  нет — только шаблон типа.
- Каждый `send` возвращает обёртку: коммуникация — `result.communication()`.
- Неразбираемое тело не оставляет в базе ответа отправителю: что ответил вызывающий, знает только он.
- У стартера появится зависимость `jackson-dataformat-xml` — транзитивная для всех потребителей,
  включая тех, у кого нет SOAP.
- Пустой `failureStatuses` в аннотации значит «не задано»: выключить отказные статусы у REST
  аннотацией нельзя, только в `yaml`.
- Перегрузки приёма с `HttpHeaders` ([0052](0052-communication-transport-contracts.md)) не заведены;
  при нужде добавятся без поломки совместимости.

## Связанные решения

- [0052](0052-communication-transport-contracts.md) — транспортные контракты и нейтральные заголовки.
- [0053](0053-inbound-reception-modes.md) — callback на неразбираемое тело, здесь отменённый.
- [0066](0066-processor-settings-annotation-names.md) — имя аннотации и отложенный состав атрибутов.
- [0076](0076-communication-deduplication.md), [0086](0086-async-response-in-outbound.md),
  [0104](0104-wait-defined-by-correlation-key.md) — условные имена, здесь утверждённые.
- [0111](0111-task-public-api-shape.md) — интерфейсы вместо записей у задач.
- [0133](0133-synchronous-inbound-reception.md) — два метода приёма и ответ отправителю.
