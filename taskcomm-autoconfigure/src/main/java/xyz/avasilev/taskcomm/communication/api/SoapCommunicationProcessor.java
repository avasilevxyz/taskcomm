package xyz.avasilev.taskcomm.communication.api;

/**
 * Процессор внешней системы по SOAP. Форма контракта в базе — {@code SOAP}. Тело по умолчанию —
 * XML, его разбирает XML-маппер стартера; другой разбор — переопределите методы разбора.
 *
 * @param <C> доменная форма запроса
 * @param <R> доменная форма синхронного ответа
 * @param <A> доменная форма колбэка; {@link Void} у не ждущего ответа
 */
public non-sealed interface SoapCommunicationProcessor<C, R, A> extends HttpCommunicationProcessor<C, R, A> {

    /**
     * Разбирает тело входящей при приёме, в потоке вызывающего. Исключение — неразбираемое тело:
     * коммуникация создаётся сразу {@code FAILED}.
     *
     * @param body сырое тело
     * @param headers заголовки
     * @param xml разбор XML в тип запроса
     * @return доменная форма запроса
     */
    default C decodeRequest(final String body, final CommunicationHeaders headers, final BodyDecoder<C> xml) {
        return xml.decode(body);
    }

    /**
     * Разбирает тело колбэка при приёме, в потоке вызывающего.
     *
     * @param body сырое тело
     * @param headers заголовки
     * @param xml разбор XML в тип колбэка
     * @return доменная форма колбэка
     */
    default A decodeResponse(final String body, final CommunicationHeaders headers, final BodyDecoder<A> xml) {
        return xml.decode(body);
    }
}
