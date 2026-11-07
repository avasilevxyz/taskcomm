# Настройки процессора аннотацией

```java
@CommunicationProcessorSettings(
        maxAttempts = 8,
        responseTimeout = "4h",
        rateLimitPerInstance = 10,
        failureStatuses = {"5xx", "429"},
        circuitBreakerExceptions = {IOException.class})
@Component
public class SupplierProcessor implements RestCommunicationProcessor<...> { ... }
```

Атрибуты те же, что у задач (приоритет, аренда, повторы), плюс `responseTimeout`,
`failureStatuses`, `rateLimitPerInstance` и `circuitBreakerExceptions`. Приоритет и отказные
статусы — свойства системы: на методе их задавать нельзя. Как у задач, **любое значение из `yaml`
сильнее аннотации** — см. [Настройки](../setup/configuration.md#как-складываются-настройки).
