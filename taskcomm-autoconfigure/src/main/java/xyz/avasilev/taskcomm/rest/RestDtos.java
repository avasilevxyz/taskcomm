package xyz.avasilev.taskcomm.rest;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonRawValue;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import xyz.avasilev.taskcomm.PayloadShape;
import xyz.avasilev.taskcomm.communication.api.CommunicationStatus;
import xyz.avasilev.taskcomm.communication.api.inspection.CommunicationSnapshot;
import xyz.avasilev.taskcomm.communication.api.inspection.CorrelationKeySnapshot;
import xyz.avasilev.taskcomm.communication.api.inspection.DedupKeySnapshot;
import xyz.avasilev.taskcomm.execution.api.Origin;
import xyz.avasilev.taskcomm.task.api.inspection.TaskSnapshot;
import xyz.avasilev.taskcomm.task.api.inspection.TaskUnitCount;

/**
 * Формы ответов REST (docs/adr/0140-rest-controllers.md). Нагрузки и результаты — сырой JSON, вставленный
 * в ответ как есть; пустые поля в ответ не попадают.
 */
public final class RestDtos {

    private RestDtos() {}

    /**
     * Страница списка.
     *
     * @param items строки
     * @param next курсор следующей страницы или {@code null} — дальше нет
     * @param window окно, в котором искали
     * @param <EItem> строка
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PageDto<EItem>(List<EItem> items, String next, WindowDto window) {}

    /**
     * Окно чтения истории — по времени создания дела.
     *
     * @param from начало или {@code null}
     * @param to конец, не включая, или {@code null}
     * @param everywhere искали ли во всех партициях
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WindowDto(Instant from, Instant to, boolean everywhere) {}

    /**
     * Дело.
     *
     * @param id идентификатор
     * @param createdAt время создания — ключ партиционирования
     */
    public record ExecutionRefDto(UUID id, Instant createdAt) {}

    /**
     * Родители сущности задач.
     *
     * @param taskId задача или {@code null}
     * @param batchId группа или {@code null}
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ParentsDto(UUID taskId, UUID batchId) {}

    /**
     * Состояние сущности задач.
     *
     * @param status статус
     * @param statusDescription причина или {@code null}
     * @param phase фаза или {@code null}
     * @param priority приоритет
     * @param createdAt время создания
     * @param updatedAt время изменения
     * @param orphan у типа нет процессора
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TaskStateDto(
            String status,
            String statusDescription,
            String phase,
            int priority,
            Instant createdAt,
            Instant updatedAt,
            boolean orphan) {}

    /**
     * Задача, группа или подзадача.
     *
     * @param id идентификатор
     * @param level уровень
     * @param taskType тип задачи
     * @param execution дело
     * @param parents родители
     * @param state состояние
     * @param origin создатель или {@code null}
     * @param payload нагрузка
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TaskDto(
            UUID id,
            TaskSnapshot.Level level,
            String taskType,
            ExecutionRefDto execution,
            ParentsDto parents,
            TaskStateDto state,
            Origin origin,
            @JsonRawValue String payload) {}

    /**
     * Исход попытки.
     *
     * @param status исход или {@code null} — попытка не закрыта
     * @param statusDescription причина или {@code null}
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OutcomeDto(String status, String statusDescription) {}

    /**
     * Начало и конец попытки.
     *
     * @param startedAt начало или {@code null}
     * @param finishedAt конец или {@code null}
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TimesDto(Instant startedAt, Instant finishedAt) {}

    /**
     * Попытка задачи.
     *
     * @param id идентификатор
     * @param phase фаза
     * @param outcome исход
     * @param times начало и конец
     * @param lockedBy экземпляр
     * @param leaseRenewals продления аренды
     * @param result результат или {@code null}
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TaskAttemptDto(
            UUID id,
            String phase,
            OutcomeDto outcome,
            TimesDto times,
            String lockedBy,
            int leaseRenewals,
            @JsonRawValue String result) {}

    /**
     * Состояние коммуникации.
     *
     * @param status статус
     * @param statusDescription причина или {@code null}
     * @param phase фаза
     * @param contractShape форма контракта или {@code null}
     * @param priority приоритет
     * @param createdAt время создания
     * @param updatedAt время изменения
     * @param orphan у системы нет процессора
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CommunicationStateDto(
            CommunicationStatus status,
            String statusDescription,
            String phase,
            String contractShape,
            int priority,
            Instant createdAt,
            Instant updatedAt,
            boolean orphan) {}

    /**
     * Коммуникация.
     *
     * @param id идентификатор
     * @param direction направление
     * @param externalSystem внешняя система
     * @param execution дело
     * @param state состояние
     * @param origin создатель или {@code null}
     * @param requestPayload запрос или {@code null}
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CommunicationDto(
            UUID id,
            CommunicationSnapshot.Direction direction,
            String externalSystem,
            ExecutionRefDto execution,
            CommunicationStateDto state,
            Origin origin,
            @JsonRawValue String requestPayload) {}

    /**
     * Сообщение обмена — как есть.
     *
     * @param body сырое тело
     * @param headers заголовки
     * @param id идентификатор сообщения или {@code null}
     * @param at время сообщения или {@code null}
     * @param status статус ответа или {@code null}
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ExchangeMessageDto(
            String body, Map<String, List<String>> headers, String id, Instant at, Integer status) {}

    /**
     * Обмен попытки коммуникации и доменные формы.
     *
     * @param request что ушло или пришло или {@code null}
     * @param response что вернулось или {@code null}
     * @param callbackPayload доменная форма колбэка или {@code null}
     * @param responsePayload доменная форма ответа или {@code null}
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ExchangeDto(
            ExchangeMessageDto request,
            ExchangeMessageDto response,
            @JsonRawValue String callbackPayload,
            @JsonRawValue String responsePayload) {}

    /**
     * Попытка коммуникации.
     *
     * @param id идентификатор
     * @param phase фаза
     * @param outcome исход
     * @param times начало и конец
     * @param lockedBy экземпляр или {@code null}
     * @param exchange обмен
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CommunicationAttemptDto(
            UUID id, String phase, OutcomeDto outcome, TimesDto times, String lockedBy, ExchangeDto exchange) {}

    /**
     * Дело целиком: все сущности в порядке создания, у каждой — её создатель.
     *
     * @param execution дело
     * @param tasks задачи
     * @param batches группы
     * @param units подзадачи
     * @param communications коммуникации
     * @param correlationKeys ключи корреляции
     */
    public record ExecutionDto(
            ExecutionRefDto execution,
            List<TaskDto> tasks,
            List<TaskDto> batches,
            List<TaskDto> units,
            List<CommunicationDto> communications,
            List<CorrelationKeySnapshot> correlationKeys) {}

    /**
     * Дело без подзадач (docs/adr/0150-execution-outline-and-meta.md): сущности в порядке создания
     * и счётчики подзадач по родителю и статусу.
     *
     * @param execution дело
     * @param tasks задачи
     * @param batches группы
     * @param unitCounts счётчики подзадач
     * @param communications коммуникации
     * @param correlationKeys ключи корреляции
     */
    public record ExecutionOutlineDto(
            ExecutionRefDto execution,
            List<TaskDto> tasks,
            List<TaskDto> batches,
            List<TaskUnitCount> unitCounts,
            List<CommunicationDto> communications,
            List<CorrelationKeySnapshot> correlationKeys) {}

    /**
     * Описание экземпляра для интерфейса (docs/adr/0150-execution-outline-and-meta.md). Выключенной
     * подсистемы в выдаче нет.
     *
     * @param version версия стартера или {@code null} — запуск не из jar
     * @param instance имя ответившего экземпляра — то же, что в {@code locked_by} попыток
     * @param searchWindow окно чтения истории по умолчанию, ISO-8601
     * @param tasks подсистема задач или {@code null} — выключена
     * @param communications подсистема коммуникаций или {@code null} — выключена
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MetaDto(
            String version,
            String instance,
            String searchWindow,
            TasksMetaDto tasks,
            CommunicationsMetaDto communications) {}

    /**
     * Типы задач, у которых есть процессор.
     *
     * @param types типы по имени
     */
    public record TasksMetaDto(List<TaskTypeDto> types) {}

    /**
     * Тип задачи.
     *
     * @param type имя типа
     * @param title название типа для человека; без своего — имя типа
     * @param description описание типа или {@code null}
     * @param titleTemplates шаблоны названия одной задачи, группы и подзадачи
     *     (docs/adr/0162-task-title-template.md)
     * @param contract форма контракта: {@code FLAT}, {@code UNIT} или {@code BATCH}
     * @param defaultPriority приоритет по умолчанию — итог лестницы настроек
     * @param poller поллер, который захватывает работу типа на ответившем экземпляре
     */
    public record TaskTypeDto(
            String type,
            String title,
            String description,
            TitleTemplatesDto titleTemplates,
            String contract,
            Integer defaultPriority,
            String poller) {}

    /**
     * Шаблоны названия одной сущности по уровням: подстановка {@code {имя}} — поле поиска своего
     * уровня (docs/adr/0162-task-title-template.md). Уровня нет у контракта или шаблон не задан —
     * поля нет в ответе.
     *
     * @param task шаблон названия задачи
     * @param batch шаблон названия группы
     * @param unit шаблон названия подзадачи
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TitleTemplatesDto(String task, String batch, String unit) {}

    /**
     * Внешние системы, у которых есть процессор.
     *
     * @param systems системы по имени
     * @param manualInbound включён ли ручной приём входящих (docs/adr/0153-ui-creation-refinements.md)
     */
    public record CommunicationsMetaDto(List<ExternalSystemDto> systems, boolean manualInbound) {}

    /**
     * Внешняя система.
     *
     * @param system имя системы
     * @param title название системы для человека; без своего — имя системы
     * @param description описание системы или {@code null}
     * @param transport транспорт: {@code REST}, {@code SOAP}, {@code KAFKA} или {@code RAW}
     * @param defaultPriority приоритет по умолчанию — итог лестницы настроек
     * @param poller поллер, который захватывает коммуникации системы на ответившем экземпляре
     */
    public record ExternalSystemDto(
            String system,
            String title,
            String description,
            String transport,
            Integer defaultPriority,
            String poller) {}

    /**
     * Число.
     *
     * @param count число сущностей
     */
    public record CountDto(long count) {}

    /**
     * Запрос чтения для разбора — с подставленными значениями (docs/adr/0165-read-sql.md).
     *
     * @param sql текст для psql: один запрос или несколько, каждый со своим заголовком
     */
    public record SqlDto(String sql) {}

    /**
     * Ключи коммуникации.
     *
     * @param dedup ключи дедупликации
     * @param correlation ключи корреляции, для которых она якорь
     */
    public record KeysDto(List<DedupKeySnapshot> dedup, List<CorrelationKeySnapshot> correlation) {}

    /**
     * Исход отмены или перезапуска.
     *
     * @param accepted принят ли
     * @param reason причина
     */
    public record ResultDto(boolean accepted, String reason) {}

    /**
     * Созданная сущность.
     *
     * @param id идентификатор
     * @param execution дело
     * @param status статус
     * @param duplicateOf совпавшая коммуникация — у дубликата, иначе {@code null}
     * @param reason почему входящая сразу {@code FAILED} — у неразбираемого тела, иначе {@code null}
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CreatedDto(UUID id, ExecutionRefDto execution, String status, UUID duplicateOf, String reason) {}

    /**
     * Ручное удаление результатов.
     *
     * @param parents сколько родителей отмечено
     */
    public record DiscardDto(int parents) {}

    /**
     * Ошибка запроса.
     *
     * @param error что не так
     * @param knownFields известные поля поиска — у отказа по необъявленному полю, иначе {@code null}
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorDto(String error, List<String> knownFields) {}

    /**
     * Структура JSON нагрузки для формы создания (docs/adr/0160-payload-shape.md). Пустые части
     * в ответ не попадают.
     *
     * @param kind вид значения
     * @param properties ключи объекта в порядке сериализации
     * @param items элемент массива или значение отображения
     * @param values допустимые значения перечисления
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record PayloadShapeDto(
            String kind, Map<String, PayloadShapeDto> properties, PayloadShapeDto items, List<String> values) {

        /**
         * Из структуры API.
         *
         * @param shape структура
         * @return то же для ответа
         */
        public static PayloadShapeDto of(final PayloadShape shape) {
            final Map<String, PayloadShapeDto> properties = new LinkedHashMap<>();
            shape.properties().forEach((name, value) -> properties.put(name, of(value)));
            return new PayloadShapeDto(
                    shape.kind().name(), properties, shape.items() == null ? null : of(shape.items()), shape.values());
        }
    }
}
