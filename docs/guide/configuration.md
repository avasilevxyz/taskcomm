# Настройки

Все свойства — под префиксом `taskcomm.*`, у каждого есть значение по умолчанию, и стартер
работает, не настроенный вовсе. Полный перечень с умолчаниями — в конце страницы,
[одним примером](#полный-справочник-свойств). Длительности — в формате Spring Boot: `500ms`,
`30s`, `5m`, `2h`, `7d`.

## Как складываются настройки

Аренда, повторы и приоритет приходят из двух источников: **аннотации на процессоре** — умолчание
от автора, который знает профиль своей работы, — и **`application.yml`** — переопределение для
эксплуатации. От сильного к слабому:

1. `yaml`: тип задачи или система, и фаза — `types.<тип>.phases.<фаза>`;
2. `yaml`: тип задачи или система — `types.<тип>` или `systems.<система>`;
3. аннотация на методе процессора;
4. аннотация на классе процессора;
5. глобальное значение подсистемы — `taskcomm.task.retry`, `taskcomm.communication.lease` и т. п.

**Любое значение из `yaml` сильнее любой аннотации** — правило по источнику, а не по точности:
во время инцидента эксплуатация правит конфигурацию и обязана быть уверена, что правка
применилась, даже к фазе с аннотацией на методе.

Каждое свойство разрешается отдельно: можно переопределить в `yaml` только `max-attempts`,
а остальное возьмётся по цепочке ниже.

```yaml
taskcomm:
  task:
    retry: { max-attempts: 3 }                    # 5: для всех типов
    types:
      order-sync:                                 # 2: для типа
        retry: { max-attempts: 10 }
        phases:
          expand:                                 # 1: для раскрытия этого типа
            lease: { max-execution-time: 2h }
```

Фазы задач — `process`, `expand`, `collect`; у коммуникаций — `process` и `handle-response`.
Фаза в `yaml` покрывает все методы фазы: у процессора с группами раскрытию отвечают и `expand`,
и `expandBatch`. Аннотация на методе — только свой метод.

**Приоритет задаётся только на уровне типа или системы** — аннотацией на классе или в `yaml`
типа. Приоритет — свойство сущности, а не попытки, поэтому на методе и в фазе он запрещён.
`poll` и `workers` не переопределяются ни по типу, ни аннотацией: своя очередь под тип — это
[выделенный поллер](claiming.md#выделенный-поллер).

**При старте стартер печатает итоговое значение** по каждому типу, системе и методу процессора —
и уровень, откуда оно взято. Ответ на «почему у меня предел тридцать минут» — в этом логе.

## Выключатели

| Свойство                                    | Что выключает                                                                    |
|---------------------------------------------|----------------------------------------------------------------------------------|
| `taskcomm.enabled: false`                   | Всё сразу: поллеры, heartbeat, обслуживание партиций, REST и проверки при старте |
| `taskcomm.task.enabled: false`              | Задачи: их движок, сервис, чтение и REST                                         |
| `taskcomm.communication.enabled: false`     | Коммуникации — так же                                                            |
| `taskcomm.rest.enabled`                     | REST; выключен по умолчанию                                                      |
| `management.health.taskcomm.enabled: false` | Health indicator — обычным свойством Actuator                                    |

Выключенная подсистема не создаёт бинов: `TaskService` в контексте не будет, и код,
который его внедряет, не запустится. Это удобно в тестах слоёв, которым стартер не нужен.

## Задачи

`taskcomm.task.*`:

- **`poll`** — основной поллер: такт опроса пустой очереди, сколько строк захватывать за раз,
  сколько воркеров, форма порядка (`deadline`, `priority`, `quota`), шаг дедлайна, доля FIFO
  у `quota`. Захватывается не больше, чем свободно воркеров: `batch-size` — только верхняя граница.
- **`lease`** — аренда, heartbeat и предел обработки, см. [Задачи](tasks.md#аренда-и-долгие-обработки).
- **`retry`** — повторы: лимит попыток, начальная пауза, множитель, потолок, случайный сдвиг.
- **`types.<тип>`** — переопределения для типа: `priority`, `lease`, `retry`, `phases`.
- **`pollers.<имя>`** — выделенные поллеры: свой набор `types`, свои `workers` и порядок;
  не заданное берётся у основного.
- **`safety`** — подстраховочный поллер, который доводит шаги каскада, брошенные упавшим
  экземпляром.

## Коммуникации

`taskcomm.communication.*` — то же устройство, ключ переопределения — **имя внешней системы**:

- **`poll`**, **`lease`**, **`retry`** — как у задач;
- **`response-timeout`** — сколько ждать асинхронного ответа до повторной отправки;
- **`circuit-breaker`** — глобальные настройки размыкателя, см.
  [Коммуникации](communications.md#размыкатель);
- **`systems.<система>`** — переопределения для системы: `priority`, `lease`, `retry`, `phases`,
  `response-timeout`, `rate-limit-per-instance`, `failure-statuses`, `circuit-breaker`;
- **`pollers.<имя>`** — выделенные поллеры по системам: `systems`, `workers`;
- **`safety`** — подстраховочный поллер истёкших ожиданий ответа.

## Схема и партиции

`taskcomm.schema.*`:

- **`initialize`** — `never` (по умолчанию), `embedded`, `always`. `always` применяет схему при
  старте — для тестов и локальной разработки. `embedded` на PostgreSQL не срабатывает никогда.
- **`partitions`** — один набор на всю схему: `manage` (ведёт ли партиции стартер), `period`
  (`day`, `week`, `month` — **выбирается один раз**), `create-ahead`, `interval`,
  `drop.enabled` и `drop.retention`. Подробно — [База данных](database.md#партиции).

## Результаты и поиск

- **`taskcomm.results.discard`** и **`discard-after`** — когда удаляются результаты детей после
  сборки, см. [Задачи](tasks.md#результаты).
- **`taskcomm.search.remove-undeclared-fields`** — удалять ли при старте поля поиска, которые
  больше никто не объявляет. Выключено: иначе поиск по старым данным перестал бы работать
  от обновления кода.

## REST

`taskcomm.rest.*`: `enabled`, `base-path` и `search-window` — окно чтения истории
по умолчанию, см. [REST API](rest-api.md#поиск-и-окно).

## Наблюдаемость

- **`taskcomm.logging.mdc-prefix`** — префикс ключей MDC (`tc.executionId`, `tc.taskId`…).
- **`taskcomm.health.missed-intervals`** — сколько своих тактов поток поллера или heartbeat может
  молчать, прежде чем health indicator покраснеет.
- **`taskcomm.metrics.interval`** — такт снимка метрик очереди на всю схему.

Подробно — [Эксплуатация](operations.md).

## Проверки при старте

Стартер проверяет настройки при старте и делит ошибки по одному вопросу: способна ли система
работать с этим значением.

**Не запустится**, если значение делает работу невозможной или опасной. Например:
`max-attempts < 1`; `jitter: 0` — случайный сдвиг обязателен; `heartbeat-interval` не меньше
`lease.duration` — аренда истекала бы раньше продления; приоритет на методе или в фазе; один тип
в двух поллерах; размер партиции не совпадает с уже созданными; `DataSource` или менеджер
транзакций не выбран однозначно.

**Предупредит громко**, если значение работает, но, вероятно, ошибочно: `heartbeat-interval`
больше трети аренды; аренда не длиннее такта опроса; фаза в `yaml`, которой нет у контракта
процессора; планировщик с одним потоком.

Сообщение называет свойство, тип или систему, фазу и уровень, откуда пришло значение, и часто —
рекомендуемое значение. Полный перечень проверок — в
[rules/configuration.md](../rules/configuration.md#проверки-настроек-при-старте).

## Полный справочник свойств

Все свойства `taskcomm.*` со значениями по умолчанию. Правила к ним — в [rules/configuration.md](../rules/configuration.md).

```yaml
taskcomm:
  enabled: true
  task:
    enabled: true
    poll:
      interval: 1s
      batch-size: 100
      workers: 8
      claim-order: deadline         # deadline | priority | quota
      deadline-step: 1m
      fifo-share: 0.1               # доля FIFO у quota, строго между 0 и 1
    lease:
      duration: 60s
      heartbeat-interval: 20s
      max-execution-time: 30m
    retry:
      max-attempts: 3
      initial-delay: 5s
      multiplier: 2.0
      max-delay: 1h
      jitter: 0.2
    types:                          # ключ — taskType
      price-update:
        priority: 50                # default 100
        lease:
          max-execution-time: 30m
        retry:
          max-attempts: 5
        phases:                     # process | expand | collect
          expand:
            lease:
              max-execution-time: 2h
    pollers:                        # дополнительные к основному; не заданное берётся у основного
      urgent:
        types: [order-urgent]       # типы задач которые подхватит этот поллер 
        workers: 4
        claim-order: priority       # deadline | priority | quota
    safety:                         # подстраховочный поллер
      interval: 1m
      grace: 1m
  communication:
    enabled: true
    poll:
      interval: 1s
      batch-size: 100
      workers: 8
      claim-order: deadline         # deadline | priority | quota
      deadline-step: 1m
    lease:
      duration: 60s
      heartbeat-interval: 20s
      max-execution-time: 5m
    retry:
      max-attempts: 5
      initial-delay: 10s
      multiplier: 2.0
      max-delay: 1h
      jitter: 0.2
    response-timeout: 1h            # срок ожидания асинхронного ответа, потом повторная отправка
    safety:                         # подстраховочный поллер: истёкшие ожидания ответа
      interval: 1m
    circuit-breaker:                # только исходящие; выключен по умолчанию
      enabled: false
      window: 60s
      minimum-calls: 10
      failure-rate-threshold: 0.5
      wait:
        initial-delay: 30s
        multiplier: 2.0
        max-delay: 10m
        jitter: 0.2
      probe:
        calls: 3
        timeout: 1m
    systems:                        # ключ — имя внешней системы
      supplier-b:
        priority: 50
        rate-limit-per-instance: 10 # отправок исходящих в секунду на экземпляр
        retry:
          max-attempts: 8
        response-timeout: 4h
        failure-statuses: [5xx, 429] # исход попытки, а не только размыкатель
        circuit-breaker:
          enabled: true
          exceptions: [java.io.IOException]
    pollers:                        # дополнительные к основному, по именам внешних систем
      supplier-b:
        systems: [supplier-b]       # source|destination коммуникаций которые подхватит этот поллер
        workers: 2
  search:
    remove-undeclared-fields: false
  results:
    discard: after                  # never | immediately | after
    discard-after: 7d
  logging:
    mdc-prefix: tc.                 # префикс ключей MDC: tc.executionId, tc.taskId, ...
  health:
    missed-intervals: 10            # сколько своих интервалов поток поллера или heartbeat молчит до DOWN
  metrics:
    interval: 30s                   # такт снимка метрик на всю схему
  rest:
    enabled: false                  # выключен по умолчанию: пути надо закрыть самому
    base-path: /api/taskcomm
    search-window: 30d              # окно чтения истории по времени создания дела
  schema:
    initialize: never               # never | embedded | always; для тестов — always
    partitions:                     # один набор на всю схему
      manage: true                  # false — партиции ведёт потребитель, стартер только проверяет
      period: month                 # day | week | month; выбирается один раз
      create-ahead: 3
      interval: 1h
      drop:
        enabled: false
        retention: 180d
```
