package xyz.avasilev.taskcomm.rest;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import xyz.avasilev.taskcomm.communication.api.Communication;
import xyz.avasilev.taskcomm.communication.api.CommunicationHeaders;
import xyz.avasilev.taskcomm.communication.api.CommunicationService;
import xyz.avasilev.taskcomm.communication.api.InboundRequest;
import xyz.avasilev.taskcomm.communication.api.ReceiveResult;

/**
 * Ручной приём входящей (docs/adr/0153-ui-creation-refinements.md): запустить руками процесс, который
 * начинается входящей коммуникацией. Асинхронный приём тем же путём, что у потребителя, —
 * {@link CommunicationService#receive(String, InboundRequest)}: тело как есть, {@code Content-Type} запроса —
 * его заголовок.
 *
 * <p>Контроллер создаётся, только если {@code taskcomm.rest.manual-inbound: true}; по умолчанию его нет.
 * Внешним системам путь не отдают: аутентификация у него та же, что у человека в интерфейсе.
 */
@RestController
@RequestMapping("${taskcomm.rest.base-path:/tc/api}/communications/inbound")
public class ManualInboundController extends RestErrors {

    private final CommunicationService communications;

    private final RestMapper mapper;

    /**
     * Собирает контроллер.
     *
     * @param communications сервис коммуникаций
     * @param mapper формы ответов
     */
    public ManualInboundController(final CommunicationService communications, final RestMapper mapper) {
        this.communications = communications;
        this.mapper = mapper;
    }

    /**
     * Принимает входящую от системы.
     *
     * @param source внешняя система-отправитель
     * @param body тело как есть
     * @param contentType тип тела или {@code null}
     * @param params {@code requestId}, {@code dedupKey}, {@code correlationId}
     * @return {@code 201} с коммуникацией; у дубликата — с совпавшей, у неразбираемого тела — с причиной
     */
    @PostMapping("/{source}")
    public ResponseEntity<RestDtos.CreatedDto> receive(
            @PathVariable("source") final String source,
            @RequestBody final String body,
            @RequestHeader(name = HttpHeaders.CONTENT_TYPE, required = false) final String contentType,
            @RequestParam final Map<String, String> params) {
        final RestParams p = new RestParams(params);
        InboundRequest request = InboundRequest.of(body);
        if (contentType != null) {
            request = request.withHeaders(
                    CommunicationHeaders.of(Map.of(HttpHeaders.CONTENT_TYPE, List.of(contentType))));
        }
        if (p.text("requestId") != null) {
            request = request.withRequestId(p.text("requestId"));
        }
        if (p.text("dedupKey") != null) {
            request = request.withDedupKey(p.text("dedupKey"));
        }
        if (p.text("correlationId") != null) {
            request = request.withCorrelationId(p.text("correlationId"));
        }
        final ReceiveResult<?> received = this.communications.receive(source, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(this.created(received));
    }

    private RestDtos.CreatedDto created(final ReceiveResult<?> received) {
        return switch (received) {
            case ReceiveResult.Accepted<?> accepted -> this.dto(accepted.communication(), null, null);
            case ReceiveResult.Duplicate<?> duplicate ->
                this.dto(duplicate.communication(), duplicate.duplicateOf(), null);
            case ReceiveResult.Unparseable<?> unparseable ->
                this.dto(unparseable.communication(), null, unparseable.reason());
        };
    }

    private RestDtos.CreatedDto dto(
            final Communication.Inbound<?> communication, final UUID duplicateOf, final String reason) {
        return new RestDtos.CreatedDto(
                communication.id(),
                this.mapper.execution(communication.executionRef()),
                communication.status().name(),
                duplicateOf,
                reason);
    }
}
