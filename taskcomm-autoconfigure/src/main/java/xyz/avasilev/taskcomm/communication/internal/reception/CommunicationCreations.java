package xyz.avasilev.taskcomm.communication.internal.reception;

import java.util.function.Supplier;
import xyz.avasilev.taskcomm.communication.api.Communication;
import xyz.avasilev.taskcomm.communication.api.CommunicationSendOptions;
import xyz.avasilev.taskcomm.communication.api.InboundRequest;
import xyz.avasilev.taskcomm.communication.api.ReceiveAndProcessResult;
import xyz.avasilev.taskcomm.communication.api.ReceiveResult;
import xyz.avasilev.taskcomm.communication.api.SendAndProcessResult;
import xyz.avasilev.taskcomm.communication.api.SendResult;
import xyz.avasilev.taskcomm.communication.api.SyncSendOptions;
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
 * вызывающего, ключи дедупликации и корреляции, итоги с перечитанной строкой. Синхронные приём
 * и отправка — через {@link SyncReception} и {@link SyncSending}, вне транзакции вызывающего.
 */
public final class CommunicationCreations {

    private final Supplier<CommunicationSettings> settings;

    private final CommunicationCreator creator;

    private final CommunicationCodec codec;

    private final BodyDecoding decoding;

    private final CommunicationReader reader;

    private final SyncRuns sync;

    /**
     * Собирает создание и приём.
     *
     * @param settings итоговые настройки — лениво
     * @param creator создание коммуникаций
     * @param codec кодек доменных форм
     * @param reader чтение коммуникации для итогов
     * @param sync синхронный запуск
     */
    public CommunicationCreations(
            final Supplier<CommunicationSettings> settings,
            final CommunicationCreator creator,
            final CommunicationCodec codec,
            final CommunicationReader reader,
            final SyncRuns sync) {
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
        final CreatedCommunication created = this.creator.create(
                this.outbound(processor, this.codec.write(payload), false, options, NewCommunication.Start.OUTBOUND));
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
     * @throws xyz.avasilev.taskcomm.TaskcommException если вызван внутри активной транзакции
     */
    public <TRequest, TSyncResponse> ReceiveAndProcessResult<TRequest, TSyncResponse> receiveAndProcess(
            final RegisteredProcessor processor, final InboundRequest request) {
        SyncAttempts.requireOutsideTransaction();
        final DecodedBody decoded = this.decoding.decode(processor, request);
        return this.sync
                .reception()
                .receive(
                        processor,
                        decoded,
                        BodyDecoding.message(request),
                        start -> this.inbound(processor, decoded, request, start));
    }

    /**
     * Синхронная отправка исходящей (docs/adr/0167-communication-run-mode.md).
     *
     * @param processor процессор системы-получателя
     * @param payload доменная форма запроса
     * @param options параметры синхронной отправки
     * @param <TRequest> доменная форма запроса
     * @return итог отправки
     * @throws IllegalArgumentException если задано «не раньше»
     * @throws xyz.avasilev.taskcomm.TaskcommException если вызван внутри активной транзакции
     */
    public <TRequest> SendAndProcessResult<TRequest, ?, ?> sendAndProcess(
            final RegisteredProcessor processor, final TRequest payload, final SyncSendOptions options) {
        SyncAttempts.requireOutsideTransaction();
        final CommunicationSendOptions send = options.send();
        if (send.notBefore().isPresent()) {
            throw new IllegalArgumentException("У синхронной отправки не бывает «не раньше»: ответ нужен сейчас. "
                    + "Отложенную отправку делайте асинхронной — методом send.");
        }
        final String json = this.codec.write(payload);
        return this.sync.sending().send(processor, start -> this.outbound(processor, json, true, send, start), options);
    }

    /** Исходящая к созданию — одна у асинхронной и синхронной отправки. */
    private NewCommunication outbound(
            final RegisteredProcessor processor,
            final String json,
            final boolean shapeNow,
            final CommunicationSendOptions options,
            final NewCommunication.Start start) {
        return new NewCommunication(
                Direction.OUTBOUND,
                processor.system(),
                json,
                // Форму контракта исходящей пишет первый захват; синхронную захват не берёт — пишем сразу.
                shapeNow ? processor.shape() : null,
                new NewCommunication.Scheduling(
                        options.priority().orElseGet(() -> this.settings.get().priority(processor.system())),
                        this.settings.get().pollers().deadlineStep(processor.system()),
                        options.notBefore().orElse(null)),
                NewCommunication.Placement.of(options),
                start,
                new NewCommunication.Keys(options.dedupKeys(), options.correlationKeys()));
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
