package xyz.avasilev.taskcomm.rest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import xyz.avasilev.taskcomm.SqlQuery;
import xyz.avasilev.taskcomm.communication.api.inspection.CommunicationInspection;
import xyz.avasilev.taskcomm.communication.api.inspection.CommunicationQuery;
import xyz.avasilev.taskcomm.communication.api.inspection.CorrelationKeySnapshot;
import xyz.avasilev.taskcomm.execution.api.ExecutionRef;
import xyz.avasilev.taskcomm.execution.api.ExecutionService;
import xyz.avasilev.taskcomm.execution.api.HistoryWindow;
import xyz.avasilev.taskcomm.execution.api.Page;
import xyz.avasilev.taskcomm.execution.api.PageRequest;
import xyz.avasilev.taskcomm.task.api.inspection.TaskInspection;
import xyz.avasilev.taskcomm.task.api.inspection.TaskQuery;
import xyz.avasilev.taskcomm.task.api.inspection.TaskSnapshot;
import xyz.avasilev.taskcomm.task.api.inspection.TaskUnitCount;

/**
 * Дело одной выдачей (docs/adr/0064-entity-origin-links.md, docs/adr/0140-rest-controllers.md): все задачи,
 * группы, подзадачи и коммуникации дела в порядке создания, у каждой — её создатель, и ключи корреляции.
 * Без предела: выдача читает всю партицию дела страницами и отдаёт её целиком. Для экрана — выдача без
 * подзадач со счётчиками (docs/adr/0150-execution-outline-and-meta.md).
 */
@RestController
@RequestMapping("${taskcomm.rest.base-path:/tc/api}/executions")
public class ExecutionController extends RestErrors {

    /**
     * Сколько подзадач у каждого родителя попадает в выдачу дела без подзадач
     * (docs/adr/0166-execution-outline-units-and-attempts.md); остальные — списком, страницами.
     */
    static final int UNITS_PER_PARENT = 50;

    /** Размер страницы, которой выдача читает партицию дела. */
    private static final int CHUNK = PageRequest.MAX_SIZE;

    private final ExecutionService executions;

    private final TaskInspection tasks;

    private final CommunicationInspection communications;

    private final RestMapper mapper;

    /**
     * Собирает контроллер.
     *
     * @param executions дела
     * @param tasks чтение задач или {@code null}, если подсистема задач выключена
     * @param communications чтение коммуникаций или {@code null}, если подсистема коммуникаций выключена
     * @param mapper формы ответов
     */
    public ExecutionController(
            final ExecutionService executions,
            final TaskInspection tasks,
            final CommunicationInspection communications,
            final RestMapper mapper) {
        this.executions = executions;
        this.tasks = tasks;
        this.communications = communications;
        this.mapper = mapper;
    }

    /**
     * Дело целиком.
     *
     * @param id идентификатор дела
     * @param createdAt время создания дела; не задано — ищется по идентификатору
     * @return дело или {@code 404}
     */
    @GetMapping("/{id}")
    public ResponseEntity<RestDtos.ExecutionDto> execution(
            @PathVariable("id") final UUID id,
            @RequestParam(name = "createdAt", required = false) final Instant createdAt) {
        return this.ref(id, createdAt)
                .map(found -> ResponseEntity.ok(this.whole(found)))
                .orElseGet(() -> notFound("дело " + id));
    }

    /**
     * Дело для экрана (docs/adr/0150-execution-outline-and-meta.md,
     * docs/adr/0166-execution-outline-units-and-attempts.md): задачи, группы, коммуникации, ключи
     * корреляции, счётчики подзадач по родителю и статусу, подзадачи — не больше
     * {@value #UNITS_PER_PARENT} у родителя — и попытки всех выданных сущностей без результатов.
     * Остальные подзадачи — списком {@code GET /tasks} с {@code level=UNIT}, делом и родителем, страницами.
     *
     * @param id идентификатор дела
     * @param createdAt время создания дела; не задано — ищется по идентификатору
     * @return дело или {@code 404}
     */
    @GetMapping("/{id}/outline")
    public ResponseEntity<RestDtos.ExecutionOutlineDto> outline(
            @PathVariable("id") final UUID id,
            @RequestParam(name = "createdAt", required = false) final Instant createdAt) {
        return this.ref(id, createdAt)
                .map(found -> ResponseEntity.ok(new RestDtos.ExecutionOutlineDto(
                        this.mapper.execution(found),
                        this.level(found, TaskSnapshot.Level.TASK),
                        this.level(found, TaskSnapshot.Level.BATCH),
                        this.tasks == null
                                ? List.<RestDtos.TaskDto>of()
                                : this.tasks.units(found, UNITS_PER_PARENT).stream()
                                        .map(this.mapper::task)
                                        .toList(),
                        this.tasks == null ? List.<TaskUnitCount>of() : this.tasks.unitCounts(found),
                        this.communications(found),
                        this.correlationKeys(found),
                        this.attempts(found))))
                .orElseGet(() -> notFound("дело " + id));
    }

    /**
     * Запросы, которыми читается дело без подзадач, — с подставленными значениями
     * (docs/adr/0165-read-sql.md): задачи, группы, счётчики подзадач, коммуникации, ключи корреляции.
     * Задачи, группы и коммуникации выдача дочитывает страницами; здесь — первая из них.
     *
     * @param id идентификатор дела
     * @param createdAt время создания дела; не задано — ищется по идентификатору
     * @return текст для psql или {@code 404}
     */
    @GetMapping("/{id}/outline/sql")
    public ResponseEntity<RestDtos.SqlDto> outlineSql(
            @PathVariable("id") final UUID id,
            @RequestParam(name = "createdAt", required = false) final Instant createdAt) {
        return this.ref(id, createdAt)
                .map(found -> ResponseEntity.ok(new RestDtos.SqlDto(SqlLiterals.script(this.outlineSql(found)))))
                .orElseGet(() -> notFound("дело " + id));
    }

    /** Запросы выдачи дела в её порядке; выключенной подсистемы — нет. */
    private Map<String, SqlQuery> outlineSql(final ExecutionRef execution) {
        final Map<String, SqlQuery> statements = new LinkedHashMap<>();
        final PageRequest first = PageRequest.first(CHUNK);
        if (this.tasks != null) {
            statements.put(
                    "Задачи дела",
                    this.tasks.listSql(
                            TaskQuery.of(TaskSnapshot.Level.TASK).inExecution(execution),
                            HistoryWindow.everywhere(),
                            first));
            statements.put(
                    "Группы дела",
                    this.tasks.listSql(
                            TaskQuery.of(TaskSnapshot.Level.BATCH).inExecution(execution),
                            HistoryWindow.everywhere(),
                            first));
            statements.put("Подзадачи дела по родителю и статусу", this.tasks.unitCountsSql(execution));
            statements.put(
                    "Подзадачи дела, первые " + UNITS_PER_PARENT + " у каждого родителя",
                    this.tasks.unitsSql(execution, UNITS_PER_PARENT));
            statements.put(
                    "Попытки задач, групп и этих подзадач", this.tasks.attemptEntriesSql(execution, UNITS_PER_PARENT));
        }
        if (this.communications != null) {
            statements.put(
                    "Коммуникации дела",
                    this.communications.listSql(
                            CommunicationQuery.all().inExecution(execution), HistoryWindow.everywhere(), first));
            statements.put("Ключи корреляции дела", this.communications.correlationKeysSql(execution));
            statements.put("Попытки коммуникаций дела", this.communications.attemptEntriesSql(execution));
        }
        return statements;
    }

    /** Попытки задач, групп, выданных подзадач и коммуникаций дела. */
    private List<RestDtos.AttemptEntryDto> attempts(final ExecutionRef execution) {
        final List<RestDtos.AttemptEntryDto> attempts = new ArrayList<>();
        if (this.tasks != null) {
            this.tasks
                    .attemptEntries(execution, UNITS_PER_PARENT)
                    .forEach(entry -> attempts.add(new RestDtos.AttemptEntryDto(
                            entry.id(),
                            entry.level().name(),
                            entry.targetId(),
                            entry.phase(),
                            new RestDtos.OutcomeDto(entry.status(), entry.statusDescription()),
                            new RestDtos.TimesDto(entry.startedAt(), entry.finishedAt()))));
        }
        if (this.communications != null) {
            this.communications
                    .attemptEntries(execution)
                    .forEach(entry -> attempts.add(new RestDtos.AttemptEntryDto(
                            entry.id(),
                            "COMMUNICATION",
                            entry.communicationId(),
                            entry.phase(),
                            new RestDtos.OutcomeDto(entry.status(), entry.statusDescription()),
                            new RestDtos.TimesDto(entry.startedAt(), entry.finishedAt()))));
        }
        return attempts;
    }

    private Optional<ExecutionRef> ref(final UUID id, final Instant createdAt) {
        return RestExecutions.find(this.executions, id, createdAt);
    }

    private RestDtos.ExecutionDto whole(final ExecutionRef execution) {
        return new RestDtos.ExecutionDto(
                this.mapper.execution(execution),
                this.level(execution, TaskSnapshot.Level.TASK),
                this.level(execution, TaskSnapshot.Level.BATCH),
                this.level(execution, TaskSnapshot.Level.UNIT),
                this.communications(execution),
                this.correlationKeys(execution));
    }

    private List<RestDtos.CommunicationDto> communications(final ExecutionRef execution) {
        return this.communications == null
                ? List.of()
                : all(
                        cursor -> this.communications.list(
                                CommunicationQuery.all().inExecution(execution), HistoryWindow.everywhere(), cursor),
                        this.mapper::communication);
    }

    private List<CorrelationKeySnapshot> correlationKeys(final ExecutionRef execution) {
        return this.communications == null ? List.of() : this.communications.correlationKeys(execution);
    }

    private List<RestDtos.TaskDto> level(final ExecutionRef execution, final TaskSnapshot.Level level) {
        return this.tasks == null
                ? List.of()
                : all(
                        cursor -> this.tasks.list(
                                TaskQuery.of(level).inExecution(execution), HistoryWindow.everywhere(), cursor),
                        this.mapper::task);
    }

    /** Все страницы; списки идут от новых к старым, выдача — в порядке создания. */
    private static <ESource, EResult> List<EResult> all(
            final Function<PageRequest, Page<ESource>> read, final Function<ESource, EResult> map) {
        final List<EResult> collected = new ArrayList<>();
        Page<ESource> page = read.apply(PageRequest.first(CHUNK));
        page.items().forEach(item -> collected.add(map.apply(item)));
        while (page.next().isPresent()) {
            page = read.apply(PageRequest.after(page.next().get(), CHUNK));
            page.items().forEach(item -> collected.add(map.apply(item)));
        }
        return collected.reversed();
    }
}
