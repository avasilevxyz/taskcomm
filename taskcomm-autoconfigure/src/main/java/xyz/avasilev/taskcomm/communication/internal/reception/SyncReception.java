package xyz.avasilev.taskcomm.communication.internal.reception;

import java.util.function.Function;
import xyz.avasilev.taskcomm.communication.api.Communication;
import xyz.avasilev.taskcomm.communication.api.ExchangeMessage;
import xyz.avasilev.taskcomm.communication.api.ReceiveAndProcessResult;
import xyz.avasilev.taskcomm.communication.internal.AttemptLease;
import xyz.avasilev.taskcomm.communication.internal.CommunicationRef;
import xyz.avasilev.taskcomm.communication.internal.Direction;
import xyz.avasilev.taskcomm.communication.internal.creation.CommunicationCreator;
import xyz.avasilev.taskcomm.communication.internal.creation.CreatedCommunication;
import xyz.avasilev.taskcomm.communication.internal.creation.NewCommunication;
import xyz.avasilev.taskcomm.communication.internal.inspection.CommunicationReader;
import xyz.avasilev.taskcomm.communication.internal.processor.DecodedBody;
import xyz.avasilev.taskcomm.communication.internal.processor.RegisteredProcessor;

/**
 * Синхронный приём входящей (docs/adr/0133-synchronous-inbound-reception.md,
 * docs/adr/0167-communication-run-mode.md): коммуникация создаётся своей короткой транзакцией с режимом
 * {@code SYNC} сразу с попыткой под арендой этого экземпляра, попытки идут в потоке вызывающего —
 * {@link SyncAttempts}; повторы — по настройкам системы, {@code max-attempts: 1} — без повторов.
 */
final class SyncReception {

    private final SyncAttempts attempts;

    private final CommunicationCreator creator;

    private final CommunicationReader reader;

    SyncReception(final SyncAttempts attempts, final CommunicationCreator creator, final CommunicationReader reader) {
        this.attempts = attempts;
        this.creator = creator;
        this.reader = reader;
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
        final AttemptLease lease = decoded.failure() == null ? this.attempts.lease(processor.system()) : null;
        final NewCommunication created = communication.apply(
                new NewCommunication.Start(message, decoded.failure(), lease, this.attempts.instance(), true));
        final CreatedCommunication row = this.creator.create(created);
        final CommunicationRef target =
                new CommunicationRef(row.id(), row.execution().createdAt());
        final ReceiveAndProcessResult<TRequest, TSyncResponse> result;
        if (row.duplicate() != null) {
            result = new ReceiveAndProcessResult.Duplicate<>(
                    this.inbound(target, processor), row.duplicate().duplicateOf());
        } else if (decoded.failure() != null) {
            result = new ReceiveAndProcessResult.Unparseable<>(this.inbound(target, processor), decoded.failure());
        } else {
            final SyncAttempts.Run outcome = this.attempts.run(
                    SyncAttempts.first(
                            lease, target, processor.system(), Direction.INBOUND, row, created, decoded.json()),
                    null);
            result = outcome.processed()
                    ? new ReceiveAndProcessResult.Processed<>(
                            this.inbound(target, processor), (TSyncResponse) outcome.response())
                    : new ReceiveAndProcessResult.Failed<>(this.inbound(target, processor), outcome.failure());
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private <TRequest> Communication.Inbound<TRequest> inbound(
            final CommunicationRef target, final RegisteredProcessor processor) {
        return (Communication.Inbound<TRequest>) this.reader.one(target, processor);
    }
}
