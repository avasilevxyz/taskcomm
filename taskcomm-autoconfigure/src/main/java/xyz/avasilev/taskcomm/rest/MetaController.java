package xyz.avasilev.taskcomm.rest;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import xyz.avasilev.taskcomm.communication.api.processor.CommunicationProcessor;
import xyz.avasilev.taskcomm.communication.api.processor.KafkaCommunicationProcessor;
import xyz.avasilev.taskcomm.communication.api.processor.RawCommunicationProcessor;
import xyz.avasilev.taskcomm.communication.api.processor.RestCommunicationProcessor;
import xyz.avasilev.taskcomm.communication.api.processor.SoapCommunicationProcessor;
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
     */
    public MetaController(
            final String version,
            final Duration searchWindow,
            final List<TaskProcessor<?>> taskProcessors,
            final List<CommunicationProcessor<?, ?, ?>> communicationProcessors) {
        this.meta = new RestDtos.MetaDto(
                version,
                searchWindow.toString(),
                taskProcessors == null
                        ? null
                        : new RestDtos.TasksMetaDto(taskProcessors.stream()
                                .map(processor -> new RestDtos.TaskTypeDto(processor.taskType(), contract(processor)))
                                .sorted(Comparator.comparing(RestDtos.TaskTypeDto::type))
                                .toList()),
                communicationProcessors == null
                        ? null
                        : new RestDtos.CommunicationsMetaDto(communicationProcessors.stream()
                                .map(processor -> new RestDtos.ExternalSystemDto(
                                        processor.externalSystem(), transport(processor)))
                                .sorted(Comparator.comparing(RestDtos.ExternalSystemDto::system))
                                .toList()));
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

    /** Транспорт — разбор запечатанной иерархии без {@code default}. */
    private static String transport(final CommunicationProcessor<?, ?, ?> processor) {
        return switch (processor) {
            case RestCommunicationProcessor<?, ?, ?> _ -> "REST";
            case SoapCommunicationProcessor<?, ?, ?> _ -> "SOAP";
            case KafkaCommunicationProcessor<?, ?, ?> _ -> "KAFKA";
            case RawCommunicationProcessor<?, ?, ?> _ -> "RAW";
        };
    }
}
