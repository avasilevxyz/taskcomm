package xyz.avasilev.taskcomm.communication.api;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Значение-запрос приёма входящей: что прислала внешняя система и с какими ключами её создать.
 * Одно для обоих методов приёма — {@link CommunicationService#receive} и
 * {@link CommunicationService#receiveAndProcess}.
 *
 * <p><b>Само по себе ничего не создаёт</b>: неизменяемое значение надо передать в метод приёма.
 * Самозапускающегося билдера нет сознательно — забытый терминальный вызов молча терял бы
 * коммуникацию, а неиспользованное значение подсветят IDE и статический анализ
 * (docs/adr/0053-inbound-reception-modes.md). Каждый метод {@code with…} возвращает новое значение.
 *
 * <pre>{@code
 * ReceiveResult<?> result = communicationService.receive("supplier-b", InboundRequest.of(body)
 *         .withHeaders(CommunicationHeaders.of(headers))
 *         .withRequestId(messageId)
 *         .withDedupKey(messageId));
 * }</pre>
 */
public final class InboundRequest {

    private final String body;
    private final CommunicationHeaders headers;
    private final String requestId;
    private final Instant requestAt;
    private final List<DedupKey> dedupKeys;
    private final List<CorrelationKey> correlationKeys;

    private InboundRequest(
            final String body,
            final CommunicationHeaders headers,
            final String requestId,
            final Instant requestAt,
            final List<DedupKey> dedupKeys,
            final List<CorrelationKey> correlationKeys) {
        this.body = body;
        this.headers = headers;
        this.requestId = requestId;
        this.requestAt = requestAt;
        this.dedupKeys = dedupKeys;
        this.correlationKeys = correlationKeys;
    }

    /**
     * Запрос с сырым телом, без заголовков и ключей.
     *
     * @param body сырое тело как пришло; двоичное — в base64
     * @return значение-запрос
     */
    public static InboundRequest of(final String body) {
        return new InboundRequest(
                Objects.requireNonNull(body, "body"), CommunicationHeaders.empty(), null, null, List.of(), List.of());
    }

    /**
     * Заголовки присланного сообщения.
     *
     * @param value заголовки
     * @return новое значение
     */
    public InboundRequest withHeaders(final CommunicationHeaders value) {
        return new InboundRequest(
                this.body,
                Objects.requireNonNull(value, "headers"),
                this.requestId,
                this.requestAt,
                this.dedupKeys,
                this.correlationKeys);
    }

    /**
     * Идентификатор сообщения в протоколе внешней системы — {@code request_id} попытки.
     *
     * @param value идентификатор
     * @return новое значение
     */
    public InboundRequest withRequestId(final String value) {
        return new InboundRequest(
                this.body,
                this.headers,
                Objects.requireNonNull(value, "requestId"),
                this.requestAt,
                this.dedupKeys,
                this.correlationKeys);
    }

    /**
     * Время сообщения, как оно стоит в самом сообщении, — {@code request_at} попытки.
     *
     * @param value время
     * @return новое значение
     */
    public InboundRequest withRequestAt(final Instant value) {
        return new InboundRequest(
                this.body,
                this.headers,
                this.requestId,
                Objects.requireNonNull(value, "requestAt"),
                this.dedupKeys,
                this.correlationKeys);
    }

    /**
     * Ключ дедупликации по всей истории. Проверяется в транзакции создания, только при
     * {@code READ COMMITTED} (docs/adr/0095-dedup-requires-read-committed.md). Синхронному приёму
     * он не годится: совпадёт с упавшей доставкой, и повторная доставка станет {@code SKIPPED} без
     * обработки. Там берите ключ по последней успешной
     * (docs/adr/0133-synchronous-inbound-reception.md).
     *
     * @param key значение ключа
     * @return новое значение
     */
    public InboundRequest withDedupKey(final String key) {
        return this.withDedup(new DedupKey.WholeHistory(key));
    }

    /**
     * Ключ дедупликации по последней успешной с этим предметом.
     *
     * @param subject предмет
     * @param key значение ключа
     * @return новое значение
     */
    public InboundRequest withDedupKey(final String subject, final String key) {
        return this.withDedup(new DedupKey.LastSucceeded(subject, key));
    }

    /**
     * Ключ корреляции от этой же системы. Известный ключ — входящая попадает в дело якоря
     * (docs/adr/0040-communication-correlation.md).
     *
     * @param correlationId значение ключа
     * @return новое значение
     */
    public InboundRequest withCorrelationId(final String correlationId) {
        return this.withCorrelation(CorrelationKey.of(correlationId));
    }

    /**
     * Ключ корреляции от явно названной системы.
     *
     * @param externalSystem система, от которой ждут колбэк
     * @param correlationId значение ключа
     * @return новое значение
     */
    public InboundRequest withCorrelationId(final String externalSystem, final String correlationId) {
        return this.withCorrelation(CorrelationKey.of(externalSystem, correlationId));
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
     * Идентификатор сообщения.
     *
     * @return идентификатор или пусто
     */
    public Optional<String> requestId() {
        return Optional.ofNullable(this.requestId);
    }

    /**
     * Время сообщения.
     *
     * @return время или пусто
     */
    public Optional<Instant> requestAt() {
        return Optional.ofNullable(this.requestAt);
    }

    /**
     * Ключи дедупликации.
     *
     * @return ключи в порядке добавления
     */
    public List<DedupKey> dedupKeys() {
        return this.dedupKeys;
    }

    /**
     * Ключи корреляции.
     *
     * @return ключи в порядке добавления
     */
    public List<CorrelationKey> correlationKeys() {
        return this.correlationKeys;
    }

    private InboundRequest withDedup(final DedupKey key) {
        final List<DedupKey> keys = new ArrayList<>(this.dedupKeys);
        keys.add(key);
        return new InboundRequest(
                this.body, this.headers, this.requestId, this.requestAt, List.copyOf(keys), this.correlationKeys);
    }

    private InboundRequest withCorrelation(final CorrelationKey key) {
        final List<CorrelationKey> keys = new ArrayList<>(this.correlationKeys);
        keys.add(key);
        return new InboundRequest(
                this.body, this.headers, this.requestId, this.requestAt, this.dedupKeys, List.copyOf(keys));
    }
}
