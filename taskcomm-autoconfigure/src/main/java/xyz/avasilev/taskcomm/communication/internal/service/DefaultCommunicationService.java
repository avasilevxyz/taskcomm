package xyz.avasilev.taskcomm.communication.internal.service;

import java.util.List;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.function.SingletonSupplier;
import xyz.avasilev.taskcomm.communication.api.CallbackRequest;
import xyz.avasilev.taskcomm.communication.api.Communication;
import xyz.avasilev.taskcomm.communication.api.CommunicationCancelResult;
import xyz.avasilev.taskcomm.communication.api.CommunicationRestartResult;
import xyz.avasilev.taskcomm.communication.api.CommunicationSendOptions;
import xyz.avasilev.taskcomm.communication.api.CommunicationService;
import xyz.avasilev.taskcomm.communication.api.InboundRequest;
import xyz.avasilev.taskcomm.communication.api.ReceiveAndProcessResult;
import xyz.avasilev.taskcomm.communication.api.ReceiveResponseResult;
import xyz.avasilev.taskcomm.communication.api.ReceiveResult;
import xyz.avasilev.taskcomm.communication.api.SendAndProcessResult;
import xyz.avasilev.taskcomm.communication.api.SendResult;
import xyz.avasilev.taskcomm.communication.api.SyncSendOptions;
import xyz.avasilev.taskcomm.communication.api.inspection.CommunicationSnapshot;
import xyz.avasilev.taskcomm.communication.api.processor.CommunicationProcessor;
import xyz.avasilev.taskcomm.communication.internal.CommunicationCodec;
import xyz.avasilev.taskcomm.communication.internal.CommunicationRef;
import xyz.avasilev.taskcomm.communication.internal.creation.CommunicationCreator;
import xyz.avasilev.taskcomm.communication.internal.engine.CommunicationEngine;
import xyz.avasilev.taskcomm.communication.internal.inspection.CommunicationReader;
import xyz.avasilev.taskcomm.communication.internal.processor.CommunicationProcessorRegistry;
import xyz.avasilev.taskcomm.communication.internal.processor.RegisteredProcessor;
import xyz.avasilev.taskcomm.communication.internal.reception.CommunicationCallbacks;
import xyz.avasilev.taskcomm.communication.internal.reception.CommunicationCreations;
import xyz.avasilev.taskcomm.communication.internal.reception.SyncRuns;
import xyz.avasilev.taskcomm.communication.internal.settings.CommunicationSettings;
import xyz.avasilev.taskcomm.execution.api.ExecutionRef;
import xyz.avasilev.taskcomm.execution.internal.UuidV7;

/**
 * Реализация {@link CommunicationService}: создание исходящих, два метода приёма входящих, чтение
 * коммуникаций дела. Разбор тела — здесь, в потоке вызывающего (docs/adr/0074-uniform-communication-processing.md);
 * синхронные приём и отправка — там же, вне транзакции вызывающего.
 *
 * <p>Создание и приём — {@link CommunicationCreations}, приём колбэка — {@link CommunicationCallbacks}.
 */
public class DefaultCommunicationService implements CommunicationService {

    private final Supplier<CommunicationProcessorRegistry> registry;

    private final CommunicationReader reader;

    private final CommunicationCreations creations;

    private final CommunicationCallbacks callbacks;

    private final CommunicationAdministration administration;

    private final CommunicationCodec codec;

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
     * @param transactionManager менеджер транзакций стартера — для отмены и перезапуска
     */
    public DefaultCommunicationService(
            final Supplier<CommunicationProcessorRegistry> registry,
            final Supplier<CommunicationSettings> settings,
            final Supplier<CommunicationEngine> engine,
            final CommunicationCreator creator,
            final CommunicationCodec codec,
            final UuidV7 ids,
            final DataSource dataSource,
            final PlatformTransactionManager transactionManager) {
        this.registry = SingletonSupplier.of(registry);
        final Supplier<CommunicationSettings> resolved = SingletonSupplier.of(settings);
        this.reader = new CommunicationReader(dataSource, codec);
        this.creations = new CommunicationCreations(
                resolved,
                creator,
                codec,
                this.reader,
                new SyncRuns(
                        resolved,
                        SingletonSupplier.of(engine),
                        creator,
                        codec,
                        this.reader,
                        ids,
                        dataSource,
                        transactionManager));
        this.callbacks = new CommunicationCallbacks(dataSource, creator, codec, this.reader, resolved, ids);
        this.administration = new CommunicationAdministration(dataSource, transactionManager, resolved, ids);
        this.codec = codec;
    }

    @Override
    public <TRequest> SendResult<TRequest> send(final String destination, final TRequest payload) {
        return this.send(destination, payload, CommunicationSendOptions.defaults());
    }

    @Override
    public <TRequest> SendResult<TRequest> send(
            final String destination, final TRequest payload, final ExecutionRef execution) {
        return this.send(
                destination, payload, CommunicationSendOptions.defaults().inExecution(execution));
    }

    @Override
    public <TRequest> SendResult<TRequest> send(
            final String destination, final TRequest payload, final CommunicationSendOptions options) {
        return this.creations.send(this.registry.get().forCreate(destination), payload, options);
    }

    @Override
    public <TRequest> SendResult<TRequest> send(
            final Class<? extends CommunicationProcessor<TRequest, ?, ?>> processor, final TRequest payload) {
        return this.send(processor, payload, CommunicationSendOptions.defaults());
    }

    @Override
    public <TRequest> SendResult<TRequest> send(
            final Class<? extends CommunicationProcessor<TRequest, ?, ?>> processor,
            final TRequest payload,
            final CommunicationSendOptions options) {
        return this.creations.send(this.registry.get().forCreate(processor), payload, options);
    }

    @Override
    public SendResult<?> sendJson(
            final String destination, final String payloadJson, final CommunicationSendOptions options) {
        final RegisteredProcessor processor = this.registry.get().forCreate(destination);
        return this.creations.send(
                processor, this.codec.readRequest(payloadJson, processor.types().request(), destination), options);
    }

    @Override
    public ReceiveResult<?> receive(final String source, final InboundRequest request) {
        return this.creations.receive(this.registry.get().forCreate(source), request);
    }

    @Override
    public <TRequest> ReceiveResult<TRequest> receive(
            final Class<? extends CommunicationProcessor<TRequest, ?, ?>> processor, final InboundRequest request) {
        return this.creations.receive(this.registry.get().forCreate(processor), request);
    }

    @Override
    public ReceiveAndProcessResult<?, ?> receiveAndProcess(final String source, final InboundRequest request) {
        return this.creations.receiveAndProcess(this.registry.get().forCreate(source), request);
    }

    @Override
    public <TRequest, TSyncResponse> ReceiveAndProcessResult<TRequest, TSyncResponse> receiveAndProcess(
            final Class<? extends CommunicationProcessor<TRequest, TSyncResponse, ?>> processor,
            final InboundRequest request) {
        return this.creations.receiveAndProcess(this.registry.get().forCreate(processor), request);
    }

    @Override
    public <TRequest> SendAndProcessResult<TRequest, ?, ?> sendAndProcess(
            final String destination, final TRequest payload, final SyncSendOptions options) {
        return this.creations.sendAndProcess(this.registry.get().forCreate(destination), payload, options);
    }

    @Override
    public ReceiveResponseResult receiveResponse(final String source, final CallbackRequest request) {
        return this.callbacks.receive(this.registry.get().forCreate(source), request);
    }

    @Override
    public List<Communication<?>> findInExecution(final ExecutionRef execution, final String externalSystem) {
        return this.reader.inExecution(execution, this.registry.get().forRead(externalSystem));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <TRequest> List<Communication<TRequest>> findInExecution(
            final ExecutionRef execution, final Class<? extends CommunicationProcessor<TRequest, ?, ?>> processor) {
        return this.reader.inExecution(execution, this.registry.get().forCreate(processor)).stream()
                .map(communication -> (Communication<TRequest>) communication)
                .toList();
    }

    @Override
    public CommunicationCancelResult cancel(final Communication<?> communication) {
        return this.administration.cancel(new CommunicationRef(
                communication.id(), communication.executionRef().createdAt()));
    }

    @Override
    public CommunicationCancelResult cancel(final CommunicationSnapshot communication) {
        return this.administration.cancel(new CommunicationRef(
                communication.id(), communication.executionRef().createdAt()));
    }

    @Override
    public CommunicationRestartResult restart(final Communication<?> communication) {
        return this.administration.restart(new CommunicationRef(
                communication.id(), communication.executionRef().createdAt()));
    }

    @Override
    public CommunicationRestartResult restart(final CommunicationSnapshot communication) {
        return this.administration.restart(new CommunicationRef(
                communication.id(), communication.executionRef().createdAt()));
    }
}
