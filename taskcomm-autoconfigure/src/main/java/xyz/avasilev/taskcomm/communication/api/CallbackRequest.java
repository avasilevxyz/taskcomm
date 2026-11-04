package xyz.avasilev.taskcomm.communication.api;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Значение-запрос приёма колбэка — асинхронного ответа на исходящую
 * (docs/adr/0086-async-response-in-outbound.md). Передаётся в
 * {@link CommunicationService#receiveResponse}; само по себе ничего не делает. Каждый метод
 * {@code with…} возвращает новое значение.
 *
 * <pre>{@code
 * communicationService.receiveResponse("supplier-b", CallbackRequest.of(ref, body)
 *         .withHeaders(CommunicationHeaders.of(headers)));
 * }</pre>
 */
public final class CallbackRequest {

    private final String correlationId;
    private final String body;
    private final CommunicationHeaders headers;
    private final String requestId;
    private final Instant requestAt;

    private CallbackRequest(
            final String correlationId,
            final String body,
            final CommunicationHeaders headers,
            final String requestId,
            final Instant requestAt) {
        this.correlationId = correlationId;
        this.body = body;
        this.headers = headers;
        this.requestId = requestId;
        this.requestAt = requestAt;
    }

    /**
     * Колбэк с ключом корреляции и сырым телом. Ключ ищется в пределах системы-отправителя
     * колбэка (docs/adr/0075-correlation-key-scoped-by-system.md).
     *
     * @param correlationId ключ корреляции из колбэка
     * @param body сырое тело как пришло; двоичное — в base64
     * @return значение-запрос
     */
    public static CallbackRequest of(final String correlationId, final String body) {
        return new CallbackRequest(
                Objects.requireNonNull(correlationId, "correlationId"),
                Objects.requireNonNull(body, "body"),
                CommunicationHeaders.empty(),
                null,
                null);
    }

    /**
     * Заголовки колбэка.
     *
     * @param value заголовки
     * @return новое значение
     */
    public CallbackRequest withHeaders(final CommunicationHeaders value) {
        return new CallbackRequest(
                this.correlationId,
                this.body,
                Objects.requireNonNull(value, "headers"),
                this.requestId,
                this.requestAt);
    }

    /**
     * Идентификатор колбэка в протоколе внешней системы.
     *
     * @param value идентификатор
     * @return новое значение
     */
    public CallbackRequest withRequestId(final String value) {
        return new CallbackRequest(
                this.correlationId,
                this.body,
                this.headers,
                Objects.requireNonNull(value, "requestId"),
                this.requestAt);
    }

    /**
     * Время колбэка, как оно стоит в самом сообщении.
     *
     * @param value время
     * @return новое значение
     */
    public CallbackRequest withRequestAt(final Instant value) {
        return new CallbackRequest(
                this.correlationId,
                this.body,
                this.headers,
                this.requestId,
                Objects.requireNonNull(value, "requestAt"));
    }

    /**
     * Ключ корреляции.
     *
     * @return значение ключа
     */
    public String correlationId() {
        return this.correlationId;
    }

    /**
     * Сырое тело.
     *
     * @return тело как пришло
     */
    public String body() {
        return this.body;
    }

    /**
     * Заголовки.
     *
     * @return заголовки; пустые, если не заданы
     */
    public CommunicationHeaders headers() {
        return this.headers;
    }

    /**
     * Идентификатор колбэка.
     *
     * @return идентификатор или пусто
     */
    public Optional<String> requestId() {
        return Optional.ofNullable(this.requestId);
    }

    /**
     * Время колбэка.
     *
     * @return время или пусто
     */
    public Optional<Instant> requestAt() {
        return Optional.ofNullable(this.requestAt);
    }
}
