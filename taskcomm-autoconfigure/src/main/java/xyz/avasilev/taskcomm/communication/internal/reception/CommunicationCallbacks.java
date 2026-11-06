package xyz.avasilev.taskcomm.communication.internal.reception;

import java.util.Optional;
import java.util.function.Supplier;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import xyz.avasilev.taskcomm.communication.api.CallbackRequest;
import xyz.avasilev.taskcomm.communication.api.Communication;
import xyz.avasilev.taskcomm.communication.api.ExchangeMessage;
import xyz.avasilev.taskcomm.communication.api.ReceiveResponseResult;
import xyz.avasilev.taskcomm.communication.internal.CommunicationCodec;
import xyz.avasilev.taskcomm.communication.internal.CommunicationRef;
import xyz.avasilev.taskcomm.communication.internal.CommunicationSql;
import xyz.avasilev.taskcomm.communication.internal.Direction;
import xyz.avasilev.taskcomm.communication.internal.creation.CommunicationCreator;
import xyz.avasilev.taskcomm.communication.internal.creation.CreatedCommunication;
import xyz.avasilev.taskcomm.communication.internal.creation.NewCommunication;
import xyz.avasilev.taskcomm.communication.internal.inspection.CommunicationReader;
import xyz.avasilev.taskcomm.communication.internal.keys.CommunicationCorrelation;
import xyz.avasilev.taskcomm.communication.internal.lifecycle.CommunicationRetries;
import xyz.avasilev.taskcomm.communication.internal.lifecycle.ResponseAttempts;
import xyz.avasilev.taskcomm.communication.internal.processor.BodyDecoding;
import xyz.avasilev.taskcomm.communication.internal.processor.DecodedBody;
import xyz.avasilev.taskcomm.communication.internal.processor.RegisteredProcessor;
import xyz.avasilev.taskcomm.communication.internal.settings.CommunicationSettings;
import xyz.avasilev.taskcomm.execution.api.Origin;
import xyz.avasilev.taskcomm.execution.api.OriginRelation;
import xyz.avasilev.taskcomm.execution.api.OriginType;
import xyz.avasilev.taskcomm.execution.internal.UuidV7;

/**
 * Приём колбэка — асинхронного ответа на исходящую (docs/adr/0086-async-response-in-outbound.md).
 * Лукап по паре «система-отправитель, ключ корреляции» (docs/adr/0075-correlation-key-scoped-by-system.md),
 * всё — в транзакции вызывающего, под блокировкой строки якоря.
 *
 * <ul>
 *   <li>Исходящая этой системы ждёт ответа, ещё отправляется или ждёт повторной отправки — колбэк
 *       дописывается попыткой {@code HANDLE_RESPONSE}; ждавшая сразу уходит в обработку ответа,
 *       ждавшая повторной отправки закрывает свою незахваченную попытку отправки {@code SKIPPED}.
 *   <li>Тело колбэка не разобралось — попытка ответа сразу {@code FAILED}, исходящая {@code FAILED}
 *       без повторов (docs/adr/0136-communication-dedup-and-callbacks.md); отправляющуюся доведёт до этого
 *       закрытие отправки.
 *   <li>Якорь с другой внешней стороной — отдельная входящая в его деле с указателем «ответ на».
 *   <li>Якорь уже не ждёт — входящая сразу {@code FAILED} «ожидающий запрос по ключу не найден» в его
 *       деле; ключа или строки якоря нет — то же в новом деле, WARN.
 * </ul>
 */
@Slf4j
public class CommunicationCallbacks {

    /** Причина {@code FAILED} колбэка, которому некуда дописаться. */
    static final String UNMATCHED = "ожидающий запрос по ключу не найден";

    private final JdbcClient jdbc;

    private final CommunicationCreator creator;

    private final BodyDecoding decoding;

    private final CommunicationCodec codec;

    private final CommunicationReader reader;

    private final CommunicationCorrelation correlation;

    private final Supplier<CommunicationSettings> settings;

    private final UuidV7 ids;

    public CommunicationCallbacks(
            final DataSource dataSource,
            final CommunicationCreator creator,
            final CommunicationCodec codec,
            final CommunicationReader reader,
            final Supplier<CommunicationSettings> settings,
            final UuidV7 ids) {
        this.jdbc = JdbcClient.create(dataSource);
        this.creator = creator;
        this.decoding = new BodyDecoding(codec);
        this.codec = codec;
        this.reader = reader;
        this.correlation = new CommunicationCorrelation(ids);
        this.settings = settings;
        this.ids = ids;
    }

    /**
     * Принимает колбэк.
     *
     * @param processor процессор системы-отправителя
     * @param callback колбэк
     * @return куда записан колбэк
     */
    public ReceiveResponseResult receive(final RegisteredProcessor processor, final CallbackRequest callback) {
        return this.creator.inTransaction(() -> this.receiveLocked(processor, callback));
    }

    private ReceiveResponseResult receiveLocked(final RegisteredProcessor processor, final CallbackRequest callback) {
        final Optional<CommunicationCorrelation.Anchor> anchor =
                this.correlation.find(this.jdbc, processor.system(), callback.correlationId());
        final Optional<CallbackAnchor.State> state =
                anchor.flatMap(found -> CallbackAnchor.lock(this.jdbc, found.target()));
        final ReceiveResponseResult result;
        if (anchor.isEmpty() || state.isEmpty()) {
            log.warn(
                    "Колбэк системы '{}' по ключу '{}': ожидающей коммуникации нет — записан входящей сразу FAILED "
                            + "в новом деле.",
                    processor.system(),
                    callback.correlationId());
            result = new ReceiveResponseResult.Unmatched(this.inbound(processor, callback, null, UNMATCHED));
        } else if (!state.get().sameSystemOutbound(processor.system())) {
            final Communication.Inbound<?> separate = this.inbound(processor, callback, anchor.get(), null);
            result = separate.statusDescription().isPresent()
                    ? new ReceiveResponseResult.Unparseable(
                            separate, separate.statusDescription().get())
                    : new ReceiveResponseResult.Separate(separate);
        } else if (state.get().waiting()) {
            result = this.attach(processor, callback, anchor.get().target(), state.get());
        } else {
            result = new ReceiveResponseResult.Unmatched(this.inbound(processor, callback, anchor.get(), UNMATCHED));
        }
        return result;
    }

    private ReceiveResponseResult attach(
            final RegisteredProcessor processor,
            final CallbackRequest callback,
            final CommunicationRef target,
            final CallbackAnchor.State state) {
        String payload = null;
        String failure = null;
        try {
            payload = this.codec.write(this.decoding.response(processor, callback.body(), callback.headers()));
        } catch (final RuntimeException unparsed) {
            failure = ResponseAttempts.UNPARSED + ": " + CommunicationSql.describe(unparsed);
        }
        CallbackAnchor.record(this.jdbc, this.ids, this.codec, target, callback, payload, failure);
        final boolean sending = "RUNNING".equals(state.status());
        if (!sending && "PENDING".equals(state.status())) {
            CallbackAnchor.skipPendingSend(this.jdbc, target);
        }
        if (failure != null) {
            if (!sending) {
                CommunicationRetries.finish(this.jdbc, target, "FAILED", ResponseAttempts.UNPARSED);
            }
            return new ReceiveResponseResult.Unparseable(this.reader.one(target, processor), failure);
        }
        if (!sending) {
            ResponseAttempts.handOver(
                    this.jdbc, target, this.settings.get().pollers().deadlineStep(processor.system()));
        }
        return new ReceiveResponseResult.Attached((Communication.Outbound<?>) this.reader.one(target, processor));
    }

    /**
     * Входящая из колбэка: отдельная обычная — {@code failure == null}, иначе сразу {@code FAILED}.
     * С якорем — в его деле с указателем «ответ на».
     */
    private Communication.Inbound<?> inbound(
            final RegisteredProcessor processor,
            final CallbackRequest callback,
            final CommunicationCorrelation.Anchor anchor,
            final String failure) {
        final DecodedBody decoded = failure == null
                ? this.decoding.decode(processor, callback.body(), callback.headers())
                : new DecodedBody(null, null, failure);
        final ExchangeMessage message = ExchangeMessage.of(callback.body()).withHeaders(callback.headers());
        final NewCommunication.Placement placement = anchor == null
                ? new NewCommunication.Placement(null, null)
                : new NewCommunication.Placement(
                        anchor.execution(),
                        new Origin(OriginType.COMMUNICATION, anchor.communicationId(), null, OriginRelation.RESPONSE));
        final CreatedCommunication created = this.creator.create(new NewCommunication(
                Direction.INBOUND,
                processor.system(),
                decoded.json(),
                processor.shape(),
                new NewCommunication.Scheduling(
                        this.settings.get().priority(processor.system()),
                        this.settings.get().pollers().deadlineStep(processor.system()),
                        null),
                placement,
                new NewCommunication.Start(
                        callback.requestId().map(message::withId).orElse(message), decoded.failure(), null, null),
                NewCommunication.Keys.NONE));
        return (Communication.Inbound<?>) this.reader.one(
                new CommunicationRef(created.id(), created.execution().createdAt()), processor);
    }
}
