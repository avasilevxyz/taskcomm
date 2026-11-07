package xyz.avasilev.taskcomm.communication.internal.reception;

import java.util.function.Function;
import java.util.function.Supplier;
import xyz.avasilev.taskcomm.TaskcommException;
import xyz.avasilev.taskcomm.communication.api.Communication;
import xyz.avasilev.taskcomm.communication.api.ExchangeMessage;
import xyz.avasilev.taskcomm.communication.api.ReceiveAndProcessResult;
import xyz.avasilev.taskcomm.communication.internal.AttemptHistory;
import xyz.avasilev.taskcomm.communication.internal.AttemptLease;
import xyz.avasilev.taskcomm.communication.internal.ClaimedCommunication;
import xyz.avasilev.taskcomm.communication.internal.CommunicationPhase;
import xyz.avasilev.taskcomm.communication.internal.CommunicationRef;
import xyz.avasilev.taskcomm.communication.internal.Direction;
import xyz.avasilev.taskcomm.communication.internal.creation.CommunicationCreator;
import xyz.avasilev.taskcomm.communication.internal.creation.CreatedCommunication;
import xyz.avasilev.taskcomm.communication.internal.creation.NewCommunication;
import xyz.avasilev.taskcomm.communication.internal.engine.CommunicationEngine;
import xyz.avasilev.taskcomm.communication.internal.engine.CommunicationWorker;
import xyz.avasilev.taskcomm.communication.internal.inspection.CommunicationReader;
import xyz.avasilev.taskcomm.communication.internal.processor.DecodedBody;
import xyz.avasilev.taskcomm.communication.internal.processor.RegisteredProcessor;
import xyz.avasilev.taskcomm.communication.internal.settings.CommunicationSettings;
import xyz.avasilev.taskcomm.execution.internal.UuidV7;

/**
 * Синхронный приём входящей (docs/adr/0133-synchronous-inbound-reception.md): коммуникация создаётся
 * своей короткой транзакцией сразу с попыткой под арендой этого экземпляра, процессор вызывается
 * в потоке вызывающего под heartbeat движка, одной попыткой; неуспех — {@code FAILED} без повтора.
 * Внутри активной транзакции — исключение до записи.
 */
public final class SyncReception {

    private final Supplier<CommunicationSettings> settings;

    private final Supplier<CommunicationEngine> engine;

    private final CommunicationCreator creator;

    private final CommunicationReader reader;

    private final UuidV7 ids;

    public SyncReception(
            final Supplier<CommunicationSettings> settings,
            final Supplier<CommunicationEngine> engine,
            final CommunicationCreator creator,
            final CommunicationReader reader,
            final UuidV7 ids) {
        this.settings = settings;
        this.engine = engine;
        this.creator = creator;
        this.reader = reader;
        this.ids = ids;
    }

    /**
     * Принимает и обрабатывает входящую.
     *
     * @param processor процессор системы-отправителя
     * @param decoded разобранное тело
     * @param message что прислали
     * @param communication коммуникация к созданию по способу начала первой попытки
     * @param <TRequest> доменная форма запроса
     * @param <TSyncResponse> доменная форма ответа отправителю
     * @return итог приёма
     */
    @SuppressWarnings("unchecked")
    <TRequest, TSyncResponse> ReceiveAndProcessResult<TRequest, TSyncResponse> receive(
            final RegisteredProcessor processor,
            final DecodedBody decoded,
            final ExchangeMessage message,
            final Function<NewCommunication.Start, NewCommunication> communication) {
        final CommunicationEngine running = this.engine.get();
        final AttemptLease lease = decoded.failure() == null
                ? this.settings
                        .get()
                        .attempt(processor.system(), CommunicationPhase.PROCESS)
                        .lease()
                        .lease(this.ids.next())
                : null;
        final NewCommunication created =
                communication.apply(new NewCommunication.Start(message, decoded.failure(), lease, running.instance()));
        final CreatedCommunication row = this.creator.create(created);
        final CommunicationRef target =
                new CommunicationRef(row.id(), row.execution().createdAt());
        if (row.duplicate() != null) {
            return new ReceiveAndProcessResult.Duplicate<>(
                    (Communication.Inbound<TRequest>) this.reader.one(target, processor),
                    row.duplicate().duplicateOf());
        }
        if (decoded.failure() != null) {
            return new ReceiveAndProcessResult.Unparseable<>(
                    (Communication.Inbound<TRequest>) this.reader.one(target, processor), decoded.failure());
        }
        final CommunicationWorker.SyncOutcome outcome = running.worker()
                .executeSync(new ClaimedCommunication(
                        lease,
                        target,
                        processor.system(),
                        Direction.INBOUND,
                        CommunicationPhase.PROCESS,
                        AttemptHistory.FIRST,
                        new ClaimedCommunication.Snapshot(
                                row.execution().id(),
                                created.scheduling().priority(),
                                row.createdAt(),
                                row.origin(),
                                decoded.json(),
                                null)));
        final Communication.Inbound<TRequest> inbound =
                (Communication.Inbound<TRequest>) this.reader.one(target, processor);
        return outcome.processed()
                ? new ReceiveAndProcessResult.Processed<>(inbound, (TSyncResponse) outcome.response())
                : new ReceiveAndProcessResult.Failed<>(inbound, outcome.failure());
    }

    /**
     * Проверяет, что транзакции вызывающего нет.
     *
     * @param transactionActive активна ли транзакция в потоке
     * @throws TaskcommException если активна
     */
    public static void requireOutsideTransaction(final boolean transactionActive) {
        if (transactionActive) {
            throw new TaskcommException("Синхронный приём вызван внутри активной транзакции. Попытка, обмен и "
                    + "закрытие пишутся своими короткими транзакциями и не увидели бы незакоммиченную "
                    + "коммуникацию, а работа рядом с вашей транзакцией молча потеряла бы атомарность с "
                    + "бизнес-данными. Вызывайте receiveAndProcess вне транзакции или принимайте асинхронно "
                    + "методом receive (docs/adr/0133-synchronous-inbound-reception.md).");
        }
    }
}
