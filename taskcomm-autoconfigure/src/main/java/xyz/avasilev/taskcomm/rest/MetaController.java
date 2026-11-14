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
import xyz.avasilev.taskcomm.communication.api.processor.CommunicationProcessor;
import xyz.avasilev.taskcomm.communication.api.processor.KafkaCommunicationProcessor;
import xyz.avasilev.taskcomm.communication.api.processor.RawCommunicationProcessor;
import xyz.avasilev.taskcomm.communication.api.processor.RestCommunicationProcessor;
import xyz.avasilev.taskcomm.communication.api.processor.SoapCommunicationProcessor;
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
        final Map<String, Integer> taskPriorities = tasks == null ? Map.of() : tasks.defaultPriorities();
        final Map<String, String> taskPollers = tasks == null ? Map.of() : tasks.pollers();
        final Map<String, Integer> systemPriorities =
                communications == null ? Map.of() : communications.defaultPriorities();
        final Map<String, String> systemPollers = communications == null ? Map.of() : communications.pollers();
        this.meta = new RestDtos.MetaDto(
                version,
                // То же выражение, что у движков: оно стоит в locked_by попыток (docs/adr/0154-ui-shell.md).
                ManagementFactory.getRuntimeMXBean().getName(),
                searchWindow.toString(),
                taskProcessors == null || tasks == null
                        ? null
                        : new RestDtos.TasksMetaDto(taskProcessors.stream()
                                .map(processor -> new RestDtos.TaskTypeDto(
                                        processor.taskType(),
                                        titleOr(processor.title(), processor.taskType()),
                                        blankToNull(processor.description()),
                                        new RestDtos.TitleTemplatesDto(
                                                blankToNull(processor.taskTitle()),
                                                blankToNull(batchTitle(processor)),
                                                blankToNull(unitTitle(processor))),
                                        contract(processor),
                                        taskPriorities.get(processor.taskType()),
                                        taskPollers.get(processor.taskType())))
                                .sorted(Comparator.comparing(RestDtos.TaskTypeDto::type))
                                .toList()),
                communicationProcessors == null || communications == null
                        ? null
                        : new RestDtos.CommunicationsMetaDto(
                                communicationProcessors.stream()
                                        .map(processor -> new RestDtos.ExternalSystemDto(
                                                processor.externalSystem(),
                                                titleOr(processor.title(), processor.externalSystem()),
                                                blankToNull(processor.description()),
                                                transport(processor),
                                                systemPriorities.get(processor.externalSystem()),
                                                systemPollers.get(processor.externalSystem())))
                                        .sorted(Comparator.comparing(RestDtos.ExternalSystemDto::system))
                                        .toList(),
                                manualInbound));
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
