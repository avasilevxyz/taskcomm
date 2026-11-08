# Зависимости

Стартер — только набор зависимостей, кода в нём нет. Он тянет:

- `taskcomm-autoconfigure` — весь код стартера;
- `spring-boot-starter`, `spring-boot-starter-jdbc` (с ним `HikariCP` и `JdbcClient`),
  `spring-boot-starter-jackson` (Jackson 3).

Всё остальное — необязательное и включается тем, что уже есть в вашем приложении.

## Необязательные зависимости

Стартер ничего из этого не тянет: функция появляется, когда зависимость уже есть у вас.

| Добавили                                              | Получили                                                                                                                         |
|-------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------|
| `org.postgresql:postgresql`                           | Драйвер. Формально необязателен, на деле нужен всегда                                                                            |
| `io.micrometer:micrometer-core` и бин `MeterRegistry` | Метрики `tc.*` — см. [Эксплуатация](../operations/README.md)                                                                     |
| `spring-boot-starter-actuator`                        | Health indicator `taskcomm`                                                                                                      |
| `spring-boot-starter-webmvc`                          | REST API — если включить его свойством, см. [REST API](../api/rest.md)                                                           |
| `spring-boot-starter-security`                        | Бин `taskcommRequestMatcher` с путями REST, чтобы закрыть их своей цепочкой                                                      |
| `tools.jackson.dataformat:jackson-dataformat-xml`     | Разбор SOAP по умолчанию — см. [Коммуникации](../communications/README.md)                                                       |
| `spring-boot-starter-liquibase`                       | Ваш Liquibase применяет нашу схему — см. [Быстрый старт](../start/quick-start.md#схема-базы-данных). Одной `liquibase-core` мало |
