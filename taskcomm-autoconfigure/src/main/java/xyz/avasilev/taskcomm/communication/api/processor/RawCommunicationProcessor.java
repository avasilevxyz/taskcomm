package xyz.avasilev.taskcomm.communication.api.processor;

import xyz.avasilev.taskcomm.communication.api.CommunicationHeaders;

/**
 * Формат-агностичный процессор: получает сырой текст и разбирает его сам. Форма контракта в базе —
 * {@code RAW}. Это выход для транспорта, которого в иерархии нет: своего транспорта потребитель
 * добавить не может (docs/adr/0052-communication-transport-contracts.md). Отказных статусов
 * по умолчанию нет — смысл статуса знает только автор процессора.
 *
 * @param <TRequest> доменная форма запроса
 * @param <TSyncResponse> доменная форма синхронного ответа
 * @param <TAsyncResponse> доменная форма колбэка; {@link Void} у не ждущего ответа
 */
public non-sealed interface RawCommunicationProcessor<TRequest, TSyncResponse, TAsyncResponse>
        extends CommunicationProcessor<TRequest, TSyncResponse, TAsyncResponse> {

    /**
     * Разбирает тело входящей при приёме, в потоке вызывающего. Исключение — неразбираемое тело:
     * коммуникация создаётся сразу {@code FAILED}.
     *
     * @param body сырое тело
     * @param headers заголовки
     * @return доменная форма запроса
     */
    TRequest decodeRequest(String body, CommunicationHeaders headers);

    /**
     * Разбирает тело колбэка при приёме, в потоке вызывающего. Процессор, не ждущий ответа, может
     * бросить исключение — колбэк станет неразбираемым.
     *
     * @param body сырое тело
     * @param headers заголовки
     * @return доменная форма колбэка
     */
    TAsyncResponse decodeResponse(String body, CommunicationHeaders headers);
}
