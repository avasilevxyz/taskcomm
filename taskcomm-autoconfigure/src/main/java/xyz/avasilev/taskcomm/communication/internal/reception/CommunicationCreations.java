package xyz.avasilev.taskcomm.communication.internal.reception;

import java.util.function.Supplier;
import xyz.avasilev.taskcomm.communication.api.Communication;
import xyz.avasilev.taskcomm.communication.api.CommunicationSendOptions;
import xyz.avasilev.taskcomm.communication.api.InboundRequest;
import xyz.avasilev.taskcomm.communication.api.ReceiveAndProcessResult;
import xyz.avasilev.taskcomm.communication.api.ReceiveResult;
import xyz.avasilev.taskcomm.communication.api.SendResult;
import xyz.avasilev.taskcomm.communication.internal.CommunicationCodec;
import xyz.avasilev.taskcomm.communication.internal.CommunicationRef;
import xyz.avasilev.taskcomm.communication.internal.Direction;
import xyz.avasilev.taskcomm.communication.internal.creation.CommunicationCreator;
import xyz.avasilev.taskcomm.communication.internal.creation.CreatedCommunication;
import xyz.avasilev.taskcomm.communication.internal.creation.NewCommunication;
import xyz.avasilev.taskcomm.communication.internal.inspection.CommunicationReader;
import xyz.avasilev.taskcomm.communication.internal.processor.BodyDecoding;
import xyz.avasilev.taskcomm.communication.internal.processor.DecodedBody;
import xyz.avasilev.taskcomm.communication.internal.processor.RegisteredProcessor;
import xyz.avasilev.taskcomm.communication.internal.service.DefaultCommunicationService;
import xyz.avasilev.taskcomm.communication.internal.settings.CommunicationSettings;

/**
 * Создание исходящих и приём входящих для {@link DefaultCommunicationService}: разбор тела в потоке
 * вызывающего, ключи дедупликации и корреляции, итоги с перечитанной строкой. Синхронный приём —
 * через {@link SyncReception}.
 */
public final class CommunicationCreations {

    private final Supplier<CommunicationSettings> settings;

    private final CommunicationCreator creator;

    private final CommunicationCodec codec;

    private final BodyDecoding decoding;

    private final CommunicationReader reader;

    private final SyncReception sync;

    public CommunicationCreations(
            final Supplier<CommunicationSettings> settings,
            final CommunicationCreator creator,
            final CommunicationCodec codec,
            final CommunicationReader reader,
            final SyncReception sync) {
        this.settings = settings;
        this.creator = creator;
        this.codec = codec;
        this.decoding = new BodyDecoding(codec);
        this.reader = reader;
        this.sync = sync;
    }

    /**
     * Создаёт исходящую.
     *
     * @param processor процессор системы-получателя
     * @param payload доменная форма запроса
     * @param options опции
     * @param <TRequest> доменная форма запроса
     * @return создана или дубликат
     */
    @SuppressWarnings("unchecked")
    public <TRequest> SendResult<TRequest> send(
            final RegisteredProcessor processor, final TRequest payload, final CommunicationSendOptions options) {
        final CreatedCommunication created = this.creator.create(new NewCommunication(
                Direction.OUTBOUND,
                processor.system(),
                this.codec.write(payload),
                null,
                new NewCommunication.Scheduling(
                        options.priority().orElseGet(() -> this.settings.get().priority(processor.system())),
                        this.settings.get().pollers().deadlineStep(processor.system()),
                        options.notBefore().orElse(null)),
                NewCommunication.Placement.of(options),
                NewCommunication.Start.OUTBOUND,
                new NewCommunication.Keys(options.dedupKeys(), options.correlationKeys())));
        final Communication.Outbound<TRequest> outbound =
                (Communication.Outbound<TRequest>) this.reread(created, processor);
        return created.duplicate() == null
                ? new SendResult.Created<>(outbound)
                : new SendResult.Duplicate<>(outbound, created.duplicate().duplicateOf());
    }

    /**
     * Асинхронный приём входящей.
     *
     * @param processor процессор системы-отправителя
     * @param request что прислали
     * @param <TRequest> доменная форма запроса
     * @return принята, дубликат или неразбираемое тело
     */
    @SuppressWarnings("unchecked")
    public <TRequest> ReceiveResult<TRequest> receive(
            final RegisteredProcessor processor, final InboundRequest request) {
        final DecodedBody decoded = this.decoding.decode(processor, request);
        final CreatedCommunication created = this.creator.create(this.inbound(
                processor,
                decoded,
                request,
                new NewCommunication.Start(BodyDecoding.message(request), decoded.failure(), null, null)));
        final Communication.Inbound<TRequest> inbound =
                (Communication.Inbound<TRequest>) this.reread(created, processor);
        if (created.duplicate() != null) {
            return new ReceiveResult.Duplicate<>(inbound, created.duplicate().duplicateOf());
        }
        return decoded.failure() == null
                ? new ReceiveResult.Accepted<>(inbound)
                : new ReceiveResult.Unparseable<>(inbound, decoded.failure());
    }

    /**
     * Синхронный приём входящей.
     *
     * @param processor процессор системы-отправителя
     * @param request что прислали
     * @param <TRequest> доменная форма запроса
     * @param <TSyncResponse> доменная форма ответа отправителю
     * @return итог приёма
     */
    public <TRequest, TSyncResponse> ReceiveAndProcessResult<TRequest, TSyncResponse> receiveAndProcess(
            final RegisteredProcessor processor, final InboundRequest request) {
        final DecodedBody decoded = this.decoding.decode(processor, request);
        return this.sync.receive(
                processor,
                decoded,
                BodyDecoding.message(request),
                start -> this.inbound(processor, decoded, request, start));
    }

    private Communication<?> reread(final CreatedCommunication created, final RegisteredProcessor processor) {
        return this.reader.one(
                new CommunicationRef(created.id(), created.execution().createdAt()), processor);
    }

    private NewCommunication inbound(
            final RegisteredProcessor processor,
            final DecodedBody decoded,
            final InboundRequest request,
            final NewCommunication.Start start) {
        return new NewCommunication(
                Direction.INBOUND,
                processor.system(),
                decoded.json(),
                processor.shape(),
                new NewCommunication.Scheduling(
                        this.settings.get().priority(processor.system()),
                        this.settings.get().pollers().deadlineStep(processor.system()),
                        null),
                NewCommunication.Placement.of(CommunicationSendOptions.defaults()),
                start,
                new NewCommunication.Keys(request.dedupKeys(), request.correlationKeys()));
    }
}
