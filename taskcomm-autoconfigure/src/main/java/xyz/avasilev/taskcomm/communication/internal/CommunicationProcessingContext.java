package xyz.avasilev.taskcomm.communication.internal;

import java.util.function.BooleanSupplier;
import xyz.avasilev.taskcomm.communication.api.CommunicationContext;
import xyz.avasilev.taskcomm.communication.api.ExchangeMessage;
import xyz.avasilev.taskcomm.execution.api.ExecutionRef;

/**
 * Контекст одного вызова процессора коммуникаций. Запись обмена идёт сразу в базу своей транзакцией;
 * остальное отвечает из памяти. Записанный статус ответа контекст помнит: по нему воркер решает
 * исход попытки (docs/adr/0065-communication-circuit-breaker.md).
 *
 * <p>Ключи корреляции и дедупликации — этап 5c: до него методы бросают
 * {@link UnsupportedOperationException} (docs/adr/0135-communication-engine.md). «Не ждать» без ключа
 * и так верно — ожидания нет, — поэтому метод ничего не делает.
 */
final class CommunicationProcessingContext implements CommunicationContext {

    private final ClaimedCommunication attempt;

    private final CommunicationExchange exchange;

    private final BooleanSupplier stopSignal;

    private volatile String skipReason;

    private volatile String doNotRetryReason;

    private volatile boolean responseRecorded;

    private volatile Integer responseStatus;

    CommunicationProcessingContext(
            final ClaimedCommunication attempt,
            final CommunicationExchange exchange,
            final BooleanSupplier stopSignal) {
        this.attempt = attempt;
        this.exchange = exchange;
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
        throw new UnsupportedOperationException("registerCorrelationId" + NewCommunication.NOT_YET);
    }

    @Override
    public void registerCorrelationId(final String externalSystem, final String correlationId) {
        throw new UnsupportedOperationException("registerCorrelationId" + NewCommunication.NOT_YET);
    }

    @Override
    public void doNotAwaitResponse() {
        // без ключа корреляции ответа и так не ждут
    }

    @Override
    public boolean dedupKey(final String key) {
        throw new UnsupportedOperationException("dedupKey" + NewCommunication.NOT_YET);
    }

    @Override
    public boolean dedupKey(final String subject, final String key) {
        throw new UnsupportedOperationException("dedupKey" + NewCommunication.NOT_YET);
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

    private static String requireReason(final String reason, final String method) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(method + "(reason): причина обязательна — она хранится в базе "
                    + "и объясняет исход тому, кто будет разбираться.");
        }
        return reason;
    }
}
