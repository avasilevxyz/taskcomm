# Зависимости

Стартер — только набор зависимостей, кода в нём нет. Он тянет:

- `taskcomm-autoconfigure` — весь код стартера;
- `spring-boot-starter`, `spring-boot-starter-jdbc` (с ним `HikariCP` и `JdbcClient`),
  `spring-boot-starter-jackson` (Jackson 3).

Всё остальное — необязательное и включается тем, что уже есть в вашем приложении.

## Репозиторий

Артефакты лежат в Maven Central: хватает `mavenCentral()` в Gradle, а в Maven ничего
объявлять не нужно.

### GitHub Packages

Те же выпуски публикуются в GitHub Packages — на случай, если Central вам недоступен. Он требует
токен даже для чтения публичного пакета: заведите personal access token (classic) с правом
`read:packages` и передайте его сборке через свойства или окружение — не в файлах репозитория.

Gradle:

```groovy
repositories {
    mavenCentral()
    maven {
        url = 'https://maven.pkg.github.com/avasilevxyz/taskcomm'
        credentials {
            username = providers.gradleProperty('gpr.user').orElse(providers.environmentVariable('GITHUB_ACTOR')).get()
            password = providers.gradleProperty('gpr.key').orElse(providers.environmentVariable('GITHUB_TOKEN')).get()
        }
    }
}
```

`gpr.user` и `gpr.key` — в `~/.gradle/gradle.properties`. В GitHub Actions своего репозитория
хватит встроенного `GITHUB_TOKEN` с правом `packages: read`, если пакет доступен этому
репозиторию.

Maven — репозиторий в `pom.xml`, токен — в `~/.m2/settings.xml` под тем же `id`:

```xml
<repositories>
    <repository>
        <id>taskcomm</id>
        <url>https://maven.pkg.github.com/avasilevxyz/taskcomm</url>
    </repository>
</repositories>
```

```xml
<servers>
    <server>
        <id>taskcomm</id>
        <username>ваш-логин-github</username>
        <password>токен с read:packages</password>
    </server>
</servers>
```

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
