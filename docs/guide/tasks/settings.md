# Настройки процессора аннотациями

Автор процессора лучше всех знает профиль своей работы и задаёт умолчания аннотацией;
эксплуатация переопределяет их в `application.yml`.

```java
@TaskPriority(50)
@TaskRetry(maxAttempts = 5)
@Component
public class OrderSyncProcessor implements UnitTaskProcessor<...> {

    @Override
    @TaskLease(maxExecutionTime = "2h")      // раскрытие на миллион заказов идёт долго
    public void expand(...) { ... }

    @Override
    @TaskLease(maxExecutionTime = "1m")
    public OrderOutcome process(...) { ... }
}
```

| Аннотация                | Что задаёт                                                        | Где                   |
|--------------------------|-------------------------------------------------------------------|-----------------------|
| `@TaskPriority`          | Приоритет по умолчанию для типа                                   | только на классе      |
| `@TaskLease`             | `duration`, `heartbeatInterval`, `maxExecutionTime`               | на классе и на методе |
| `@TaskRetry`             | `maxAttempts`, `initialDelay`, `multiplier`, `maxDelay`, `jitter` | на классе и на методе |
| `@TaskProcessorSettings` | Всё сразу                                                         | на классе и на методе |

Длительности — строками в формате Spring Boot: `"30s"`, `"2h"`. Аннотация на методе действует
только на свой метод и перекрывает аннотацию класса. **Любое значение из `yaml` сильнее любой
аннотации** — так эксплуатация во время инцидента может быть уверена, что её настройка
применилась. Полный порядок — в [Настройках](../setup/configuration.md#как-складываются-настройки).
