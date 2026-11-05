package xyz.avasilev.taskcomm.communication.internal;

import java.util.Optional;
import java.util.function.BooleanSupplier;
import xyz.avasilev.taskcomm.communication.api.CommunicationContext;
import xyz.avasilev.taskcomm.communication.api.CorrelationKey;
import xyz.avasilev.taskcomm.communication.api.DedupKey;
import xyz.avasilev.taskcomm.communication.api.ExchangeMessage;
import xyz.avasilev.taskcomm.execution.api.ExecutionRef;

/**
 * Контекст одного вызова процессора коммуникаций. Запись обмена и ключей идёт сразу в базу своими
 * короткими транзакциями; остальное отвечает из памяти. Записанный статус ответа контекст помнит:
 * по нему воркер решает исход попытки (docs/adr/0065-communication-circuit-breaker.md).
 *
 * <p>Ключ дедупликации, оказавшийся дубликатом, действует как {@code skip}: {@link #shouldStop()}
 * отвечает {@code true}, нормальный возврат даёт {@code SKIPPED}, исключение перебивает пропуск
 * (docs/adr/0076-communication-deduplication.md).
 */
final class CommunicationProcessingContext implements CommunicationContext {

    private final ClaimedCommunication attempt;

    private final CommunicationExchange exchange;

    private final ProcessorKeys keys;

    private final BooleanSupplier stopSignal;

    private volatile String skipReason;

    private volatile String doNotRetryReason;

    private volatile boolean responseRecorded;

    private volatile Integer responseStatus;

    private volatile boolean doNotAwait;

    CommunicationProcessingContext(
            final ClaimedCommunication attempt,
            final CommunicationExchange exchange,
            final ProcessorKeys keys,
            final BooleanSupplier stopSignal) {
        this.attempt = attempt;
        this.exchange = exchange;
        this.keys = keys;
        this.stopSignal = stopSignal;
    }

    @Override
    public void skip(final String reason) {
        this.skipReason = requireReason(reason, "skip");
    }

    @Override
    public void doNotRetry(final String reason) {
        this.doNotRetryReason = requireReason(reason, "doNotRetry");
    }

    @Override
    public boolean shouldStop() {
        return this.skipReason != null || this.stopSignal.getAsBoolean();
    }

    @Override
    public ExecutionRef executionRef() {
        return this.attempt.execution();
    }

    @Override
    public int attemptNumber() {
        return this.attempt.history().number();
    }

    @Override
    public boolean isRetry() {
        return this.attempt.history().retry();
    }

    @Override
    public void recordRequest(final ExchangeMessage message) {
        this.exchange.request(this.attempt.target(), this.attempt.lease().attemptId(), message);
    }

    @Override
    public void recordResponse(final ExchangeMessage message) {
        this.exchange.response(this.attempt.target(), this.attempt.lease().attemptId(), message);
        this.responseRecorded = true;
        this.responseStatus = message.status().isPresent() ? message.status().getAsInt() : null;
    }

    @Override
    public void registerCorrelationId(final String correlationId) {
        this.keys.correlate(this.attempt, CorrelationKey.of(correlationId));
    }

    @Override
    public void registerCorrelationId(final String externalSystem, final String correlationId) {
        this.keys.correlate(this.attempt, CorrelationKey.of(externalSystem, correlationId));
    }

    @Override
    public void doNotAwaitResponse() {
        this.doNotAwait = true;
    }

    @Override
    public boolean dedupKey(final String key) {
        return this.dedup(new DedupKey.WholeHistory(key));
    }

    @Override
    public boolean dedupKey(final String subject, final String key) {
        return this.dedup(new DedupKey.LastSucceeded(subject, key));
    }

    String skipReason() {
        return this.skipReason;
    }

    String doNotRetryReason() {
        return this.doNotRetryReason;
    }

    boolean responseRecorded() {
        return this.responseRecorded;
    }

    Integer responseStatus() {
        return this.responseStatus;
    }

    boolean doNotAwait() {
        return this.doNotAwait;
    }

    private boolean dedup(final DedupKey key) {
        final Optional<CommunicationDedup.Match> match = this.keys.dedup(this.attempt, key);
        match.ifPresent(found -> this.skipReason = CommunicationDedup.reason(found));
        return match.isPresent();
    }

    private static String requireReason(final String reason, final String method) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(method + "(reason): причина обязательна — она хранится в базе "
                    + "и объясняет исход тому, кто будет разбираться.");
        }
        return reason;
    }
}
