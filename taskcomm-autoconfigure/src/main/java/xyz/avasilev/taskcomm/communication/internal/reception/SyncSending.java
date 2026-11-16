package xyz.avasilev.taskcomm.communication.internal.reception;

import java.time.Duration;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import xyz.avasilev.taskcomm.communication.api.Communication;
import xyz.avasilev.taskcomm.communication.api.SendAndProcessResult;
import xyz.avasilev.taskcomm.communication.api.SyncSendOptions;
import xyz.avasilev.taskcomm.communication.internal.AttemptLease;
import xyz.avasilev.taskcomm.communication.internal.CommunicationCodec;
import xyz.avasilev.taskcomm.communication.internal.CommunicationRef;
import xyz.avasilev.taskcomm.communication.internal.CommunicationSql;
import xyz.avasilev.taskcomm.communication.internal.Direction;
import xyz.avasilev.taskcomm.communication.internal.creation.CommunicationCreator;
import xyz.avasilev.taskcomm.communication.internal.creation.CreatedCommunication;
import xyz.avasilev.taskcomm.communication.internal.creation.NewCommunication;
import xyz.avasilev.taskcomm.communication.internal.inspection.CommunicationReader;
import xyz.avasilev.taskcomm.communication.internal.processor.RegisteredProcessor;
import xyz.avasilev.taskcomm.communication.internal.settings.CommunicationSettings;

/**
 * Синхронная отправка исходящей (docs/adr/0167-communication-run-mode.md): ожидание размыкателя
 * и ограничителя скорости до создания — истёк предел, коммуникация не создаётся; затем коммуникация
 * с режимом {@code SYNC} сразу с попыткой под арендой этого экземпляра и попытки в потоке вызывающего —
 * {@link SyncAttempts}. После синхронного ответа — по просьбе вызова ожидание колбэка опросом строки.
 */
final class SyncSending {

    /** Самый длинный шаг опроса колбэка. */
    private static final Duration MAX_STEP = Duration.ofSeconds(2);

    /** Статус и последний колбэк — для ожидания ответа. */
    private static final String RESPONSE = """
            SELECT c.status, c.status_description,
                   (SELECT a.request_payload::text FROM tc_communication_attempt a
                     WHERE a.execution_created_at = c.execution_created_at AND a.communication_id = c.id
                       AND a.phase = 'HANDLE_RESPONSE'
                     ORDER BY a.created_at DESC
                     LIMIT 1) AS callback
              FROM tc_communication c
             WHERE c.execution_created_at = :executionCreatedAt AND c.id = :id
            """;

    private final SyncAttempts attempts;

    private final Supplier<CommunicationSettings> settings;

    private final CommunicationCreator creator;

    private final CommunicationReader reader;

    private final CommunicationCodec codec;

    private final JdbcClient jdbc;

    SyncSending(
            final SyncAttempts attempts,
            final Supplier<CommunicationSettings> settings,
            final CommunicationCreator creator,
            final CommunicationReader reader,
            final CommunicationCodec codec,
            final DataSource dataSource) {
        this.attempts = attempts;
        this.settings = settings;
        this.creator = creator;
        this.reader = reader;
        this.codec = codec;
        this.jdbc = JdbcClient.create(dataSource);
    }

    /**
     * Отправляет исходящую и обрабатывает её здесь же.
     *
     * @param processor процессор системы-получателя
     * @param communication коммуникация к созданию по способу начала первой попытки
     * @param options параметры синхронной отправки — пределы ожидания
     * @param <TRequest> доменная форма запроса
     * @param <TSyncResponse> доменная форма синхронного ответа
     * @param <TAsyncResponse> доменная форма колбэка
     * @return итог отправки
     */
    <TRequest, TSyncResponse, TAsyncResponse> SendAndProcessResult<TRequest, TSyncResponse, TAsyncResponse> send(
            final RegisteredProcessor processor,
            final Function<NewCommunication.Start, NewCommunication> communication,
            final SyncSendOptions options) {
        return this.send(
                processor,
                communication,
                options.syncWait().orElseGet(() -> this.settings.get().syncWait(processor.system())),
                options.awaitResponse().orElse(null));
    }

    @SuppressWarnings("unchecked")
    private <TRequest, TSyncResponse, TAsyncResponse>
            SendAndProcessResult<TRequest, TSyncResponse, TAsyncResponse> send(
                    final RegisteredProcessor processor,
                    final Function<NewCommunication.Start, NewCommunication> communication,
                    final Duration syncWait,
                    final Duration awaitResponse) {
        final String unavailable = this.attempts.awaitGuards(processor.system(), syncWait);
        if (unavailable != null) {
            return new SendAndProcessResult.Unavailable<>(processor.system(), unavailable);
        }
        final AttemptLease lease = this.attempts.lease(processor.system());
        final NewCommunication created =
                communication.apply(new NewCommunication.Start(null, null, lease, this.attempts.instance(), true));
        final CreatedCommunication row = this.creator.create(created);
        final CommunicationRef target =
                new CommunicationRef(row.id(), row.execution().createdAt());
        if (row.duplicate() != null) {
            return new SendAndProcessResult.Duplicate<>(
                    this.outbound(target, processor), row.duplicate().duplicateOf());
        }
        final SyncAttempts.Run outcome = this.attempts.run(
                SyncAttempts.first(
                        lease, target, processor.system(), Direction.OUTBOUND, row, created, created.payload()),
                syncWait);
        return outcome.processed()
                ? this.response(processor, target, (TSyncResponse) outcome.response(), awaitResponse)
                : new SendAndProcessResult.Failed<>(this.outbound(target, processor), outcome.failure());
    }

    /** Синхронный ответ получен: колбэк не ждём, ждём до предела или его не будет. */
    @SuppressWarnings("unchecked")
    private <TRequest, TSyncResponse, TAsyncResponse>
            SendAndProcessResult<TRequest, TSyncResponse, TAsyncResponse> response(
                    final RegisteredProcessor processor,
                    final CommunicationRef target,
                    final TSyncResponse response,
                    final Duration awaitResponse) {
        final State state = awaitResponse == null ? this.state(target) : this.await(target, awaitResponse);
        final Communication.Outbound<TRequest> outbound = this.outbound(target, processor);
        final SendAndProcessResult<TRequest, TSyncResponse, TAsyncResponse> result;
        if (state.waiting()) {
            result = awaitResponse == null
                    ? new SendAndProcessResult.Processed<>(outbound, response)
                    : new SendAndProcessResult.Awaiting<>(outbound, response);
        } else if (!"SUCCEEDED".equals(state.status())) {
            result = new SendAndProcessResult.Failed<>(
                    outbound, state.reason() == null ? "статус " + state.status() : state.reason());
        } else if (state.callback() == null) {
            result = new SendAndProcessResult.Processed<>(outbound, response);
        } else {
            result = new SendAndProcessResult.Responded<>(outbound, response, (TAsyncResponse)
                    this.codec.read(state.callback(), processor.types().callback()));
        }
        return result;
    }

    /** Опрос строки до конечного статуса или предела: шаг растёт от 200 мс до 2 с. */
    private State await(final CommunicationRef target, final Duration limit) {
        final long deadline = System.nanoTime() + limit.toNanos();
        long step = SyncAttempts.STEP.toNanos();
        State state = this.state(target);
        while (state.waiting()
                && System.nanoTime() < deadline
                && SyncAttempts.pause(Math.min(step, deadline - System.nanoTime()) / 1e9)) {
            step = Math.min(step * 2, MAX_STEP.toNanos());
            state = this.state(target);
        }
        return state;
    }

    private State state(final CommunicationRef target) {
        return this.jdbc
                .sql(RESPONSE)
                .param("executionCreatedAt", CommunicationSql.parameter(target.executionCreatedAt()))
                .param("id", target.id())
                .query((rs, rowNum) ->
                        new State(rs.getString("status"), rs.getString("status_description"), rs.getString("callback")))
                .single();
    }

    @SuppressWarnings("unchecked")
    private <TRequest> Communication.Outbound<TRequest> outbound(
            final CommunicationRef target, final RegisteredProcessor processor) {
        return (Communication.Outbound<TRequest>) this.reader.one(target, processor);
    }

    /**
     * Коммуникация после отправки.
     *
     * @param status статус
     * @param reason причина
     * @param callback последний колбэк или {@code null}
     */
    private record State(String status, String reason, String callback) {

        boolean waiting() {
            return "AWAITING_RESPONSE".equals(this.status)
                    || "PENDING".equals(this.status)
                    || "RUNNING".equals(this.status);
        }
    }
}
