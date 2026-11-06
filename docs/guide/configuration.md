# Настройки

_Что здесь: все свойства taskcomm.* по подсистемам с назначением и значением по умолчанию, и как настройки складываются._

## Как складываются настройки

_yaml по типу и фазе сильнее аннотации процессора, аннотация сильнее глобальных; что применяется к чему._

## Выключатели

_taskcomm.enabled и enabled подсистем: что выключается._

## Задачи

_taskcomm.task.*: опрос, поллеры, аренда, повторы, подстраховка, настройки по типам и фазам._

## Коммуникации

_taskcomm.communication.*: опрос, поллеры, аренда, повторы, размыкатель, скорость, настройки по системам и фазам._

## Схема и партиции

_taskcomm.schema.*: initialize, управление партициями, период, запас, удаление._

## Результаты и поиск

_taskcomm.results.*, taskcomm.search.*._

## REST

_taskcomm.rest.*._

## Наблюдаемость

_taskcomm.logging.*, taskcomm.health.*, taskcomm.metrics.*._

## Проверки при старте

_что валит старт и что только предупреждает — со списком сообщений._

## Полный справочник свойств

_Все свойства `taskcomm.*` со значениями по умолчанию. Правила к ним — в [rules/configuration.md](../rules/configuration.md)._

```yaml
taskcomm:
  enabled: true
  task:
    enabled: true
    poll: { interval: 1s, batch-size: 100, workers: 8,
            claim-order: deadline, deadline-step: 1m,   # deadline | priority | quota
            fifo-share: 0.1 }               # доля FIFO у quota, строго между 0 и 1
    lease: { duration: 60s, heartbeat-interval: 20s, max-execution-time: 30m }
    retry: { max-attempts: 3, initial-delay: 5s, multiplier: 2.0, max-delay: 1h, jitter: 0.2 }
    types:                          # ключ — taskType
      price-update:
        priority: 50
        lease: { max-execution-time: 30m }
        retry: { max-attempts: 5 }
        phases:                     # PROCESS | EXPAND | COLLECT
          expand:
            lease: { max-execution-time: 2h }
    pollers:                        # дополнительные к основному; не заданное берётся у основного
      urgent: { types: [order-urgent], workers: 4, claim-order: priority }
    safety: { interval: 1m, grace: 1m }   # подстраховочный поллер
  communication:
    enabled: true
    poll: { interval: 1s, batch-size: 100, workers: 8,
            claim-order: deadline, deadline-step: 1m }   # deadline | priority | quota
    lease: { duration: 60s, heartbeat-interval: 20s, max-execution-time: 5m }
    retry: { max-attempts: 5, initial-delay: 10s, multiplier: 2.0, max-delay: 1h, jitter: 0.2 }
    response-timeout: 1h            # срок ожидания асинхронного ответа, потом повторная отправка
    safety: { interval: 1m }        # подстраховочный поллер: истёкшие ожидания ответа
    circuit-breaker:                # только исходящие; выключен по умолчанию
      enabled: false
      window: 60s
      minimum-calls: 10
      failure-rate-threshold: 0.5
      wait: { initial-delay: 30s, multiplier: 2.0, max-delay: 10m, jitter: 0.2 }
      probe: { calls: 3, timeout: 1m }
    systems:                        # ключ — имя внешней системы
      supplier-b:
        priority: 50
        rate-limit-per-instance: 10      # отправок исходящих в секунду на экземпляр
        retry: { max-attempts: 8 }
        response-timeout: 4h
        failure-statuses: [5xx, 429]  # исход попытки, а не только размыкатель
        circuit-breaker: { enabled: true, exceptions: [java.io.IOException] }
    pollers:                        # дополнительные к основному, по именам внешних систем
      supplier-b: { systems: [supplier-b], workers: 2 }
  search:
    remove-undeclared-fields: false
  results:
    discard: after   # never | immediately | after
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
      drop: { enabled: false, retention: 180d }
```
