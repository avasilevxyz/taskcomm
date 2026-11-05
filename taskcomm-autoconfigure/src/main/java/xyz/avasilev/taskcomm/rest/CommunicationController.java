package xyz.avasilev.taskcomm.rest;

import java.util.List;
import java.util.Map;
import java.util.Set;
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
import xyz.avasilev.taskcomm.communication.api.CircuitBreakerSnapshot;
import xyz.avasilev.taskcomm.communication.api.CommunicationCancelResult;
import xyz.avasilev.taskcomm.communication.api.CommunicationInspection;
import xyz.avasilev.taskcomm.communication.api.CommunicationQuery;
import xyz.avasilev.taskcomm.communication.api.CommunicationRestartResult;
import xyz.avasilev.taskcomm.communication.api.CommunicationSendOptions;
import xyz.avasilev.taskcomm.communication.api.CommunicationService;
import xyz.avasilev.taskcomm.communication.api.CommunicationSnapshot;
import xyz.avasilev.taskcomm.communication.api.CommunicationStatus;
import xyz.avasilev.taskcomm.communication.api.CommunicationSummary;
import xyz.avasilev.taskcomm.communication.api.SendResult;
import xyz.avasilev.taskcomm.execution.api.ExecutionRef;
import xyz.avasilev.taskcomm.execution.api.HistoryWindow;
import xyz.avasilev.taskcomm.execution.api.Page;

/**
 * REST коммуникаций (docs/adr/0140-rest-controllers.md): списки и детали, лукап по {@code request_id},
 * попытки с обменом, сводка, размыкатели ответившего экземпляра, отправка из JSON, отмена, перезапуск.
 * Приём входящих сюда не входит: его ведёт контроллер потребителя (docs/adr/0133-synchronous-inbound-reception.md).
 */
@RestController
@RequestMapping("${taskcomm.rest.base-path:/api/taskcomm}/communications")
public class CommunicationController extends RestErrors {

    private final CommunicationService communications;

    private final CommunicationInspection inspection;

    private final RestWindows windows;

    private final RestMapper mapper;

    /**
     * Собирает контроллер.
     *
     * @param communications сервис коммуникаций
     * @param inspection чтение коммуникаций
     * @param windows окна чтения истории
     * @param mapper формы ответов
     */
    public CommunicationController(
            final CommunicationService communications,
            final CommunicationInspection inspection,
            final RestWindows windows,
            final RestMapper mapper) {
        this.communications = communications;
        this.inspection = inspection;
        this.windows = windows;
        this.mapper = mapper;
    }

    /**
     * Список коммуникаций.
     *
     * @param params фильтры {@code direction}, {@code system}, {@code requestId}, дело, окно, страница
     * @param status статусы
     * @return страница
     */
    @GetMapping
    public RestDtos.PageDto<RestDtos.CommunicationDto> list(
            @RequestParam final Map<String, String> params,
            @RequestParam(name = "status", required = false) final List<CommunicationStatus> status) {
        final RestParams p = new RestParams(params);
        CommunicationQuery query = CommunicationQuery.all();
        if (p.text("direction") != null) {
            query = query.withDirection(CommunicationSnapshot.Direction.valueOf(p.text("direction")));
        }
        if (p.text("system") != null) {
            query = query.withSystem(p.text("system"));
        }
        if (status != null) {
            query = query.withStatuses(Set.copyOf(status));
        }
        if (p.text("requestId") != null) {
            query = query.withRequestId(p.text("requestId"));
        }
        final ExecutionRef execution = p.execution();
        if (execution != null) {
            query = query.inExecution(execution);
        }
        final HistoryWindow window = this.windows.resolve(p.instant("from"), p.instant("to"), p.everywhere());
        final Page<CommunicationSnapshot> page = this.inspection.list(query, window, p.page());
        return new RestDtos.PageDto<>(
                page.items().stream().map(this.mapper::communication).toList(),
                page.next().orElse(null),
                this.mapper.window(window));
    }

    /**
     * Одна коммуникация.
     *
     * @param id идентификатор
     * @param params окно или {@code executionCreatedAt}
     * @return коммуникация или {@code 404}
     */
    @GetMapping("/{id}")
    public ResponseEntity<RestDtos.CommunicationDto> find(
            @PathVariable("id") final UUID id, @RequestParam final Map<String, String> params) {
        return this.withCommunication(id, params, found -> ResponseEntity.ok(this.mapper.communication(found)));
    }

    /**
     * История попыток с обменом: что ушло и что вернулось в каждой.
     *
     * @param id идентификатор
     * @param params окно или {@code executionCreatedAt}
     * @return попытки или {@code 404}
     */
    @GetMapping("/{id}/attempts")
    public ResponseEntity<List<RestDtos.CommunicationAttemptDto>> attempts(
            @PathVariable("id") final UUID id, @RequestParam final Map<String, String> params) {
        return this.withCommunication(
                id,
                params,
                found -> ResponseEntity.ok(this.inspection.attempts(found).stream()
                        .map(this.mapper::communicationAttempt)
                        .toList()));
    }

    /**
     * Отмена.
     *
     * @param id идентификатор
     * @param params окно или {@code executionCreatedAt}
     * @return {@code 200} — принята, {@code 409} — отвергнута, {@code 404} — не найдена
     */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<RestDtos.ResultDto> cancel(
            @PathVariable("id") final UUID id, @RequestParam final Map<String, String> params) {
        return this.withCommunication(id, params, found -> {
            final CommunicationCancelResult result = this.communications.cancel(found);
            return result(result.accepted(), result.reason());
        });
    }

    /**
     * Перезапуск упавшей.
     *
     * @param id идентификатор
     * @param params окно или {@code executionCreatedAt}
     * @return {@code 200} — принят, {@code 409} — отвергнут, {@code 404} — не найдена
     */
    @PostMapping("/{id}/restart")
    public ResponseEntity<RestDtos.ResultDto> restart(
            @PathVariable("id") final UUID id, @RequestParam final Map<String, String> params) {
        return this.withCommunication(id, params, found -> {
            final CommunicationRestartResult result = this.communications.restart(found);
            return result(result.accepted(), result.reason());
        });
    }

    /**
     * Отправка исходящей из JSON: тело запроса — доменная форма запроса процессора системы.
     *
     * @param destination внешняя система-получатель
     * @param payload запрос в JSON
     * @param params дело, {@code priority}, {@code notBefore}, {@code dedupKey}, {@code correlationId}
     * @return {@code 201} с созданной коммуникацией; у дубликата — с совпавшей
     */
    @PostMapping(value = "/outbound/{destination}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RestDtos.CreatedDto> send(
            @PathVariable("destination") final String destination,
            @RequestBody final String payload,
            @RequestParam final Map<String, String> params) {
        final SendResult<?> sent = this.communications.sendJson(destination, payload, options(new RestParams(params)));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new RestDtos.CreatedDto(
                        sent.communication().id(),
                        this.mapper.execution(sent.communication().executionRef()),
                        sent.communication().status().name(),
                        sent instanceof SendResult.Duplicate<?> duplicate ? duplicate.duplicateOf() : null));
    }

    /**
     * Сводка по направлениям, системам и статусам в окне.
     *
     * @param params окно
     * @return сводка
     */
    @GetMapping("/summary")
    public List<CommunicationSummary> summary(@RequestParam final Map<String, String> params) {
        final RestParams p = new RestParams(params);
        return this.inspection.summary(this.windows.resolve(p.instant("from"), p.instant("to"), p.everywhere()));
    }

    /**
     * Размыкатели ответившего экземпляра — только чтение, с его именем.
     *
     * @return снимки
     */
    @GetMapping("/circuit-breakers")
    public List<CircuitBreakerSnapshot> circuitBreakers() {
        return this.inspection.circuitBreakers();
    }

    private static CommunicationSendOptions options(final RestParams p) {
        CommunicationSendOptions options = CommunicationSendOptions.defaults();
        final ExecutionRef execution = p.execution();
        if (execution != null) {
            options = options.inExecution(execution);
        }
        if (p.text("priority") != null) {
            options = options.priority(Integer.parseInt(p.text("priority")));
        }
        if (p.instant("notBefore") != null) {
            options = options.notBefore(p.instant("notBefore"));
        }
        if (p.text("dedupKey") != null) {
            options = options.dedupKey(p.text("dedupKey"));
        }
        if (p.text("correlationId") != null) {
            options = options.correlationId(p.text("correlationId"));
        }
        return options;
    }

    private <T> ResponseEntity<T> withCommunication(
            final UUID id,
            final Map<String, String> params,
            final Function<CommunicationSnapshot, ResponseEntity<T>> action) {
        final RestParams p = new RestParams(params);
        final HistoryWindow window = this.windows.entity(
                p.instant("executionCreatedAt"), p.instant("from"), p.instant("to"), p.everywhere());
        return this.inspection
                .find(id, window)
                .map(action)
                .orElseGet(() -> notFound("коммуникация " + id + " в окне " + window));
    }
}
