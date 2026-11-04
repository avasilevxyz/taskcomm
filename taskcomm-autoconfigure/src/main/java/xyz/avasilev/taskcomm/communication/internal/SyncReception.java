package xyz.avasilev.taskcomm.communication.internal;

import java.util.function.Function;
import java.util.function.Supplier;
import xyz.avasilev.taskcomm.TaskcommException;
import xyz.avasilev.taskcomm.communication.api.Communication;
import xyz.avasilev.taskcomm.communication.api.ExchangeMessage;
import xyz.avasilev.taskcomm.communication.api.ReceiveAndProcessResult;
import xyz.avasilev.taskcomm.execution.internal.UuidV7;

/**
 * Синхронный приём входящей (docs/adr/0133-synchronous-inbound-reception.md): коммуникация создаётся
 * своей короткой транзакцией сразу с попыткой под арендой этого экземпляра, процессор вызывается
 * в потоке вызывающего под heartbeat движка, одной попыткой; неуспех — {@code FAILED} без повтора.
 * Внутри активной транзакции — исключение до записи.
 */
final class SyncReception {

    private final Supplier<CommunicationSettings> settings;

    private final Supplier<CommunicationEngine> engine;

    private final CommunicationCreator creator;

    private final CommunicationReader reader;

    private final UuidV7 ids;

    SyncReception(
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
     * @param <C> доменная форма запроса
     * @param <R> доменная форма ответа отправителю
     * @return итог приёма
     */
    @SuppressWarnings("unchecked")
    <C, R> ReceiveAndProcessResult<C, R> receive(
            final RegisteredProcessor processor,
            final DecodedBody decoded,
            final ExchangeMessage message,
            final Function<NewCommunication.Start, NewCommunication> communication) {
        final CommunicationEngine running = this.engine.get();
        final AttemptLease lease = decoded.failure() == null
                ? AttemptLease.of(
                        this.ids.next(),
                        this.settings
                                .get()
                                .attempt(processor.system(), CommunicationPhase.PROCESS)
                                .lease())
                : null;
        final NewCommunication created =
                communication.apply(new NewCommunication.Start(message, decoded.failure(), lease, running.instance()));
        final CreatedCommunication row = this.creator.create(created);
        final CommunicationRef target =
                new CommunicationRef(row.id(), row.execution().createdAt());
        if (decoded.failure() != null) {
            return new ReceiveAndProcessResult.Unparseable<>(
                    (Communication.Inbound<C>) this.reader.one(target, processor), decoded.failure());
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
                                created.placement().origin(),
                                decoded.json())));
        final Communication.Inbound<C> inbound = (Communication.Inbound<C>) this.reader.one(target, processor);
        return outcome.processed()
                ? new ReceiveAndProcessResult.Processed<>(inbound, (R) outcome.response())
                : new ReceiveAndProcessResult.Failed<>(inbound, outcome.failure());
    }

    /**
     * Проверяет, что транзакции вызывающего нет.
     *
     * @param transactionActive активна ли транзакция в потоке
     * @throws TaskcommException если активна
     */
    static void requireOutsideTransaction(final boolean transactionActive) {
        if (transactionActive) {
            throw new TaskcommException("Синхронный приём вызван внутри активной транзакции. Попытка, обмен и "
                    + "закрытие пишутся своими короткими транзакциями и не увидели бы незакоммиченную "
                    + "коммуникацию, а работа рядом с вашей транзакцией молча потеряла бы атомарность с "
                    + "бизнес-данными. Вызывайте receiveAndProcess вне транзакции или принимайте асинхронно "
                    + "методом receive (docs/adr/0133-synchronous-inbound-reception.md).");
        }
    }
}
