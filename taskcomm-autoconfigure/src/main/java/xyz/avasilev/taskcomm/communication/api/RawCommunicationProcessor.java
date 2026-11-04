package xyz.avasilev.taskcomm.communication.api;

/**
 * Формат-агностичный процессор: получает сырой текст и разбирает его сам. Форма контракта в базе —
 * {@code RAW}. Это выход для транспорта, которого в иерархии нет: своего транспорта потребитель
 * добавить не может (docs/adr/0052-communication-transport-contracts.md). Отказных статусов
 * по умолчанию нет — смысл статуса знает только автор процессора.
 *
 * @param <C> доменная форма запроса
 * @param <R> доменная форма синхронного ответа
 * @param <A> доменная форма колбэка; {@link Void} у не ждущего ответа
 */
public non-sealed interface RawCommunicationProcessor<C, R, A> extends CommunicationProcessor<C, R, A> {

    /**
     * Разбирает тело входящей при приёме, в потоке вызывающего. Исключение — неразбираемое тело:
     * коммуникация создаётся сразу {@code FAILED}.
     *
     * @param body сырое тело
     * @param headers заголовки
     * @return доменная форма запроса
     */
    C decodeRequest(String body, CommunicationHeaders headers);

    /**
     * Разбирает тело колбэка при приёме, в потоке вызывающего. Процессор, не ждущий ответа, может
     * бросить исключение — колбэк станет неразбираемым.
     *
     * @param body сырое тело
     * @param headers заголовки
     * @return доменная форма колбэка
     */
    A decodeResponse(String body, CommunicationHeaders headers);
}
