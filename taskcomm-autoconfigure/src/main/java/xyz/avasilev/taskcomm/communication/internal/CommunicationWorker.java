package xyz.avasilev.taskcomm.communication.internal;

import lombok.extern.slf4j.Slf4j;
import xyz.avasilev.taskcomm.communication.api.Communication;
import xyz.avasilev.taskcomm.communication.api.CommunicationProcessor;
import xyz.avasilev.taskcomm.communication.api.CommunicationStatus;
import xyz.avasilev.taskcomm.execution.api.Origin;
import xyz.avasilev.taskcomm.execution.api.OriginRelation;
import xyz.avasilev.taskcomm.execution.api.OriginType;
import xyz.avasilev.taskcomm.execution.internal.CurrentExecution;

/**
 * Выполнение одной захваченной попытки коммуникации: вызов процессора и закрытие попытки исходом.
 * Работает и в потоке пула поллера, и в потоке вызывающего синхронного приёма.
 *
 * <p>Перехватывает всё, что бросил процессор потребителя. Исход: исключение — неуспех, перебивающий
 * пропуск; {@code skip(reason)} и нормальный возврат — пропуск; иначе успех. <b>У исходящей успех —
 * не просто нормальный возврат</b>: записанный ответ с отказным статусом системы делает попытку
 * неуспешной, с причиной «ответ 503» (docs/adr/0065-communication-circuit-breaker.md); REST- или
 * SOAP-процессор, вернувшийся без записанного ответа, даёт успех и WARN. Возвращённое значение пишется
 * в {@code response_payload} попытки.
 *
 * <p>На время вызова поток привязан к делу коммуникации: созданное из процессора попадает в то же
 * дело с указателем на создателя (docs/rules/execution.md).
 */
@Slf4j
class CommunicationWorker {

    private final CommunicationProcessorRegistry registry;

    private final CommunicationCodec codec;

    private final CommunicationAttemptCloser closer;

    private final CommunicationHeartbeat heartbeat;

    private final CommunicationExchange exchange;

    private final CommunicationSettings settings;

    CommunicationWorker(
            final CommunicationProcessorRegistry registry,
            final CommunicationCodec codec,
            final CommunicationAttemptCloser closer,
            final CommunicationHeartbeat heartbeat,
            final CommunicationExchange exchange,
            final CommunicationSettings settings) {
        this.registry = registry;
        this.codec = codec;
        this.closer = closer;
        this.heartbeat = heartbeat;
        this.exchange = exchange;
        this.settings = settings;
    }

    /**
     * Выполняет попытку, захваченную поллером. Ничего не бросает: ошибка закрытия — WARN, попытку
     * тогда закроет переподхват истёкшей аренды.
     *
     * @param attempt захваченная работа
     */
    void execute(final ClaimedCommunication attempt) {
        this.run(attempt, false);
    }

    /**
     * Выполняет попытку синхронного приёма в потоке вызывающего, одной попыткой: неуспех — коммуникация
     * {@code FAILED} без повтора (docs/adr/0133-synchronous-inbound-reception.md).
     *
     * @param attempt попытка, открытая под арендой при создании
     * @return чем кончилась обработка
     */
    SyncOutcome executeSync(final ClaimedCommunication attempt) {
        return this.run(attempt, true);
    }

    private SyncOutcome run(final ClaimedCommunication attempt, final boolean sync) {
        this.heartbeat.track(attempt);
        try {
            final RegisteredProcessor processor = this.registry
                    .find(attempt.system())
                    .orElseThrow(() -> new IllegalStateException(
                            "Захвачена коммуникация системы без процессора '" + attempt.system() + "'"));
            final CommunicationProcessingContext context = new CommunicationProcessingContext(
                    attempt, this.exchange, () -> this.heartbeat.shouldStop(attempt));
            return this.close(attempt, processor, context, this.invoke(attempt, processor, context), sync);
        } catch (final RuntimeException failure) {
            log.warn(
                    "Попытку {} коммуникации {} с '{}' не удалось закрыть; её закроет переподхват истёкшей аренды.",
                    attempt.lease().attemptId(),
                    attempt.target().id(),
                    attempt.system(),
                    failure);
            return SyncOutcome.failed("попытку не удалось закрыть: " + CommunicationSql.describe(failure));
        } finally {
            this.heartbeat.release(attempt);
        }
    }

    @SuppressWarnings("unchecked")
    private Invocation invoke(
            final ClaimedCommunication attempt,
            final RegisteredProcessor processor,
            final CommunicationProcessingContext context) {
        final Origin creator = new Origin(
                OriginType.COMMUNICATION, attempt.target().id(), attempt.lease().attemptId(), OriginRelation.SPAWNED);
        try (CurrentExecution.Scope _ =
                CurrentExecution.bind(new CurrentExecution.Binding(attempt.execution(), creator))) {
            if (attempt.phase() != CommunicationPhase.PROCESS) {
                throw new IllegalStateException("Обработка асинхронного ответа появится на этапе 5c");
            }
            final Communication<Object> communication = CommunicationViews.of(
                    attempt.direction(),
                    new CommunicationViews.Row(
                            attempt.target().id(),
                            attempt.execution(),
                            attempt.system(),
                            attempt.snapshot().priority(),
                            CommunicationStatus.RUNNING,
                            null,
                            attempt.snapshot().createdAt(),
                            attempt.snapshot().origin()),
                    this.codec.read(
                            attempt.snapshot().payload(), processor.types().request()));
            final CommunicationProcessor<Object, Object, Object> typed =
                    (CommunicationProcessor<Object, Object, Object>) processor.processor();
            return new Invocation(typed.process(communication, context), null);
        } catch (final Throwable failure) {
            log.warn(
                    "Процессор системы '{}' завершил попытку {} коммуникации {} исключением",
                    attempt.system(),
                    attempt.lease().attemptId(),
                    attempt.target().id(),
                    failure);
            return new Invocation(null, failure);
        }
    }

    private SyncOutcome close(
            final ClaimedCommunication attempt,
            final RegisteredProcessor processor,
            final CommunicationProcessingContext context,
            final Invocation invocation,
            final boolean sync) {
        final String failure = this.failure(attempt, processor, context, invocation);
        final boolean closed;
        if (failure != null) {
            closed = sync
                    ? this.closer.failWithoutRetry(attempt, failure)
                    : this.closer.fail(
                            attempt, failure, invocation.failure() == null ? null : context.doNotRetryReason());
            return SyncOutcome.failed(closed ? failure : "аренда потеряна, исход не сохранён: " + failure);
        }
        final String response = this.codec.write(invocation.result());
        closed = context.skipReason() != null
                ? this.closer.skip(attempt, context.skipReason(), response)
                : this.closer.succeed(attempt, response);
        return closed
                ? SyncOutcome.processed(invocation.result())
                : SyncOutcome.failed("аренда потеряна раньше, чем обработка закончилась; исход не сохранён");
    }

    /** Причина неуспеха попытки или {@code null}, если попытка успешна или пропущена. */
    private String failure(
            final ClaimedCommunication attempt,
            final RegisteredProcessor processor,
            final CommunicationProcessingContext context,
            final Invocation invocation) {
        String failure = null;
        if (invocation.failure() != null) {
            failure = CommunicationSql.describe(invocation.failure());
        } else if (attempt.direction() == Direction.OUTBOUND && context.skipReason() == null) {
            failure = this.responseFailure(attempt, processor, context);
        }
        return failure;
    }

    /** Исход исходящей по записанному ответу (docs/adr/0065-communication-circuit-breaker.md). */
    private String responseFailure(
            final ClaimedCommunication attempt,
            final RegisteredProcessor processor,
            final CommunicationProcessingContext context) {
        final Integer status = context.responseStatus();
        if (status != null && this.settings.failureStatuses(attempt.system()).failed(status)) {
            return "ответ " + status;
        }
        if (!context.responseRecorded() && processor.shape().http()) {
            log.warn(
                    "Процессор {} системы '{}' вернулся, не записав ответ: стартер его не видит, отказ прошёл бы как "
                            + "успех. Записывайте ответ методом recordResponse (docs/consumer-contract.md).",
                    processor.shape(),
                    attempt.system());
        }
        return null;
    }

    /**
     * Чем кончился вызов процессора.
     *
     * @param result возвращённое значение
     * @param failure исключение процессора или {@code null}
     */
    private record Invocation(Object result, Throwable failure) {}

    /**
     * Итог попытки для вызывающего синхронного приёма.
     *
     * @param processed {@code true}, если обработка успешна или пропущена
     * @param response что вернул процессор
     * @param failure причина неуспеха или {@code null}
     */
    record SyncOutcome(boolean processed, Object response, String failure) {

        static SyncOutcome processed(final Object response) {
            return new SyncOutcome(true, response, null);
        }

        static SyncOutcome failed(final String failure) {
            return new SyncOutcome(false, null, failure);
        }
    }
}
