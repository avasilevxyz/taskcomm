# Быстрый старт

От пустого проекта до выполненной задачи и отправленного сообщения. Нужно знать Spring Boot —
больше ничего.

## Требования

| Что         | Версия                                                |
|-------------|-------------------------------------------------------|
| Java        | 25                                                    |
| Spring Boot | 4.1 (Spring Framework 7, Jackson 3)                   |
| PostgreSQL  | проверено на 17; минимальная версия пока не объявлена |

Другие базы не поддерживаются и не будут: стартер опирается на возможности именно PostgreSQL —
партиционирование таблиц, `SELECT ... FOR UPDATE SKIP LOCKED` для раздачи работы между
экземплярами, advisory-блокировки, `jsonb` для нагрузки. H2 и другие встроенные базы
не подходят даже для тестов — см. [Тестирование](testing.md).

Брокер сообщений, Redis, ZooKeeper и отдельный сервис не нужны: всё состояние живёт в вашей
базе.

## Подключение

Gradle:

```groovy
dependencies {
    implementation platform('xyz.avasilev:taskcomm-bom:2.0.0')
    implementation 'xyz.avasilev:taskcomm-spring-boot-starter'
    runtimeOnly 'org.postgresql:postgresql'
}
```

Maven:

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>xyz.avasilev</groupId>
            <artifactId>taskcomm-bom</artifactId>
            <version>2.0.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>xyz.avasilev</groupId>
        <artifactId>taskcomm-spring-boot-starter</artifactId>
    </dependency>
    <dependency>
        <groupId>org.postgresql</groupId>
        <artifactId>postgresql</artifactId>
        <scope>runtime</scope>
    </dependency>
</dependencies>
```

`taskcomm-bom` необязателен. Он ничего не подтягивает, а только задаёт версии стартера и тех
необязательных зависимостей, которые включают его функции, — их можно писать без версий. Без BOM
укажите версию стартера явно: `taskcomm-spring-boot-starter:2.0.0`.

**Стартер не навязывает вам версии Spring Boot.** Он не экспортирует BOM Spring Boot: подключив
его, вы не получите ни одной зависимости, поднятой до «наших» версий. Версию Spring Boot
по-прежнему определяет ваш проект.

## Что приезжает с зависимостью

Стартер — только набор зависимостей, кода в нём нет. Он тянет:

- `taskcomm-autoconfigure` — весь код стартера;
- `spring-boot-starter`, `spring-boot-starter-jdbc` (с ним `HikariCP` и `JdbcClient`),
  `spring-boot-starter-jackson` (Jackson 3).

Всё остальное — необязательное и включается тем, что уже есть в вашем приложении.

## Необязательные зависимости

Стартер ничего из этого не тянет: функция появляется, когда зависимость уже есть у вас.

| Добавили                                              | Получили                                                                    |
|-------------------------------------------------------|-----------------------------------------------------------------------------|
| `org.postgresql:postgresql`                           | Драйвер. Формально необязателен, на деле нужен всегда                       |
| `io.micrometer:micrometer-core` и бин `MeterRegistry` | Метрики `tc.*` — см. [Эксплуатация](operations.md)                          |
| `spring-boot-starter-actuator`                        | Health indicator `taskcomm`                                                 |
| `spring-boot-starter-webmvc`                          | REST API — если включить его свойством, см. [REST API](rest-api.md)         |
| `spring-boot-starter-security`                        | Бин `taskcommRequestMatcher` с путями REST, чтобы закрыть их своей цепочкой |
| `tools.jackson.dataformat:jackson-dataformat-xml`     | Разбор SOAP по умолчанию — см. [Коммуникации](communications.md)            |
| `org.liquibase:liquibase-core`                        | Применение нашей схемы вашим Liquibase одной строкой — см. ниже             |

## Схема базы данных

**Схему применяете вы.** Стартер не выполняет DDL в рабочей базе — с одним исключением: партиции
таблиц он создаёт сам, иначе вставка в несуществующую партицию остановила бы систему. Таблицы
с префиксом `tc_` создаются в вашей схеме по умолчанию; имя схемы в SQL не записано.

Путей три.

**Liquibase.** Подключите наш changelog одной строкой в свой master:

```xml
<include file="db/changelog/taskcomm/master.xml" relativeToChangelogFile="false"/>
```

Changelog читается прямо из jar стартера, наши changeSet'ы попадают в вашу `DATABASECHANGELOG`.
Обновили версию стартера — новые changeSet'ы применятся при следующем запуске. Подходит
Liquibase 4.x и 5.x: лицензию выбираете вы своей версией.

**Другой инструмент миграций (Flyway и т. п.).** Возьмите файл
`db/changelog/taskcomm/taskcomm-2.0.0.sql` из jar стартера. Это обычный SQL — разметка Liquibase
в нём записана комментариями, — его можно положить в свои миграции под своим номером. Каждая
новая версия стартера, меняющая схему, приносит новый файл; старые не меняются никогда.

**Тесты и локальная разработка.** Свойство `taskcomm.schema.initialize: always` применит схему
при старте приложения. Для рабочей базы оно не предназначено: схему оно создаёт, но не обновляет.

При старте стартер проверяет свои таблицы и колонки. Схема не соответствует версии — приложение
не запустится и скажет, каких миграций не хватает. Подробно — в [Базе данных](database.md).

## Первая задача

Задача — работа, которую надо сделать в фоне, надёжно и с повторами. Опишите, как её выполнить, —
это **процессор**. Самый простой — плоский: задача выполняется целиком, без деления на части.

```java
public record ReportParams(YearMonth month) {}

public record ReportSummary(int rows) {}

@Component
@RequiredArgsConstructor
public class MonthlyReportProcessor implements FlatTaskProcessor<ReportParams, ReportSummary> {

    private final ReportBuilder reports;

    @Override
    public String taskType() {
        return "monthly-report";
    }

    @Override
    public ReportSummary process(Task<ReportParams> task, ProcessingContext context) {
        int rows = reports.build(task.payload().month());
        return new ReportSummary(rows);   // результат сохранится в базе
    }
}
```

Процессор — обычный бин: `@Component` или `@Bean`. Стартер находит все процессоры сам.

Поставьте задачу:

```java
@Service
@RequiredArgsConstructor
public class ReportScheduling {

    private final TaskService taskService;

    @Transactional
    public UUID orderReport(YearMonth month) {
        Task<ReportParams> task = taskService.submit(MonthlyReportProcessor.class, new ReportParams(month));
        return task.id();
    }
}
```

Задача записывается **в вашу транзакцию**: воркер увидит её в момент коммита, а при откате она
исчезнет вместе с вашими данными. После коммита, на ближайшем такте опроса, один из экземпляров
приложения возьмёт её в работу и вызовет `process`. Упадёт — повторит с нарастающей паузой,
сколько раз — задаётся [настройками](configuration.md).

Узнать результат:

```java
Optional<ReportSummary> summary = taskService.result(task, ReportSummary.class);
```

Статус и историю попыток задачи покажут [API чтения](inspection.md) и [REST API](rest-api.md).

## Первая коммуникация

Коммуникация — обмен с внешней системой: отправка запроса или приём сообщения. Процессор
коммуникаций — адаптер к одной внешней системе. Его контракт выбирается по транспорту; для
REST — `RestCommunicationProcessor`.

```java
public record PriceUpdate(String sku, BigDecimal price) {}

public record SupplierAck(String status) {}

@Component
@RequiredArgsConstructor
public class SupplierProcessor implements RestCommunicationProcessor<PriceUpdate, SupplierAck, Void> {

    private final RestClient supplierClient;   // с тайм-аутом — обязательно
    private final JsonMapper json;

    @Override
    public String externalSystem() {
        return "supplier";
    }

    @Override
    public SupplierAck process(Communication<PriceUpdate> communication, CommunicationContext context) {
        return switch (communication) {
            case Communication.Outbound<PriceUpdate> outbound -> send(outbound, context);
            case Communication.Inbound<PriceUpdate> inbound ->
                    throw new UnsupportedOperationException("supplier нам не пишет");
        };
    }

    private SupplierAck send(Communication.Outbound<PriceUpdate> outbound, CommunicationContext context) {
        String body = json.writeValueAsString(outbound.requestPayload());
        context.recordRequest(ExchangeMessage.of(body));
        String answer = supplierClient.post()
                .uri("/prices")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((request, response) -> {
                    String text = response.bodyTo(String.class);
                    context.recordResponse(ExchangeMessage.of(text).withStatus(response.getStatusCode().value()));
                    return text;
                });
        return json.readValue(answer, SupplierAck.class);
    }
}
```

Три вещи в этом примере не случайны:

- **запрос и ответ записываются методами контекста** — так в истории видно, что ушло и что
  вернулось на каждой попытке;
- **ответ записывается со статусом**, даже отказным: ответ `503` стартер сам сочтёт неуспехом
  и повторит отправку, хотя исключения не было;
- **у клиента есть тайм-аут.** Прервать ваш код стартер не может.

Отправьте сообщение:

```java
@Transactional
public void changePrice(String sku, BigDecimal price) {
    catalog.updatePrice(sku, price);
    communicationService.send(SupplierProcessor.class, new PriceUpdate(sku, price));
}
```

Как и задача, коммуникация пишется в вашу транзакцию и уходит после коммита — это outbox без
отдельной таблицы и без отдельного кода.

## Проверить, что всё работает

- **Лог при старте.** Стартер печатает итоговые настройки по каждому типу задачи и каждой внешней
  системе — с уровнем, откуда взято значение. Нет ни одного процессора — громкое
  предупреждение: почти наверняка процессоры не попали в контекст.
- **Таблицы.** В базе появились таблицы `tc_*`, а у основных — партиции вида
  `tc_task_p20261101`.
- **Health.** При Actuator в `/actuator/health` есть компонент `taskcomm` со статусом `UP`.
- **Работа.** После постановки в `tc_task` появилась строка, и через такт-другой опроса её
  `status` стал `SUCCEEDED`.

Не заработало — [Решение проблем](troubleshooting.md).

## Что дальше

- [Понятия](concepts.md) — словарь, без которого не читаются остальные страницы.
- [Задачи](tasks.md) — подзадачи, группы, повторы, отмена, перезапуск.
- [Коммуникации](communications.md) — приём входящих, дедупликация, асинхронный ответ.
- [Настройки](configuration.md) — все свойства `taskcomm.*`.
- [Совместимость](compatibility.md) — что нельзя менять в своём коде, когда задачи уже в базе.
  Прочитайте до первого релиза.
