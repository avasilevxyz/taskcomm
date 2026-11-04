package xyz.avasilev.taskcomm.communication.api;

/**
 * Процессор внешней системы через Kafka. Форма контракта в базе — {@code KAFKA}. Тело по умолчанию —
 * JSON, его разбирает маппер нагрузки стартера; другой формат — переопределите методы разбора.
 * Статуса ответа у брокера нет. Типов {@code kafka-clients} в контракте нет и не будет:
 * сообщение приходит сырым текстом и {@link CommunicationHeaders}.
 *
 * @param <C> доменная форма запроса
 * @param <R> доменная форма синхронного ответа
 * @param <A> доменная форма колбэка; {@link Void} у не ждущего ответа
 */
public non-sealed interface KafkaCommunicationProcessor<C, R, A> extends CommunicationProcessor<C, R, A> {

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
