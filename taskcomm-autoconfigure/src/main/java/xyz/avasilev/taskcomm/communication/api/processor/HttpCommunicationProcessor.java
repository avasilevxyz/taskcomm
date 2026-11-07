package xyz.avasilev.taskcomm.communication.api.processor;

/**
 * Процессор внешней системы по HTTP — общий предок REST и SOAP. Своих методов не добавляет: нужен,
 * чтобы воркер мог обращаться с HTTP-транспортами одинаково там, где они одинаковы, — у ответа
 * есть код, и отказные статусы по умолчанию — {@code 5xx} и {@code 429}
 * (docs/adr/0065-communication-circuit-breaker.md).
 *
 * @param <TRequest> доменная форма запроса
 * @param <TSyncResponse> доменная форма синхронного ответа
 * @param <TAsyncResponse> доменная форма колбэка; {@link Void} у не ждущего ответа
 */
public sealed interface HttpCommunicationProcessor<TRequest, TSyncResponse, TAsyncResponse>
        extends CommunicationProcessor<TRequest, TSyncResponse, TAsyncResponse>
        permits RestCommunicationProcessor, SoapCommunicationProcessor {}
