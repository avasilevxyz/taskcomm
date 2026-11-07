package xyz.avasilev.taskcomm.rest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import xyz.avasilev.taskcomm.communication.api.inspection.CommunicationInspection;
import xyz.avasilev.taskcomm.communication.api.inspection.CommunicationQuery;
import xyz.avasilev.taskcomm.execution.api.Execution;
import xyz.avasilev.taskcomm.execution.api.ExecutionRef;
import xyz.avasilev.taskcomm.execution.api.ExecutionService;
import xyz.avasilev.taskcomm.execution.api.HistoryWindow;
import xyz.avasilev.taskcomm.execution.api.Page;
import xyz.avasilev.taskcomm.execution.api.PageRequest;
import xyz.avasilev.taskcomm.task.api.inspection.TaskInspection;
import xyz.avasilev.taskcomm.task.api.inspection.TaskQuery;
import xyz.avasilev.taskcomm.task.api.inspection.TaskSnapshot;

/**
 * Дело одной выдачей (docs/adr/0064-entity-origin-links.md, docs/adr/0140-rest-controllers.md): все задачи,
 * группы, подзадачи и коммуникации дела в порядке создания, у каждой — её создатель, и ключи корреляции.
 * Без предела: выдача читает всю партицию дела страницами и отдаёт её целиком.
 */
@RestController
@RequestMapping("${taskcomm.rest.base-path:/api/taskcomm}/executions")
public class ExecutionController extends RestErrors {

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
        final Optional<ExecutionRef> ref = createdAt == null
                ? this.executions.find(id).map(Execution::ref)
                : Optional.of(new ExecutionRef(id, createdAt));
        return ref.map(found -> ResponseEntity.ok(this.whole(found))).orElseGet(() -> notFound("дело " + id));
    }

    private RestDtos.ExecutionDto whole(final ExecutionRef execution) {
        return new RestDtos.ExecutionDto(
                this.mapper.execution(execution),
                this.level(execution, TaskSnapshot.Level.TASK),
                this.level(execution, TaskSnapshot.Level.BATCH),
                this.level(execution, TaskSnapshot.Level.UNIT),
                this.communications == null
                        ? List.of()
                        : all(
                                cursor -> this.communications.list(
                                        CommunicationQuery.all().inExecution(execution),
                                        HistoryWindow.everywhere(),
                                        cursor),
                                this.mapper::communication),
                this.communications == null ? List.of() : this.communications.correlationKeys(execution));
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
