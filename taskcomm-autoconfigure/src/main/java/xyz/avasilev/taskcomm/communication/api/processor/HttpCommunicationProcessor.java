package xyz.avasilev.taskcomm.communication.api.processor;

/**
 * Процессор внешней системы по HTTP — общий предок REST и SOAP. Своих методов не добавляет: нужен,
 * чтобы воркер мог обращаться с HTTP-транспортами одинаково там, где они одинаковы, — у ответа
 * есть код, и отказные статусы по умолчанию — {@code 5xx} и {@code 429}
 * (docs/adr/0065-communication-circuit-breaker.md).
 *
 * @param <C> доменная форма запроса
 * @param <R> доменная форма синхронного ответа
 * @param <A> доменная форма колбэка; {@link Void} у не ждущего ответа
 */
public sealed interface HttpCommunicationProcessor<C, R, A> extends CommunicationProcessor<C, R, A>
        permits RestCommunicationProcessor, SoapCommunicationProcessor {}
