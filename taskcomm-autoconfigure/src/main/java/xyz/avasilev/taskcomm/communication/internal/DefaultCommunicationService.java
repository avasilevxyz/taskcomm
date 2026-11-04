package xyz.avasilev.taskcomm.communication.internal;

import java.util.List;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.function.SingletonSupplier;
import xyz.avasilev.taskcomm.communication.api.CallbackRequest;
import xyz.avasilev.taskcomm.communication.api.Communication;
import xyz.avasilev.taskcomm.communication.api.CommunicationProcessor;
import xyz.avasilev.taskcomm.communication.api.CommunicationSendOptions;
import xyz.avasilev.taskcomm.communication.api.CommunicationService;
import xyz.avasilev.taskcomm.communication.api.InboundRequest;
import xyz.avasilev.taskcomm.communication.api.ReceiveAndProcessResult;
import xyz.avasilev.taskcomm.communication.api.ReceiveResponseResult;
import xyz.avasilev.taskcomm.communication.api.ReceiveResult;
import xyz.avasilev.taskcomm.communication.api.SendResult;
import xyz.avasilev.taskcomm.execution.api.ExecutionRef;
import xyz.avasilev.taskcomm.execution.internal.UuidV7;

/**
 * Реализация {@link CommunicationService}: создание исходящих, два метода приёма входящих, чтение
 * коммуникаций дела. Разбор тела — здесь, в потоке вызывающего (docs/adr/0074-uniform-communication-processing.md);
 * синхронный приём — {@link SyncReception}.
 *
 * <p>Ключи дедупликации и корреляции и приём колбэка — этап 5c: до него бросают
 * {@link UnsupportedOperationException} до записи (docs/adr/0135-communication-engine.md).
 */
public class DefaultCommunicationService implements CommunicationService {

    private final Supplier<CommunicationProcessorRegistry> registry;

    private final Supplier<CommunicationSettings> settings;

    private final CommunicationCreator creator;

    private final CommunicationCodec codec;

    private final BodyDecoding decoding;

    private final CommunicationReader reader;

    private final SyncReception sync;

    /**
     * Собирает сервис.
     *
     * @param registry реестр процессоров — лениво
     * @param settings итоговые настройки — лениво
     * @param engine движок — его воркер и heartbeat обслуживают синхронный приём; лениво
     * @param creator создание коммуникаций
     * @param codec кодек доменных форм
     * @param ids генератор идентификаторов
     * @param dataSource {@code DataSource} стартера
     */
    public DefaultCommunicationService(
            final Supplier<CommunicationProcessorRegistry> registry,
            final Supplier<CommunicationSettings> settings,
            final Supplier<CommunicationEngine> engine,
            final CommunicationCreator creator,
            final CommunicationCodec codec,
            final UuidV7 ids,
            final DataSource dataSource) {
        this.registry = SingletonSupplier.of(registry);
        this.settings = SingletonSupplier.of(settings);
        this.creator = creator;
        this.codec = codec;
        this.decoding = new BodyDecoding(codec);
        this.reader = new CommunicationReader(dataSource, codec);
        this.sync = new SyncReception(this.settings, SingletonSupplier.of(engine), creator, this.reader, ids);
    }

    @Override
    public <C> SendResult<C> send(final String destination, final C payload) {
        return this.send(destination, payload, CommunicationSendOptions.defaults());
    }

    @Override
    public <C> SendResult<C> send(final String destination, final C payload, final ExecutionRef execution) {
        return this.send(
                destination, payload, CommunicationSendOptions.defaults().inExecution(execution));
    }

    @Override
    public <C> SendResult<C> send(final String destination, final C payload, final CommunicationSendOptions options) {
        return this.send(this.registry.get().forCreate(destination), payload, options);
    }

    @Override
    public <C> SendResult<C> send(final Class<? extends CommunicationProcessor<C, ?, ?>> processor, final C payload) {
        return this.send(processor, payload, CommunicationSendOptions.defaults());
    }

    @Override
    public <C> SendResult<C> send(
            final Class<? extends CommunicationProcessor<C, ?, ?>> processor,
            final C payload,
            final CommunicationSendOptions options) {
        return this.send(this.registry.get().forCreate(processor), payload, options);
    }

    @Override
    public ReceiveResult<?> receive(final String source, final InboundRequest request) {
        return this.receive(this.registry.get().forCreate(source), request);
    }

    @Override
    public <C> ReceiveResult<C> receive(
            final Class<? extends CommunicationProcessor<C, ?, ?>> processor, final InboundRequest request) {
        return this.receive(this.registry.get().forCreate(processor), request);
    }

    @Override
    public ReceiveAndProcessResult<?, ?> receiveAndProcess(final String source, final InboundRequest request) {
        SyncReception.requireOutsideTransaction(TransactionSynchronizationManager.isActualTransactionActive());
        return this.receiveAndProcess(this.registry.get().forCreate(source), request);
    }

    @Override
    public <C, R> ReceiveAndProcessResult<C, R> receiveAndProcess(
            final Class<? extends CommunicationProcessor<C, R, ?>> processor, final InboundRequest request) {
        SyncReception.requireOutsideTransaction(TransactionSynchronizationManager.isActualTransactionActive());
        return this.receiveAndProcess(this.registry.get().forCreate(processor), request);
    }

    @Override
    public ReceiveResponseResult receiveResponse(final String source, final CallbackRequest request) {
        throw new UnsupportedOperationException("receiveResponse" + NewCommunication.NOT_YET);
    }

    @Override
    public List<Communication<?>> findInExecution(final ExecutionRef execution, final String externalSystem) {
        return this.reader.inExecution(execution, this.registry.get().forRead(externalSystem));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <C> List<Communication<C>> findInExecution(
            final ExecutionRef execution, final Class<? extends CommunicationProcessor<C, ?, ?>> processor) {
        return this.reader.inExecution(execution, this.registry.get().forCreate(processor)).stream()
                .map(communication -> (Communication<C>) communication)
                .toList();
    }

    private <C> SendResult<C> send(
            final RegisteredProcessor processor, final C payload, final CommunicationSendOptions options) {
        NewCommunication.requireNoKeys(
                !options.dedupKeys().isEmpty() || !options.correlationKeys().isEmpty());
        final NewCommunication.Placement placement = NewCommunication.Placement.of(options);
        final int priority =
                options.priority().orElseGet(() -> this.settings.get().priority(processor.system()));
        final NewCommunication.Scheduling scheduling = new NewCommunication.Scheduling(
                priority,
                this.settings.get().pollers().deadlineStep(processor.system()),
                options.notBefore().orElse(null));
        final CreatedCommunication created = this.creator.create(new NewCommunication(
                Direction.OUTBOUND,
                processor.system(),
                this.codec.write(payload),
                null,
                scheduling,
                placement,
                NewCommunication.Start.OUTBOUND));
        return new SendResult.Created<>(CommunicationViews.outbound(
                CommunicationViews.created(created, processor.system(), priority, placement.origin(), null), payload));
    }

    @SuppressWarnings("unchecked")
    private <C> ReceiveResult<C> receive(final RegisteredProcessor processor, final InboundRequest request) {
        NewCommunication.requireNoKeys(
                !request.dedupKeys().isEmpty() || !request.correlationKeys().isEmpty());
        final DecodedBody decoded = this.decoding.decode(processor, request);
        final NewCommunication communication = this.inbound(
                processor,
                decoded,
                new NewCommunication.Start(BodyDecoding.message(request), decoded.failure(), null, null));
        final CreatedCommunication created = this.creator.create(communication);
        final Communication.Inbound<C> inbound = CommunicationViews.inbound(
                CommunicationViews.created(
                        created,
                        processor.system(),
                        communication.scheduling().priority(),
                        communication.placement().origin(),
                        decoded.failure()),
                (C) decoded.payload());
        return decoded.failure() == null
                ? new ReceiveResult.Accepted<>(inbound)
                : new ReceiveResult.Unparseable<>(inbound, decoded.failure());
    }

    private <C, R> ReceiveAndProcessResult<C, R> receiveAndProcess(
            final RegisteredProcessor processor, final InboundRequest request) {
        NewCommunication.requireNoKeys(
                !request.dedupKeys().isEmpty() || !request.correlationKeys().isEmpty());
        final DecodedBody decoded = this.decoding.decode(processor, request);
        return this.sync.receive(
                processor, decoded, BodyDecoding.message(request), start -> this.inbound(processor, decoded, start));
    }

    private NewCommunication inbound(
            final RegisteredProcessor processor, final DecodedBody decoded, final NewCommunication.Start start) {
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
                start);
    }
}
