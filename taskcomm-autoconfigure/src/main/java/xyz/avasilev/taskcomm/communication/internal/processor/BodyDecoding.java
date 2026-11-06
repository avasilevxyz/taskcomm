package xyz.avasilev.taskcomm.communication.internal.processor;

import java.util.function.Supplier;
import org.springframework.core.ResolvableType;
import org.springframework.util.ClassUtils;
import org.springframework.util.function.SingletonSupplier;
import xyz.avasilev.taskcomm.TaskcommException;
import xyz.avasilev.taskcomm.communication.api.CommunicationHeaders;
import xyz.avasilev.taskcomm.communication.api.ExchangeMessage;
import xyz.avasilev.taskcomm.communication.api.InboundRequest;
import xyz.avasilev.taskcomm.communication.api.processor.BodyDecoder;
import xyz.avasilev.taskcomm.communication.api.processor.KafkaCommunicationProcessor;
import xyz.avasilev.taskcomm.communication.api.processor.RawCommunicationProcessor;
import xyz.avasilev.taskcomm.communication.api.processor.RestCommunicationProcessor;
import xyz.avasilev.taskcomm.communication.api.processor.SoapCommunicationProcessor;
import xyz.avasilev.taskcomm.communication.internal.CommunicationCodec;
import xyz.avasilev.taskcomm.communication.internal.CommunicationSql;

/**
 * Разбор тела входящей при приёме, в потоке вызывающего, кодеком транспортного контракта
 * (docs/adr/0052-communication-transport-contracts.md, docs/adr/0134-communication-public-api-shape.md):
 * методы разбора процессора получают разбор по умолчанию — JSON у REST и Kafka, XML у SOAP.
 * Любое исключение из разбора — неразбираемое тело.
 */
public final class BodyDecoding {

    private final CommunicationCodec codec;

    private final Supplier<SoapBodyDecoder> soap;

    public BodyDecoding(final CommunicationCodec codec) {
        this.codec = codec;
        this.soap = SingletonSupplier.of(BodyDecoding::soapDecoder);
    }

    /**
     * Разбирает тело запроса.
     *
     * @param processor процессор системы
     * @param body сырое тело
     * @param headers заголовки
     * @return доменная форма запроса
     * @throws RuntimeException если тело не разбирается
     */
    @SuppressWarnings("unchecked")
    Object request(final RegisteredProcessor processor, final String body, final CommunicationHeaders headers) {
        final ResolvableType type = processor.types().request();
        return switch (processor.processor()) {
            case RestCommunicationProcessor<?, ?, ?> rest ->
                ((RestCommunicationProcessor<Object, ?, ?>) rest).decodeRequest(body, headers, this.json(type));
            case SoapCommunicationProcessor<?, ?, ?> soapProcessor ->
                ((SoapCommunicationProcessor<Object, ?, ?>) soapProcessor).decodeRequest(body, headers, this.xml(type));
            case KafkaCommunicationProcessor<?, ?, ?> kafka ->
                ((KafkaCommunicationProcessor<Object, ?, ?>) kafka).decodeRequest(body, headers, this.json(type));
            case RawCommunicationProcessor<?, ?, ?> raw -> raw.decodeRequest(body, headers);
        };
    }

    /**
     * Разбирает тело колбэка в доменную форму {@code A}.
     *
     * @param processor процессор системы
     * @param body сырое тело
     * @param headers заголовки
     * @return доменная форма колбэка
     * @throws RuntimeException если тело не разбирается
     */
    @SuppressWarnings("unchecked")
    public Object response(final RegisteredProcessor processor, final String body, final CommunicationHeaders headers) {
        final ResolvableType type = processor.types().callback();
        return switch (processor.processor()) {
            case RestCommunicationProcessor<?, ?, ?> rest ->
                ((RestCommunicationProcessor<?, ?, Object>) rest).decodeResponse(body, headers, this.json(type));
            case SoapCommunicationProcessor<?, ?, ?> soapProcessor ->
                ((SoapCommunicationProcessor<?, ?, Object>) soapProcessor)
                        .decodeResponse(body, headers, this.xml(type));
            case KafkaCommunicationProcessor<?, ?, ?> kafka ->
                ((KafkaCommunicationProcessor<?, ?, Object>) kafka).decodeResponse(body, headers, this.json(type));
            case RawCommunicationProcessor<?, ?, ?> raw -> raw.decodeResponse(body, headers);
        };
    }

    /**
     * Разбирает тело входящей: исключение разбора
     * — неразобранное тело; ошибка записи JSON — нет,
     * она бросается.
     *
     * @param processor процессор системы
     * @param request значение-запрос приёма
     * @return итог разбора
     */
    public DecodedBody decode(final RegisteredProcessor processor, final InboundRequest request) {
        return this.decode(processor, request.body(), request.headers());
    }

    /**
     * Разбирает тело входящей по сырому телу и заголовкам.
     *
     * @param processor процессор системы
     * @param body сырое тело
     * @param headers заголовки
     * @return итог разбора
     */
    public DecodedBody decode(
            final RegisteredProcessor processor, final String body, final CommunicationHeaders headers) {
        final Object payload;
        try {
            payload = this.request(processor, body, headers);
        } catch (final RuntimeException failure) {
            return new DecodedBody(null, null, "тело не разбирается: " + CommunicationSql.describe(failure));
        }
        return new DecodedBody(payload, this.codec.write(payload), null);
    }

    /**
     * Присланное сообщение
     * — запрос первой попытки входящей.
     *
     * @param request значение-запрос приёма
     * @return сообщение
     */
    public static ExchangeMessage message(final InboundRequest request) {
        final ExchangeMessage plain = ExchangeMessage.of(request.body()).withHeaders(request.headers());
        final ExchangeMessage identified =
                request.requestId().map(plain::withId).orElse(plain);
        return request.requestAt().map(identified::withAt).orElse(identified);
    }

    private BodyDecoder<Object> json(final ResolvableType type) {
        return body -> this.codec.decodeJson(body, type);
    }

    private BodyDecoder<Object> xml(final ResolvableType type) {
        return body -> this.soap.get().decode(body, type);
    }

    private static SoapBodyDecoder soapDecoder() {
        if (!ClassUtils.isPresent(CommunicationProcessorRegistry.XML_MAPPER, BodyDecoding.class.getClassLoader())) {
            throw new TaskcommException(
                    "Разбор SOAP по умолчанию требует tools.jackson.dataformat:jackson-dataformat-xml "
                            + "(docs/adr/0135-communication-engine.md).");
        }
        return new SoapBodyDecoder();
    }
}
