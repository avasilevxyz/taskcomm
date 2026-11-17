package xyz.avasilev.taskcomm.rest;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import xyz.avasilev.taskcomm.communication.api.inspection.CommunicationInspection;
import xyz.avasilev.taskcomm.communication.api.inspection.CommunicationLimits;
import xyz.avasilev.taskcomm.communication.api.processor.CommunicationProcessor;
import xyz.avasilev.taskcomm.communication.api.processor.KafkaCommunicationProcessor;
import xyz.avasilev.taskcomm.communication.api.processor.RawCommunicationProcessor;
import xyz.avasilev.taskcomm.communication.api.processor.RestCommunicationProcessor;
import xyz.avasilev.taskcomm.communication.api.processor.SoapCommunicationProcessor;
import xyz.avasilev.taskcomm.task.api.inspection.TaskAttemptLimit;
import xyz.avasilev.taskcomm.task.api.inspection.TaskInspection;
import xyz.avasilev.taskcomm.task.api.processor.BatchTaskProcessor;
import xyz.avasilev.taskcomm.task.api.processor.FlatTaskProcessor;
import xyz.avasilev.taskcomm.task.api.processor.TaskProcessor;
import xyz.avasilev.taskcomm.task.api.processor.UnitTaskProcessor;

/**
 * Описание экземпляра для интерфейса (docs/adr/0150-execution-outline-and-meta.md): версия стартера, окно
 * чтения истории по умолчанию, типы задач и внешние системы, у которых есть процессор. Выключенной
 * подсистемы в выдаче нет. Выдача собирается один раз: процессоры в работе не меняются.
 */
@RestController
@RequestMapping("${taskcomm.rest.base-path:/tc/api}/meta")
public class MetaController {

    private final RestDtos.MetaDto meta;

    /**
     * Собирает контроллер.
     *
     * @param version версия стартера или {@code null}
     * @param searchWindow окно чтения истории по умолчанию
     * @param taskProcessors процессоры задач или {@code null}, если подсистема задач выключена
     * @param communicationProcessors процессоры коммуникаций или {@code null}, если подсистема выключена
     * @param tasks чтение задач — приоритеты и поллеры типов; {@code null}, если подсистема выключена
     * @param communications чтение коммуникаций — приоритеты и поллеры систем; {@code null}, если выключена
     * @param manualInbound включён ли ручной приём входящих
     */
    public MetaController(
            final String version,
            final Duration searchWindow,
            final List<TaskProcessor<?>> taskProcessors,
            final List<CommunicationProcessor<?, ?, ?>> communicationProcessors,
            final TaskInspection tasks,
            final CommunicationInspection communications,
            final boolean manualInbound) {
        this.meta = new RestDtos.MetaDto(
                version,
                // То же выражение, что у движков: оно стоит в locked_by попыток (docs/adr/0154-ui-shell.md).
                ManagementFactory.getRuntimeMXBean().getName(),
                searchWindow.toString(),
                taskProcessors == null || tasks == null ? null : tasksMeta(taskProcessors, tasks),
                communicationProcessors == null || communications == null
                        ? null
                        : communicationsMeta(communicationProcessors, communications, manualInbound));
    }

    /** Типы задач с процессором — подсистема задач включена. */
    private static RestDtos.TasksMetaDto tasksMeta(
            final List<TaskProcessor<?>> processors, final TaskInspection tasks) {
        final Map<String, Integer> priorities = tasks.defaultPriorities();
        final Map<String, String> pollers = tasks.pollers();
        final Map<String, List<TaskAttemptLimit>> limits = tasks.attemptLimits();
        return new RestDtos.TasksMetaDto(processors.stream()
                .map(processor -> new RestDtos.TaskTypeDto(
                        processor.taskType(),
                        titleOr(processor.title(), processor.taskType()),
                        blankToNull(processor.description()),
                        new RestDtos.TitleTemplatesDto(
                                blankToNull(processor.taskTitle()),
                                blankToNull(batchTitle(processor)),
                                blankToNull(unitTitle(processor))),
                        contract(processor),
                        priorities.get(processor.taskType()),
                        pollers.get(processor.taskType()),
                        limits.getOrDefault(processor.taskType(), List.of()).stream()
                                .map(limit -> new RestDtos.AttemptLimitDto(
                                        limit.level().name(),
                                        limit.phase(),
                                        limit.maxExecutionTime().toString()))
                                .toList()))
                .sorted(Comparator.comparing(RestDtos.TaskTypeDto::type))
                .toList());
    }

    /** Внешние системы с процессором — подсистема коммуникаций включена. */
    private static RestDtos.CommunicationsMetaDto communicationsMeta(
            final List<CommunicationProcessor<?, ?, ?>> processors,
            final CommunicationInspection communications,
            final boolean manualInbound) {
        final Map<String, Integer> priorities = communications.defaultPriorities();
        final Map<String, String> pollers = communications.pollers();
        final Map<String, CommunicationLimits> limits = communications.limits();
        return new RestDtos.CommunicationsMetaDto(
                processors.stream()
                        .map(processor -> new RestDtos.ExternalSystemDto(
                                processor.externalSystem(),
                                titleOr(processor.title(), processor.externalSystem()),
                                blankToNull(processor.description()),
                                transport(processor),
                                priorities.get(processor.externalSystem()),
                                pollers.get(processor.externalSystem()),
                                systemLimits(limits.get(processor.externalSystem())),
                                responseTimeout(limits.get(processor.externalSystem()))))
                        .sorted(Comparator.comparing(RestDtos.ExternalSystemDto::system))
                        .toList(),
                manualInbound);
    }

    /**
     * Описание экземпляра.
     *
     * @return описание
     */
    @GetMapping
    public RestDtos.MetaDto meta() {
        return this.meta;
    }

    /** Пределы попыток системы по фазам; реализация без настроек — пусто. */
    private static List<RestDtos.AttemptLimitDto> systemLimits(final CommunicationLimits limits) {
        return limits == null
                ? List.of()
                : limits.maxExecutionTimes().entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .map(entry -> new RestDtos.AttemptLimitDto(
                                null, entry.getKey(), entry.getValue().toString()))
                        .toList();
    }

    private static String responseTimeout(final CommunicationLimits limits) {
        return limits == null ? null : limits.responseTimeout().toString();
    }

    /** Форма контракта — разбор запечатанной иерархии без {@code default}. */
    private static String contract(final TaskProcessor<?> processor) {
        return switch (processor) {
            case FlatTaskProcessor<?, ?> _ -> "FLAT";
            case UnitTaskProcessor<?, ?, ?, ?> _ -> "UNIT";
            case BatchTaskProcessor<?, ?, ?, ?, ?, ?> _ -> "BATCH";
        };
    }

    /** Шаблон названия группы — только у контракта с группами. */
    private static String batchTitle(final TaskProcessor<?> processor) {
        return processor instanceof BatchTaskProcessor<?, ?, ?, ?, ?, ?> batch ? batch.batchTitle() : null;
    }

    /** Шаблон названия подзадачи — у контрактов с подзадачами. */
    private static String unitTitle(final TaskProcessor<?> processor) {
        return switch (processor) {
            case FlatTaskProcessor<?, ?> _ -> null;
            case UnitTaskProcessor<?, ?, ?, ?> unit -> unit.unitTitle();
            case BatchTaskProcessor<?, ?, ?, ?, ?, ?> batch -> batch.unitTitle();
        };
    }

    /** Транспорт — разбор запечатанной иерархии без {@code default}. */
    private static String transport(final CommunicationProcessor<?, ?, ?> processor) {
        return switch (processor) {
            case RestCommunicationProcessor<?, ?, ?> _ -> "REST";
            case SoapCommunicationProcessor<?, ?, ?> _ -> "SOAP";
            case KafkaCommunicationProcessor<?, ?, ?> _ -> "KAFKA";
            case RawCommunicationProcessor<?, ?, ?> _ -> "RAW";
        };
    }

    /**
     * Название процессора, а без своего — его ключ: процессор вправе вернуть пустое
     * (docs/adr/0159-processor-title-and-description.md).
     */
    private static String titleOr(final String title, final String key) {
        return title == null || title.isBlank() ? key : title;
    }

    private static String blankToNull(final String text) {
        return text == null || text.isBlank() ? null : text;
    }
}
