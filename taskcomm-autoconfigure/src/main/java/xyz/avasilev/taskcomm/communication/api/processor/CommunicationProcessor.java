package xyz.avasilev.taskcomm.communication.api.processor;

import xyz.avasilev.taskcomm.communication.api.Communication;

/**
 * Процессор коммуникаций — адаптер потребителя к одной внешней системе. Запечатанный по транспорту
 * контракт (docs/adr/0052-communication-transport-contracts.md): реализуется один из четырёх
 * наследников, и транспорт задаёт, каким кодеком разбирать тело при приёме.
 *
 * <ul>
 *   <li>{@link RestCommunicationProcessor}, {@link SoapCommunicationProcessor} — через
 *       {@link HttpCommunicationProcessor};
 *   <li>{@link KafkaCommunicationProcessor};
 *   <li>{@link RawCommunicationProcessor} — формат знает только автор, разбор целиком его.
 * </ul>
 *
 * <p><b>Один процессор — одна внешняя система</b>, в обоих направлениях: входящие от неё и исходящие
 * к ней приходят в один {@link #process}, направление разбирается по типу коммуникации. Два процессора
 * с одной {@link #externalSystem()} — падение при старте. Процессор не удаляется никогда, пока в базе
 * есть коммуникации его системы. Имя системы, поля {@code TRequest} и транспорт — формат уже
 * записанных данных: у живой системы их не меняют, часть таких правок теряет данные молча
 * (<a href="https://github.com/avasilevxyz/taskcomm/blob/v2.0.2/docs/guide/evolution/compatibility.md">Совместимость</a>).
 *
 * <p><b>Гарантия — at-least-once</b>: аренда может истечь у живого, но медленного воркера, и ту же
 * коммуникацию подберёт другой. {@link #process} и {@link #handleResponse} обязаны быть идемпотентными;
 * дедупликация от этого не освобождает — она защищает от повторной доставки, а не от повторного
 * выполнения.
 *
 * <p><b>У клиента внешней системы обязан быть тайм-аут, и короче предела обработки</b>: прервать код
 * процессора стартер не может.
 *
 * @param <TRequest> доменная форма запроса
 * @param <TSyncResponse> доменная форма синхронного ответа: у исходящей — ответ внешней системы, у входящей —
 *     ответ отправителю (docs/adr/0133-synchronous-inbound-reception.md); {@link Void} — не хранить
 * @param <TAsyncResponse> доменная форма асинхронного ответа — колбэка на исходящую
 *     (docs/adr/0086-async-response-in-outbound.md); {@link Void} у процессора, не ждущего ответа
 */
public sealed interface CommunicationProcessor<TRequest, TSyncResponse, TAsyncResponse>
        permits HttpCommunicationProcessor, KafkaCommunicationProcessor, RawCommunicationProcessor {

    /**
     * Внешняя система, которую обслуживает процессор, — {@code source} входящих и {@code destination}
     * исходящих, ключ настроек {@code systems.<имя>}. Не меняется у работающей системы.
     *
     * @return имя внешней системы
     */
    String externalSystem();

    /**
     * Вызывается движком при захвате <b>перед</b> {@link #process}, чтобы задать ключи дедупликации
     * методами контекста до любых побочных эффектов (docs/adr/0076-communication-deduplication.md).
     * Дубликат — коммуникация {@code SKIPPED}, {@code process} не вызывается. По умолчанию ключей нет.
     *
     * @param communication коммуникация
     * @param context контекст обработки
     */
    default void beforeProcess(final Communication<TRequest> communication, final CommunicationContext context) {
        // ключей нет
    }

    /**
     * Обрабатывает коммуникацию. У исходящей — отправляет запрос во внешнюю систему, записывая обмен
     * методами контекста; у входящей — обрабатывает присланное.
     *
     * <p>Нормальный возврат — успех, если записанный ответ не отказной; исключение — неуспешная
     * попытка с повтором по настройкам, если повтор не запрещён
     * {@link CommunicationContext#doNotRetry(String)}. Возвращённое значение пишется в
     * {@code response_payload} попытки; у синхронного приёма входящей его же получает вызывающий,
     * чтобы ответить отправителю как есть.
     *
     * @param communication коммуникация; направление — её тип
     * @param context контекст обработки
     * @return доменная форма ответа; {@code null} не хранится
     */
    TSyncResponse process(Communication<TRequest> communication, CommunicationContext context);

    /**
     * Обрабатывает асинхронный ответ на исходящую — колбэк, пришедший той же системой с ключом
     * корреляции. Вызывается движком под арендой, с heartbeat и повторами, как {@link #process};
     * идемпотентность обязательна так же. По умолчанию ничего не делает: колбэк сохранён,
     * коммуникация становится {@code SUCCEEDED}.
     *
     * @param communication исходящая, ждавшая ответа
     * @param response колбэк, разобранный в доменную форму
     * @param context контекст обработки; {@code response_*} в нём — что мы ответили отправителю колбэка
     */
    default void handleResponse(
            final Communication.Outbound<TRequest> communication,
            final TAsyncResponse response,
            final CommunicationContext context) {
        // колбэк сохранён, обрабатывать нечего
    }
}
