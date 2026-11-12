# REST API

Встроенные эндпоинты для экранов сопровождения и ручных операций: чтение, поиск, отмена,
перезапуск, создание. Построены поверх [API чтения](java.md) и сервисов задач
и коммуникаций — всё то же можно сделать из своего кода.

## Включение

REST **выключен по умолчанию**: в нём отмена, перезапуск, создание коммуникаций и тела обмена
с внешними системами, и подключение стартера не должно открывать их наружу.

```yaml
taskcomm:
  rest:
    enabled: true
    base-path: /tc/api            # по умолчанию; до 2.0.2 было /api/taskcomm — см. «Обновление версии»
```

Нужно servlet-приложение со Spring MVC (`spring-boot-starter-webmvc`). Контроллеры подсистемы
появляются вместе с подсистемой: выключили коммуникации — нет и их эндпоинтов.

## Безопасность

**Стартер не трогает Spring Security и не закрывает свои пути.** Включив REST, закройте их сами
тем, чем закрываете остальное приложение. Чтобы не перечислять пути вручную, стартер отдаёт бин
`RequestMatcher` с именем `taskcommRequestMatcher` — он есть, если REST включён и Spring Security
на classpath:

```java
@Bean
SecurityFilterChain security(HttpSecurity http,
        @Qualifier("taskcommRequestMatcher") RequestMatcher taskcomm) throws Exception {
    return http
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers(taskcomm).hasRole("OPERATOR")
                    .anyRequest().authenticated())
            .httpBasic(Customizer.withDefaults())
            .build();
}
```

Без Spring Security бина нет, а пути открыты всем, кто достучится до приложения.

## Задачи

Пути — от `{base-path}/tasks`. Уровень — `TASK`, `BATCH` или `UNIT`.

| Метод и путь                        | Что делает                                                                                     |
|-------------------------------------|------------------------------------------------------------------------------------------------|
| `GET /tasks`                        | Список уровня `level` (по умолчанию `TASK`) с фильтрами и поиском                              |
| `GET /tasks/{level}/{id}`           | Одна сущность                                                                                  |
| `GET /tasks/{level}/{id}/attempts`  | Её попытки: фаза, исход, время, экземпляр, результат                                           |
| `POST /tasks/{level}/{id}/cancel`   | Отмена                                                                                         |
| `POST /tasks/{level}/{id}/restart`  | Перезапуск                                                                                     |
| `POST /tasks?type={taskType}`       | Постановка; тело — нагрузка в JSON                                                             |
| `GET /tasks/search-fields`          | Поля, доступные для поиска, с подписями и описаниями                                           |
| `GET /tasks/summary`                | Сводка: уровень, тип, статус, количество, признак осиротевшего типа                            |
| `POST /tasks/discard-child-results` | Удалить результаты детей у собранных родителей; `olderThan` — только старше, например `PT168H` |

Фильтры списка: `type`, `status` (можно несколько), `parentId`, дело парой `executionId`
и `executionCreatedAt`, поля нагрузки — `field.<имя>`:

```
GET /tc/api/tasks?level=UNIT&field.order_id=A-1024&status=FAILED
```

Постановка принимает `priority`, `notBefore` (ISO-8601) и дело парой `executionId`
и `executionCreatedAt`; отвечает `201` с идентификатором, делом и статусом. Нагрузка разбирается
в тип процессора до записи: не разобралась — `400`.

## Коммуникации

Пути — от `{base-path}/communications`.

| Метод и путь                                  | Что делает                                                       |
|-----------------------------------------------|------------------------------------------------------------------|
| `GET /communications`                         | Список с фильтрами                                               |
| `GET /communications/{id}`                    | Одна коммуникация                                                |
| `GET /communications/{id}/attempts`           | Попытки с полным обменом: запрос, ответ, заголовки, тела, статус |
| `POST /communications/{id}/cancel`            | Отмена                                                           |
| `POST /communications/{id}/restart`           | Перезапуск                                                       |
| `POST /communications/outbound/{destination}` | Отправка; тело — запрос в JSON                                   |
| `GET /communications/summary`                 | Сводка: направление, система, статус, количество                 |
| `GET /communications/circuit-breakers`        | Состояние размыкателей **ответившего экземпляра**, с его именем  |

Фильтры списка: `direction` (`INBOUND`, `OUTBOUND`), `system`, `status`, дело парой
`executionId` и `executionCreatedAt`, `requestId` — идентификатор запроса во внешней системе,
для разбора «вы нам это присылали?».

Отправка принимает `priority`, `notBefore`, `dedupKey`, `correlationId` и дело; отвечает `201`,
а при совпавшем ключе дедупликации — с `duplicateOf`.

Приёма входящих в REST нет и не будет: входящие создаёт ваш контроллер, см.
[Коммуникации](../communications/README.md).

## Дела

`GET {base-path}/executions/{id}?createdAt={время создания дела}` — дело целиком: задачи, группы,
подзадачи, коммуникации и ключи корреляции, в порядке создания, каждая сущность с указателем
на создателя. Без `createdAt` дело ищется по всем партициям — медленнее, но работает
с идентификатором из лога.

Предела размера у выдачи дела нет: дело с миллионом подзадач отдастся целиком. Для таких дел
есть выдача без подзадач.

`GET {base-path}/executions/{id}/outline?createdAt={время создания дела}` — то же дело, но вместо
подзадач — `unitCounts`: сколько подзадач у каждой задачи и группы в каком статусе (`batchId` пуст
у подзадач без групп). Сами подзадачи читайте списком `GET {base-path}/tasks?level=UNIT` с делом
(`executionId`, `executionCreatedAt`) и родителем (`parentId`), страницами. Из Java те же счётчики —
`TaskInspection.unitCounts(executionRef)`. Счёт идёт по всем подзадачам дела, и на миллионе это
секунды: не опрашивайте его чаще, чем раз в несколько секунд.

## Описание экземпляра

`GET {base-path}/meta` — что умеет этот экземпляр: `version` — версия стартера (нет при запуске
не из jar), `searchWindow` — окно по умолчанию в ISO-8601, `tasks.types` — типы задач с процессором
и формой контракта (`FLAT`, `UNIT`, `BATCH`), `communications.systems` — внешние системы с процессором
и транспортом (`REST`, `SOAP`, `KAFKA`, `RAW`). Выключенной подсистемы в ответе нет.

## Поиск и окно

Списки, сводки и поиск идут **в окне по времени создания дела**:

| Параметр          | Смысл                                                         |
|-------------------|---------------------------------------------------------------|
| `from`, `to`      | Границы окна, ISO-8601                                        |
| `everywhere=true` | Без окна — по всем партициям. Медленно на большой истории     |
| ничего            | Последние `taskcomm.rest.search-window`, по умолчанию 30 дней |

Окно — по времени создания **дела**, не сущности: коммуникация, созданная сегодня в деле
годовой давности, в окно по умолчанию не попадёт. Каждая выдача списка называет окно, в котором
искала, — `"window": {"from": ..., "to": ..., "everywhere": false}`: «не найдено» всегда
значит «не найдено в этом окне».

Одна сущность ищется в том же окне — или ровно в партиции своего дела, если передать
`executionCreatedAt`.

Страницы — по курсору: в ответе `next`, его передают параметром `cursor`; размер — `size`,
по умолчанию 50.

```json
{
  "items": [ ... ],
  "next": "eyJ...",
  "window": { "from": "2026-10-07T00:00:00Z", "to": "2026-11-06T00:00:00Z", "everywhere": false }
}
```

## Примеры запросов и ответов

Формы ответов сняты с работающего стартера; имена типов, полей и значения условные,
идентификаторы сокращены. Поля со значением `null` в ответ не попадают: у задачи без описания
статуса нет `statusDescription`, у последней страницы нет `next`.

**Поставить задачу:**

```
POST /tc/api/tasks?type=order-sync
Content-Type: application/json

{"order_id": "A-1024"}
```

```json
201 Created
{
  "id": "01a1b72c-7428-…",
  "execution": { "id": "01a1b72c-7426-…", "createdAt": "2026-11-07T18:01:46.271987Z" },
  "status": "PENDING"
}
```

Сохраните пару `execution.id` и `execution.createdAt`: по ней дело читается в своей партиции,
без обхода всех.

**Найти задачу по полю нагрузки:**

```
GET /tc/api/tasks?field.order_id=A-1024
```

```json
200 OK
{
  "items": [
    {
      "id": "01a1b72c-7428-…",
      "level": "TASK",
      "taskType": "order-sync",
      "execution": { "id": "01a1b72c-7426-…", "createdAt": "2026-11-07T18:01:46.271987Z" },
      "parents": {},
      "state": {
        "status": "PENDING",
        "priority": 100,
        "createdAt": "2026-11-07T18:01:46.271987Z",
        "updatedAt": "2026-11-07T18:01:46.271987Z",
        "orphan": false
      },
      "payload": { "order_id": "A-1024" }
    }
  ],
  "window": { "from": "2026-10-08T18:01:46Z", "to": "2026-11-07T18:01:46Z", "everywhere": false }
}
```

`parents` у подзадачи несёт `taskId` и `batchId`, у задачи он пуст. `payload` — нагрузка как
она лежит в базе.

**Поле, которое никто не объявил:**

```
GET /tc/api/tasks?field.customer=x
```

```json
400 Bad Request
{
  "error": "Поиск по полям [customer] уровня TASK: ни один тип не объявил все эти поля с подходящим типом значения. Известные поля: [order_id].",
  "knownFields": ["order_id"]
}
```

**Отменить и перезапустить:**

```
POST /tc/api/tasks/TASK/01a1b72c-7428-…/cancel
```

```json
200 OK
{ "accepted": true, "reason": "отменена сразу: работа над ней не шла" }
```

Повторная отмена и перезапуск незавершённой отвергаются с `409` и причиной:

```json
409 Conflict
{ "accepted": false, "reason": "перезапускается только завершённая сущность, а задача 01a1b72c-7428-… в статусе PENDING" }
```

**Сводка по типам:**

```
GET /tc/api/tasks/summary
```

```json
200 OK
[ { "level": "TASK", "taskType": "order-sync", "status": "PENDING", "count": 1, "orphan": false } ]
```

**Отправить коммуникацию и найти её:**

```
POST /tc/api/communications/outbound/bank
Content-Type: application/json

{"id": "payment-77"}
```

```json
201 Created
{ "id": "01a1b72c-87e5-…", "execution": { "id": "01a1b72c-87e3-…", "createdAt": "2026-11-07T18:01:51.325202Z" }, "status": "PENDING" }
```

```
GET /tc/api/communications?system=bank
```

```json
200 OK
{
  "items": [
    {
      "id": "01a1b72c-87e5-…",
      "direction": "OUTBOUND",
      "externalSystem": "bank",
      "execution": { "id": "01a1b72c-87e3-…", "createdAt": "2026-11-07T18:01:51.325202Z" },
      "state": {
        "status": "PENDING",
        "phase": "PROCESS",
        "priority": 100,
        "createdAt": "2026-11-07T18:01:51.325202Z",
        "updatedAt": "2026-11-07T18:01:51.325202Z",
        "orphan": false
      },
      "requestPayload": { "id": "payment-77" }
    }
  ],
  "window": { "from": "2026-10-08T18:01:51Z", "to": "2026-11-07T18:01:51Z", "everywhere": false }
}
```

**Дело целиком:**

```
GET /tc/api/executions/01a1b72c-7426-…?createdAt=2026-11-07T18:01:46.271987Z
```

```json
200 OK
{
  "execution": { "id": "01a1b72c-7426-…", "createdAt": "2026-11-07T18:01:46.271987Z" },
  "tasks": [ { "id": "01a1b72c-7428-…", "level": "TASK", "taskType": "order-sync", "…": "…" } ],
  "batches": [],
  "units": [],
  "communications": [],
  "correlationKeys": []
}
```

## Ошибки

| Код   | Когда                                                                                 | Тело                                                                      |
|-------|---------------------------------------------------------------------------------------|---------------------------------------------------------------------------|
| `400` | Неверный параметр, неразбираемая нагрузка, неизвестный тип, необъявленное поле поиска | `{"error": "...", "knownFields": [...]}` — список полей только для поиска |
| `404` | Сущность не найдена в окне                                                            | Пустое; что и где искали — в заголовке `X-Taskcomm-Not-Found`             |
| `409` | Отмена или перезапуск отвергнуты                                                      | `{"accepted": false, "reason": "..."}`                                    |
| `200` | Отмена или перезапуск приняты                                                         | `{"accepted": true, "reason": "..."}`                                     |

Обработчики ошибок стартера действуют только на его контроллеры и ваших не трогают.
