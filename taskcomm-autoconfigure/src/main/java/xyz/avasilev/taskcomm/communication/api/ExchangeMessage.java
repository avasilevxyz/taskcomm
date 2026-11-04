package xyz.avasilev.taskcomm.communication.api;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Одно сообщение обмена — запрос или ответ, как он ушёл или пришёл по проводу
 * (docs/adr/0055-communication-exchange-storage.md). Пишется в строку попытки методами
 * {@link CommunicationContext#recordRequest(ExchangeMessage)} и
 * {@link CommunicationContext#recordResponse(ExchangeMessage)}.
 *
 * <p>Неизменяемое значение; каждый метод {@code with…} возвращает новое.
 *
 * <pre>{@code
 * context.recordResponse(ExchangeMessage.of(response.body())
 *         .withStatus(response.statusCode())
 *         .withHeaders(CommunicationHeaders.of(response.headers().map())));
 * }</pre>
 */
public final class ExchangeMessage {

    private final String body;
    private final CommunicationHeaders headers;
    private final String id;
    private final Instant at;
    private final Integer status;

    private ExchangeMessage(
            final String body,
            final CommunicationHeaders headers,
            final String id,
            final Instant at,
            final Integer status) {
        this.body = body;
        this.headers = headers;
        this.id = id;
        this.at = at;
        this.status = status;
    }

    /**
     * Сообщение с телом, без заголовков, идентификатора, времени и статуса.
     *
     * @param body сырое тело как есть; пустая строка — пустое тело; двоичное — в base64
     * @return сообщение
     */
    public static ExchangeMessage of(final String body) {
        return new ExchangeMessage(
                Objects.requireNonNull(body, "body"), CommunicationHeaders.empty(), null, null, null);
    }

    /**
     * Заголовки сообщения.
     *
     * @param value заголовки
     * @return новое сообщение
     */
    public ExchangeMessage withHeaders(final CommunicationHeaders value) {
        return new ExchangeMessage(this.body, Objects.requireNonNull(value, "headers"), this.id, this.at, this.status);
    }

    /**
     * Идентификатор сообщения в протоколе внешней системы — по нему сообщение ищут в её логах.
     *
     * @param value идентификатор
     * @return новое сообщение
     */
    public ExchangeMessage withId(final String value) {
        return new ExchangeMessage(this.body, this.headers, Objects.requireNonNull(value, "id"), this.at, this.status);
    }

    /**
     * Время сообщения, как оно стоит в самом сообщении или как оно ушло, — а не время захвата
     * попытки: между ними процессор строит запрос и ждёт ограничитель скорости.
     *
     * @param value время
     * @return новое сообщение
     */
    public ExchangeMessage withAt(final Instant value) {
        return new ExchangeMessage(this.body, this.headers, this.id, Objects.requireNonNull(value, "at"), this.status);
    }

    /**
     * Код ответа. Осмыслен у ответа REST и SOAP; у брокеров его нет. Записанный ответ с отказным
     * статусом делает попытку неуспешной (docs/adr/0065-communication-circuit-breaker.md).
     *
     * @param value код ответа
     * @return новое сообщение
     */
    public ExchangeMessage withStatus(final int value) {
        return new ExchangeMessage(this.body, this.headers, this.id, this.at, value);
    }

    /**
     * Сырое тело.
     *
     * @return тело как есть
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
    public Optional<String> id() {
        return Optional.ofNullable(this.id);
    }

    /**
     * Время сообщения.
     *
     * @return время или пусто
     */
    public Optional<Instant> at() {
        return Optional.ofNullable(this.at);
    }

    /**
     * Код ответа.
     *
     * @return код или пусто
     */
    public OptionalInt status() {
        return this.status == null ? OptionalInt.empty() : OptionalInt.of(this.status);
    }
}
