package xyz.avasilev.taskcomm.rest;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;
import xyz.avasilev.taskcomm.communication.api.ExchangeMessage;
import xyz.avasilev.taskcomm.communication.api.inspection.CommunicationAttemptSnapshot;
import xyz.avasilev.taskcomm.communication.api.inspection.CommunicationSnapshot;
import xyz.avasilev.taskcomm.execution.api.ExecutionRef;
import xyz.avasilev.taskcomm.execution.api.HistoryWindow;
import xyz.avasilev.taskcomm.task.api.inspection.TaskAttemptSnapshot;
import xyz.avasilev.taskcomm.task.api.inspection.TaskSnapshot;

/**
 * Снимки API чтения в формы ответов REST (docs/adr/0140-rest-controllers.md). Снимки — интерфейсы с
 * методами вида {@code id()}, а MapStruct видит свойства только у JavaBean-геттеров и записей, поэтому
 * каждое поле названо выражением.
 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface RestMapper {

    /**
     * Задача, группа или подзадача.
     *
     * @param s снимок
     * @return форма ответа
     */
    @Mapping(target = "id", expression = "java(s.id())")
    @Mapping(target = "level", expression = "java(s.level())")
    @Mapping(target = "taskType", expression = "java(s.taskType())")
    @Mapping(target = "execution", expression = "java(execution(s.executionRef()))")
    @Mapping(target = "parents", expression = "java(parents(s))")
    @Mapping(target = "state", expression = "java(taskState(s))")
    @Mapping(target = "origin", expression = "java(s.origin().orElse(null))")
    @Mapping(target = "payload", expression = "java(s.payload())")
    RestDtos.TaskDto task(TaskSnapshot s);

    /**
     * Родители.
     *
     * @param s снимок
     * @return родители
     */
    @Mapping(target = "taskId", expression = "java(s.taskId().orElse(null))")
    @Mapping(target = "batchId", expression = "java(s.batchId().orElse(null))")
    RestDtos.ParentsDto parents(TaskSnapshot s);

    /**
     * Состояние.
     *
     * @param s снимок
     * @return состояние
     */
    @Mapping(target = "status", expression = "java(s.status())")
    @Mapping(target = "statusDescription", expression = "java(s.statusDescription().orElse(null))")
    @Mapping(target = "phase", expression = "java(s.phase().orElse(null))")
    @Mapping(target = "priority", expression = "java(s.priority())")
    @Mapping(target = "createdAt", expression = "java(s.createdAt())")
    @Mapping(target = "updatedAt", expression = "java(s.updatedAt())")
    @Mapping(target = "orphan", expression = "java(s.orphan())")
    RestDtos.TaskStateDto taskState(TaskSnapshot s);

    /**
     * Попытка задачи.
     *
     * @param s снимок
     * @return форма ответа
     */
    @Mapping(target = "id", expression = "java(s.id())")
    @Mapping(target = "phase", expression = "java(s.phase())")
    @Mapping(
            target = "outcome",
            expression = "java(new RestDtos.OutcomeDto(s.status().orElse(null), s.statusDescription().orElse(null)))")
    @Mapping(target = "times", expression = "java(new RestDtos.TimesDto(s.startedAt(), s.finishedAt().orElse(null)))")
    @Mapping(target = "lockedBy", expression = "java(s.lockedBy())")
    @Mapping(target = "leaseRenewals", expression = "java(s.leaseRenewals())")
    @Mapping(target = "result", expression = "java(s.result().orElse(null))")
    RestDtos.TaskAttemptDto taskAttempt(TaskAttemptSnapshot s);

    /**
     * Коммуникация.
     *
     * @param s снимок
     * @return форма ответа
     */
    @Mapping(target = "id", expression = "java(s.id())")
    @Mapping(target = "direction", expression = "java(s.direction())")
    @Mapping(target = "externalSystem", expression = "java(s.externalSystem())")
    @Mapping(target = "execution", expression = "java(execution(s.executionRef()))")
    @Mapping(target = "state", expression = "java(communicationState(s))")
    @Mapping(target = "origin", expression = "java(s.origin().orElse(null))")
    @Mapping(target = "requestPayload", expression = "java(s.requestPayload().orElse(null))")
    RestDtos.CommunicationDto communication(CommunicationSnapshot s);

    /**
     * Состояние коммуникации.
     *
     * @param s снимок
     * @return состояние
     */
    @Mapping(target = "status", expression = "java(s.status())")
    @Mapping(target = "statusDescription", expression = "java(s.statusDescription().orElse(null))")
    @Mapping(target = "phase", expression = "java(s.phase())")
    @Mapping(target = "contractShape", expression = "java(s.contractShape().orElse(null))")
    @Mapping(target = "priority", expression = "java(s.priority())")
    @Mapping(target = "createdAt", expression = "java(s.createdAt())")
    @Mapping(target = "updatedAt", expression = "java(s.updatedAt())")
    @Mapping(target = "orphan", expression = "java(s.orphan())")
    RestDtos.CommunicationStateDto communicationState(CommunicationSnapshot s);

    /**
     * Попытка коммуникации.
     *
     * @param s снимок
     * @return форма ответа
     */
    @Mapping(target = "id", expression = "java(s.id())")
    @Mapping(target = "phase", expression = "java(s.phase())")
    @Mapping(
            target = "outcome",
            expression = "java(new RestDtos.OutcomeDto(s.status().orElse(null), s.statusDescription().orElse(null)))")
    @Mapping(
            target = "times",
            expression = "java(new RestDtos.TimesDto(s.startedAt().orElse(null), s.finishedAt().orElse(null)))")
    @Mapping(target = "lockedBy", expression = "java(s.lockedBy().orElse(null))")
    @Mapping(target = "exchange", expression = "java(exchange(s))")
    RestDtos.CommunicationAttemptDto communicationAttempt(CommunicationAttemptSnapshot s);

    /**
     * Обмен попытки.
     *
     * @param s снимок
     * @return обмен
     */
    @Mapping(target = "request", expression = "java(s.request().map(this::message).orElse(null))")
    @Mapping(target = "response", expression = "java(s.response().map(this::message).orElse(null))")
    @Mapping(target = "callbackPayload", expression = "java(s.callbackPayload().orElse(null))")
    @Mapping(target = "responsePayload", expression = "java(s.responsePayload().orElse(null))")
    RestDtos.ExchangeDto exchange(CommunicationAttemptSnapshot s);

    /**
     * Сообщение обмена.
     *
     * @param m сообщение
     * @return форма ответа
     */
    @Mapping(target = "body", expression = "java(m.body())")
    @Mapping(target = "headers", expression = "java(m.headers().asMap())")
    @Mapping(target = "id", expression = "java(m.id().orElse(null))")
    @Mapping(target = "at", expression = "java(m.at().orElse(null))")
    @Mapping(target = "status", expression = "java(m.status().isPresent() ? m.status().getAsInt() : null)")
    RestDtos.ExchangeMessageDto message(ExchangeMessage m);

    /**
     * Дело.
     *
     * @param ref ссылка на дело
     * @return форма ответа
     */
    RestDtos.ExecutionRefDto execution(ExecutionRef ref);

    /**
     * Окно.
     *
     * @param w окно
     * @return форма ответа
     */
    @Mapping(target = "from", expression = "java(w.from().orElse(null))")
    @Mapping(target = "to", expression = "java(w.to().orElse(null))")
    @Mapping(target = "everywhere", expression = "java(w.isEverywhere())")
    RestDtos.WindowDto window(HistoryWindow w);
}
