package xyz.avasilev.taskcomm.communication.api.processor;

import xyz.avasilev.taskcomm.communication.api.CommunicationHeaders;

/**
 * Процессор внешней системы по SOAP. Форма контракта в базе — {@code SOAP}. Тело по умолчанию —
 * XML, его разбирает XML-маппер стартера; другой разбор — переопределите методы разбора.
 *
 * @param <TRequest> доменная форма запроса
 * @param <TSyncResponse> доменная форма синхронного ответа
 * @param <TAsyncResponse> доменная форма колбэка; {@link Void} у не ждущего ответа
 */
public non-sealed interface SoapCommunicationProcessor<TRequest, TSyncResponse, TAsyncResponse>
        extends HttpCommunicationProcessor<TRequest, TSyncResponse, TAsyncResponse> {

    /**
     * Разбирает тело входящей при приёме, в потоке вызывающего. Исключение — неразбираемое тело:
     * коммуникация создаётся сразу {@code FAILED}.
     *
     * @param body сырое тело
     * @param headers заголовки
     * @param xml разбор XML в тип запроса
     * @return доменная форма запроса
     */
    default TRequest decodeRequest(
            final String body, final CommunicationHeaders headers, final BodyDecoder<TRequest> xml) {
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
    default TAsyncResponse decodeResponse(
            final String body, final CommunicationHeaders headers, final BodyDecoder<TAsyncResponse> xml) {
        return xml.decode(body);
    }
}
