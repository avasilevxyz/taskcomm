# Захват коммуникаций

Всё то же, со своими отличиями:

- настройки — `taskcomm.communication.poll`; формы захвата те же три;
- приоритет задаётся на **внешней системе** — `systems.<система>.priority` или атрибутом
  `priority` в `@CommunicationProcessorSettings` — и на отдельной отправке —
  `CommunicationSendOptions.defaults().priority(10)`;
- выделенный поллер перечисляет системы, а не типы:

```yaml
taskcomm:
  communication:
    pollers:
      supplier-b: { systems: [supplier-b], workers: 2 }
```

Медленная система с маленьким лимитом скорости тогда не занимает воркеров, нужных остальным.
Разомкнутую [размыкателем](../communications/rate-and-breaker.md#размыкатель) систему поллер не захватывает вовсе:
её коммуникации ждут в очереди, не тратя попыток.
