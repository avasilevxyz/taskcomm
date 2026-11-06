package xyz.avasilev.taskcomm.communication.api.processor;

import xyz.avasilev.taskcomm.communication.api.CommunicationHeaders;

/**
 * Процессор внешней системы по REST. Форма контракта в базе — {@code REST}. Тело по умолчанию — JSON,
 * его разбирает маппер нагрузки стартера; другой формат — переопределите методы разбора.
 *
 * <pre>{@code
 * @Component
 * public class SupplierProcessor implements RestCommunicationProcessor<
 *         PriceUpdate,   // C — запрос
 *         Ack,           // R — синхронный ответ
 *         Void> {        // A — колбэка нет
 *
 *     public String externalSystem() {
 *         return "supplier-b";
 *     }
 *
 *     public Ack process(Communication<PriceUpdate> communication, CommunicationContext context) {
 *         return switch (communication) {
 *             case Communication.Outbound<PriceUpdate> outbound -> client.send(outbound.requestPayload(), context);
 *             case Communication.Inbound<PriceUpdate> inbound -> prices.apply(inbound.requestPayload());
 *         };
 *     }
 * }
 * }</pre>
 *
 * @param <C> доменная форма запроса
 * @param <R> доменная форма синхронного ответа
 * @param <A> доменная форма колбэка; {@link Void} у не ждущего ответа
 */
public non-sealed interface RestCommunicationProcessor<C, R, A> extends HttpCommunicationProcessor<C, R, A> {

    /**
     * Разбирает тело входящей при приёме, в потоке вызывающего. Исключение — неразбираемое тело:
     * коммуникация создаётся сразу {@code FAILED}.
     *
     * @param body сырое тело
     * @param headers заголовки
     * @param json разбор JSON в тип запроса
     * @return доменная форма запроса
     */
    default C decodeRequest(final String body, final CommunicationHeaders headers, final BodyDecoder<C> json) {
        return json.decode(body);
    }

    /**
     * Разбирает тело колбэка при приёме, в потоке вызывающего.
     *
     * @param body сырое тело
     * @param headers заголовки
     * @param json разбор JSON в тип колбэка
     * @return доменная форма колбэка
     */
    default A decodeResponse(final String body, final CommunicationHeaders headers, final BodyDecoder<A> json) {
        return json.decode(body);
    }
}
