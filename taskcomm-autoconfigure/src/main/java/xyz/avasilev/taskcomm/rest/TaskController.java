package xyz.avasilev.taskcomm.rest;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import xyz.avasilev.taskcomm.execution.api.HistoryWindow;
import xyz.avasilev.taskcomm.execution.api.Page;
import xyz.avasilev.taskcomm.task.api.Task;
import xyz.avasilev.taskcomm.task.api.TaskCancelResult;
import xyz.avasilev.taskcomm.task.api.TaskRestartResult;
import xyz.avasilev.taskcomm.task.api.TaskService;
import xyz.avasilev.taskcomm.task.api.inspection.SearchableFieldSnapshot;
import xyz.avasilev.taskcomm.task.api.inspection.TaskAttemptSnapshot;
import xyz.avasilev.taskcomm.task.api.inspection.TaskInspection;
import xyz.avasilev.taskcomm.task.api.inspection.TaskSnapshot;
import xyz.avasilev.taskcomm.task.api.inspection.TaskTypeSummary;

/**
 * REST задач (docs/adr/0140-rest-controllers.md): списки и детали, поиск по нагрузке, попытки, поля поиска,
 * сводка, постановка из JSON, отмена, перезапуск, ручное удаление результатов. Окно чтения истории —
 * параметры {@code from}, {@code to}, {@code everywhere}; выдача списка называет окно, в котором искали.
 */
@RestController
@RequestMapping("${taskcomm.rest.base-path:/tc/api}/tasks")
public class TaskController extends RestErrors {

    private final TaskService tasks;

    private final TaskInspection inspection;

    private final RestWindows windows;

    private final RestMapper mapper;

    /**
     * Собирает контроллер.
     *
     * @param tasks сервис задач
     * @param inspection чтение задач
     * @param windows окна чтения истории
     * @param mapper формы ответов
     */
    public TaskController(
            final TaskService tasks,
            final TaskInspection inspection,
            final RestWindows windows,
            final RestMapper mapper) {
        this.tasks = tasks;
        this.inspection = inspection;
        this.windows = windows;
        this.mapper = mapper;
    }

    /**
     * Список сущностей уровня.
     *
     * @param level уровень
     * @param params все параметры: фильтры, окно, страница, поля поиска {@code field.<имя>}
     * @param status статусы
     * @return страница
     */
    @GetMapping
    public RestDtos.PageDto<RestDtos.TaskDto> list(
            @RequestParam(name = "level", defaultValue = "TASK") final TaskSnapshot.Level level,
            @RequestParam final Map<String, String> params,
            @RequestParam(name = "status", required = false) final List<String> status) {
        final RestParams p = new RestParams(params);
        final HistoryWindow window = this.windows.resolve(p.instant("from"), p.instant("to"), p.everywhere());
        final Page<TaskSnapshot> page =
                this.inspection.list(TaskRequests.query(level, params, status), window, p.page());
        return new RestDtos.PageDto<>(
                page.items().stream().map(this.mapper::task).toList(),
                page.next().orElse(null),
                this.mapper.window(window));
    }

    /**
     * Одна сущность.
     *
     * @param level уровень
     * @param id идентификатор
     * @param params окно или {@code executionCreatedAt}
     * @return сущность или {@code 404}
     */
    @GetMapping("/{level}/{id}")
    public ResponseEntity<RestDtos.TaskDto> find(
            @PathVariable("level") final TaskSnapshot.Level level,
            @PathVariable("id") final UUID id,
            @RequestParam final Map<String, String> params) {
        return this.withEntity(level, id, params, found -> ResponseEntity.ok(this.mapper.task(found)));
    }

    /**
     * Попытки сущности.
     *
     * @param level уровень
     * @param id идентификатор
     * @param params окно или {@code executionCreatedAt}
     * @return попытки или {@code 404}
     */
    @GetMapping("/{level}/{id}/attempts")
    public ResponseEntity<List<RestDtos.TaskAttemptDto>> attempts(
            @PathVariable("level") final TaskSnapshot.Level level,
            @PathVariable("id") final UUID id,
            @RequestParam final Map<String, String> params) {
        return this.withEntity(level, id, params, found -> {
            final List<TaskAttemptSnapshot> attempts = this.inspection.attempts(found);
            return ResponseEntity.ok(
                    attempts.stream().map(this.mapper::taskAttempt).toList());
        });
    }

    /**
     * Отмена.
     *
     * @param level уровень
     * @param id идентификатор
     * @param params окно или {@code executionCreatedAt}
     * @return {@code 200} — принята, {@code 409} — отвергнута, {@code 404} — не найдена
     */
    @PostMapping("/{level}/{id}/cancel")
    public ResponseEntity<RestDtos.ResultDto> cancel(
            @PathVariable("level") final TaskSnapshot.Level level,
            @PathVariable("id") final UUID id,
            @RequestParam final Map<String, String> params) {
        return this.withEntity(level, id, params, found -> {
            final TaskCancelResult result = this.tasks.cancel(found);
            return result(result.accepted(), result.reason());
        });
    }

    /**
     * Перезапуск.
     *
     * @param level уровень
     * @param id идентификатор
     * @param params окно или {@code executionCreatedAt}
     * @return {@code 200} — принят, {@code 409} — отвергнут, {@code 404} — не найдена
     */
    @PostMapping("/{level}/{id}/restart")
    public ResponseEntity<RestDtos.ResultDto> restart(
            @PathVariable("level") final TaskSnapshot.Level level,
            @PathVariable("id") final UUID id,
            @RequestParam final Map<String, String> params) {
        return this.withEntity(level, id, params, found -> {
            final TaskRestartResult result = this.tasks.restart(found);
            return result(result.accepted(), result.reason());
        });
    }

    /**
     * Постановка задачи из JSON: тело запроса — нагрузка задачи.
     *
     * @param type тип задачи
     * @param payload нагрузка в JSON
     * @param params {@code executionId} с {@code executionCreatedAt}, {@code priority}, {@code notBefore}
     * @return {@code 201} с созданной задачей
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RestDtos.CreatedDto> submit(
            @RequestParam("type") final String type,
            @RequestBody final String payload,
            @RequestParam final Map<String, String> params) {
        final Task<?> task = this.tasks.submitJson(type, payload, TaskRequests.options(params));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new RestDtos.CreatedDto(
                        task.id(),
                        this.mapper.execution(task.executionRef()),
                        task.status().name(),
                        null));
    }

    /**
     * Поля, доступные для поиска, — с подписями для UI.
     *
     * @return реестр полей
     */
    @GetMapping("/search-fields")
    public List<SearchableFieldSnapshot> searchFields() {
        return this.inspection.searchableFields();
    }

    /**
     * Сводка по уровням, типам и статусам в окне.
     *
     * @param params окно
     * @return сводка
     */
    @GetMapping("/summary")
    public List<TaskTypeSummary> summary(@RequestParam final Map<String, String> params) {
        final RestParams p = new RestParams(params);
        return this.inspection.summary(this.windows.resolve(p.instant("from"), p.instant("to"), p.everywhere()));
    }

    /**
     * Ручное удаление результатов детей у собранных родителей.
     *
     * @param olderThan сколько прошло с конца сборки, ISO-8601; не задано — все
     * @return сколько родителей отмечено
     */
    @PostMapping("/discard-child-results")
    public RestDtos.DiscardDto discard(@RequestParam(name = "olderThan", required = false) final Duration olderThan) {
        return new RestDtos.DiscardDto(this.tasks.discardChildResults(olderThan == null ? Duration.ZERO : olderThan));
    }

    private <TBody> ResponseEntity<TBody> withEntity(
            final TaskSnapshot.Level level,
            final UUID id,
            final Map<String, String> params,
            final Function<TaskSnapshot, ResponseEntity<TBody>> action) {
        final RestParams p = new RestParams(params);
        final Instant executionCreatedAt = p.instant("executionCreatedAt");
        final HistoryWindow window =
                this.windows.entity(executionCreatedAt, p.instant("from"), p.instant("to"), p.everywhere());
        return this.inspection
                .find(level, id, window)
                .map(action)
                .orElseGet(() -> notFound(level + " " + id + " в окне " + window));
    }
}
