package xyz.avasilev.taskcomm.communication.api.processor;

import xyz.avasilev.taskcomm.communication.api.CommunicationHeaders;

/**
 * Процессор внешней системы через Kafka. Форма контракта в базе — {@code KAFKA}. Тело по умолчанию —
 * JSON, его разбирает маппер нагрузки стартера; другой формат — переопределите методы разбора.
 * Статуса ответа у брокера нет. Типов {@code kafka-clients} в контракте нет и не будет:
 * сообщение приходит сырым текстом и {@link CommunicationHeaders}.
 *
 * @param <TRequest> доменная форма запроса
 * @param <TSyncResponse> доменная форма синхронного ответа
 * @param <TAsyncResponse> доменная форма колбэка; {@link Void} у не ждущего ответа
 */
public non-sealed interface KafkaCommunicationProcessor<TRequest, TSyncResponse, TAsyncResponse>
        extends CommunicationProcessor<TRequest, TSyncResponse, TAsyncResponse> {

    /**
     * Разбирает тело входящей при приёме, в потоке вызывающего. Исключение — неразбираемое тело:
     * коммуникация создаётся сразу {@code FAILED}.
     *
     * @param body сырое тело
     * @param headers заголовки
     * @param json разбор JSON в тип запроса
     * @return доменная форма запроса
     */
    default TRequest decodeRequest(
            final String body, final CommunicationHeaders headers, final BodyDecoder<TRequest> json) {
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
    default TAsyncResponse decodeResponse(
            final String body, final CommunicationHeaders headers, final BodyDecoder<TAsyncResponse> json) {
        return json.decode(body);
    }
}
