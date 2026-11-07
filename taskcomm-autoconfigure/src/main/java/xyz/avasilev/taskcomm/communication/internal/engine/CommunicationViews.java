package xyz.avasilev.taskcomm.communication.internal.engine;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import xyz.avasilev.taskcomm.communication.api.Communication;
import xyz.avasilev.taskcomm.communication.api.CommunicationStatus;
import xyz.avasilev.taskcomm.communication.internal.Direction;
import xyz.avasilev.taskcomm.communication.internal.creation.CreatedCommunication;
import xyz.avasilev.taskcomm.execution.api.ExecutionRef;
import xyz.avasilev.taskcomm.execution.api.Origin;

/** Снимки коммуникаций для потребителя — реализации {@link Communication.Inbound} и {@link Communication.Outbound}. */
public final class CommunicationViews {

    private CommunicationViews() {}

    /**
     * Снимок по направлению.
     *
     * @param direction направление
     * @param row строка коммуникации
     * @param payload доменная форма запроса
     * @param <TRequest> доменная форма запроса
     * @return входящая или исходящая
     */
    public static <TRequest> Communication<TRequest> of(
            final Direction direction, final Row row, final TRequest payload) {
        return direction == Direction.INBOUND ? new InboundView<>(row, payload) : new OutboundView<>(row, payload);
    }

    /**
     * Снимок входящей.
     *
     * @param row строка коммуникации
     * @param payload доменная форма запроса
     * @param <TRequest> доменная форма запроса
     * @return входящая
     */
    static <TRequest> Communication.Inbound<TRequest> inbound(final Row row, final TRequest payload) {
        return new InboundView<>(row, payload);
    }

    /**
     * Снимок исходящей.
     *
     * @param row строка коммуникации
     * @param payload доменная форма запроса
     * @param <TRequest> доменная форма запроса
     * @return исходящая
     */
    static <TRequest> Communication.Outbound<TRequest> outbound(final Row row, final TRequest payload) {
        return new OutboundView<>(row, payload);
    }

    /**
     * Строка только что созданной коммуникации: {@code PENDING} или, с причиной, {@code FAILED}.
     *
     * @param created созданная коммуникация
     * @param system внешняя система
     * @param priority приоритет
     * @param origin создатель или {@code null}
     * @param failure причина неразобранного тела или {@code null}
     * @return строка
     */
    static Row created(
            final CreatedCommunication created,
            final String system,
            final int priority,
            final Origin origin,
            final String failure) {
        return new Row(
                created.id(),
                created.execution(),
                system,
                priority,
                failure == null ? CommunicationStatus.PENDING : CommunicationStatus.FAILED,
                failure,
                created.createdAt(),
                origin);
    }

    /**
     * Строка коммуникации без доменной формы.
     *
     *
     * @param id идентификатор
     * @param execution дело
     * @param system внешняя система
     * @param priority приоритет
     * @param status статус
     * @param description причина статуса или {@code null}
     * @param createdAt время создания
     * @param origin создатель или {@code null}
     */
    public record Row(
            UUID id,
            ExecutionRef execution,
            String system,
            int priority,
            CommunicationStatus status,
            String description,
            Instant createdAt,
            Origin origin) {}

    /**
     * Входящая.
     *
     * @param row строка
     * @param requestPayload доменная форма запроса
     * @param <TRequest> доменная форма запроса
     */
    private record InboundView<TRequest>(Row row, TRequest requestPayload) implements Communication.Inbound<TRequest> {

        @Override
        public String source() {
            return this.row.system();
        }

        @Override
        public UUID id() {
            return this.row.id();
        }

        @Override
        public ExecutionRef executionRef() {
            return this.row.execution();
        }

        @Override
        public int priority() {
            return this.row.priority();
        }

        @Override
        public CommunicationStatus status() {
            return this.row.status();
        }

        @Override
        public Optional<String> statusDescription() {
            return Optional.ofNullable(this.row.description());
        }

        @Override
        public Instant createdAt() {
            return this.row.createdAt();
        }

        @Override
        public Optional<Origin> origin() {
            return Optional.ofNullable(this.row.origin());
        }
    }

    /**
     * Исходящая.
     *
     * @param row строка
     * @param requestPayload доменная форма запроса
     * @param <TRequest> доменная форма запроса
     */
    private record OutboundView<TRequest>(Row row, TRequest requestPayload)
            implements Communication.Outbound<TRequest> {

        @Override
        public String destination() {
            return this.row.system();
        }

        @Override
        public UUID id() {
            return this.row.id();
        }

        @Override
        public ExecutionRef executionRef() {
            return this.row.execution();
        }

        @Override
        public int priority() {
            return this.row.priority();
        }

        @Override
        public CommunicationStatus status() {
            return this.row.status();
        }

        @Override
        public Optional<String> statusDescription() {
            return Optional.ofNullable(this.row.description());
        }

        @Override
        public Instant createdAt() {
            return this.row.createdAt();
        }

        @Override
        public Optional<Origin> origin() {
            return Optional.ofNullable(this.row.origin());
        }
    }
}
