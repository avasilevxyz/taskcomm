package xyz.avasilev.taskcomm.communication.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.core.ResolvableType;
import xyz.avasilev.taskcomm.communication.api.CommunicationAttemptSnapshot;
import xyz.avasilev.taskcomm.communication.api.CommunicationHeaders;
import xyz.avasilev.taskcomm.communication.api.CommunicationSnapshot;
import xyz.avasilev.taskcomm.communication.api.CommunicationStatus;
import xyz.avasilev.taskcomm.communication.api.ExchangeMessage;
import xyz.avasilev.taskcomm.execution.api.ExecutionRef;
import xyz.avasilev.taskcomm.execution.api.Origin;
import xyz.avasilev.taskcomm.execution.api.OriginRelation;
import xyz.avasilev.taskcomm.execution.api.OriginType;

/** Снимки для {@link xyz.avasilev.taskcomm.communication.api.CommunicationInspection} из строк выборки. */
final class CommunicationSnapshots {

    private static final ResolvableType HEADERS = ResolvableType.forClassWithGenerics(
            Map.class,
            ResolvableType.forClass(String.class),
            ResolvableType.forClassWithGenerics(List.class, String.class));

    private CommunicationSnapshots() {}

    /**
     * Снимок коммуникации.
     *
     * @param rs строка выборки
     * @param orphan у системы нет процессора
     * @return снимок
     * @throws SQLException ошибка чтения
     */
    static CommunicationSnapshot communication(final ResultSet rs, final boolean orphan) throws SQLException {
        final String originType = rs.getString("origin_type");
        return new Entity(
                new Identity(
                        rs.getObject("id", UUID.class),
                        new ExecutionRef(rs.getObject("execution_id", UUID.class), instant(rs, "execution_created_at")),
                        CommunicationSnapshot.Direction.valueOf(rs.getString("direction")),
                        rs.getString("system")),
                new State(
                        CommunicationStatus.valueOf(rs.getString("status")),
                        rs.getString("status_description"),
                        rs.getString("phase"),
                        rs.getString("contract_shape"),
                        rs.getInt("priority")),
                new Times(instant(rs, "created_at"), instant(rs, "updated_at")),
                originType == null
                        ? null
                        : new Origin(
                                OriginType.valueOf(originType),
                                rs.getObject("origin_id", UUID.class),
                                rs.getObject("origin_attempt_id", UUID.class),
                                OriginRelation.valueOf(rs.getString("origin_relation"))),
                rs.getString("request_payload"),
                orphan);
    }

    /**
     * Снимок попытки с обменом.
     *
     * @param rs строка попытки
     * @param codec кодек — для заголовков
     * @return снимок
     * @throws SQLException ошибка чтения
     */
    static CommunicationAttemptSnapshot attempt(final ResultSet rs, final CommunicationCodec codec)
            throws SQLException {
        return new Attempt(
                rs.getObject("id", UUID.class),
                rs.getString("phase"),
                rs.getString("status"),
                rs.getString("status_description"),
                new Times(nullableInstant(rs, "started_at"), nullableInstant(rs, "finished_at")),
                rs.getString("locked_by"),
                new Exchange(
                        message(rs, "request", codec),
                        message(rs, "response", codec),
                        rs.getString("request_payload"),
                        rs.getString("response_payload")));
    }

    private static ExchangeMessage message(final ResultSet rs, final String prefix, final CommunicationCodec codec)
            throws SQLException {
        final String body = rs.getString(prefix + "_body");
        if (body == null) {
            return null;
        }
        ExchangeMessage message = ExchangeMessage.of(body);
        final String headers = rs.getString(prefix + "_headers");
        if (headers != null) {
            @SuppressWarnings("unchecked")
            final Map<String, List<String>> values = (Map<String, List<String>>) codec.read(headers, HEADERS);
            message = message.withHeaders(CommunicationHeaders.of(values));
        }
        final String id = rs.getString(prefix + "_id");
        if (id != null) {
            message = message.withId(id);
        }
        final Instant at = nullableInstant(rs, prefix + "_at");
        if (at != null) {
            message = message.withAt(at);
        }
        return withStatus(rs, prefix, message);
    }

    private static ExchangeMessage withStatus(final ResultSet rs, final String prefix, final ExchangeMessage message)
            throws SQLException {
        if (!"response".equals(prefix)) {
            return message;
        }
        final int status = rs.getInt("response_status");
        return rs.wasNull() ? message : message.withStatus(status);
    }

    private static Instant instant(final ResultSet rs, final String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static Instant nullableInstant(final ResultSet rs, final String column) throws SQLException {
        final OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    /**
     * Идентичность коммуникации.
     *
     * @param id идентификатор
     * @param executionRef дело
     * @param direction направление
     * @param system внешняя система
     */
    private record Identity(
            UUID id, ExecutionRef executionRef, CommunicationSnapshot.Direction direction, String system) {}

    /**
     * Состояние.
     *
     * @param status статус
     * @param description причина или {@code null}
     * @param phase фаза
     * @param contractShape форма контракта или {@code null}
     * @param priority приоритет
     */
    private record State(
            CommunicationStatus status, String description, String phase, String contractShape, int priority) {}

    /**
     * Пара моментов.
     *
     * @param first создание или начало; бывает {@code null}
     * @param second изменение или конец; бывает {@code null}
     */
    private record Times(Instant first, Instant second) {}

    /**
     * Запрос и ответ попытки.
     *
     * @param request запрос или {@code null}
     * @param response ответ или {@code null}
     * @param callbackPayload доменная форма колбэка JSON или {@code null}
     * @param responsePayload доменная форма ответа JSON или {@code null}
     */
    private record Exchange(
            ExchangeMessage request, ExchangeMessage response, String callbackPayload, String responsePayload) {}

    /**
     * Снимок коммуникации.
     *
     * @param identity идентификатор, дело, направление, система
     * @param state состояние
     * @param times создание и изменение
     * @param originValue создатель или {@code null}
     * @param payload нагрузка JSON или {@code null}
     * @param orphan у системы нет процессора
     */
    private record Entity(
            Identity identity, State state, Times times, Origin originValue, String payload, boolean orphan)
            implements CommunicationSnapshot {

        @Override
        public UUID id() {
            return this.identity.id();
        }

        @Override
        public ExecutionRef executionRef() {
            return this.identity.executionRef();
        }

        @Override
        public Direction direction() {
            return this.identity.direction();
        }

        @Override
        public String externalSystem() {
            return this.identity.system();
        }

        @Override
        public CommunicationStatus status() {
            return this.state.status();
        }

        @Override
        public Optional<String> statusDescription() {
            return Optional.ofNullable(this.state.description());
        }

        @Override
        public String phase() {
            return this.state.phase();
        }

        @Override
        public Optional<String> contractShape() {
            return Optional.ofNullable(this.state.contractShape());
        }

        @Override
        public int priority() {
            return this.state.priority();
        }

        @Override
        public Instant createdAt() {
            return this.times.first();
        }

        @Override
        public Instant updatedAt() {
            return this.times.second();
        }

        @Override
        public Optional<Origin> origin() {
            return Optional.ofNullable(this.originValue);
        }

        @Override
        public Optional<String> requestPayload() {
            return Optional.ofNullable(this.payload);
        }
    }

    /**
     * Снимок попытки.
     *
     * @param id идентификатор
     * @param phase фаза
     * @param statusValue исход или {@code null}
     * @param description причина или {@code null}
     * @param times начало и конец
     * @param lockedByValue экземпляр или {@code null}
     * @param exchange обмен и доменные формы
     */
    private record Attempt(
            UUID id,
            String phase,
            String statusValue,
            String description,
            Times times,
            String lockedByValue,
            Exchange exchange)
            implements CommunicationAttemptSnapshot {

        @Override
        public Optional<String> status() {
            return Optional.ofNullable(this.statusValue);
        }

        @Override
        public Optional<String> statusDescription() {
            return Optional.ofNullable(this.description);
        }

        @Override
        public Optional<Instant> startedAt() {
            return Optional.ofNullable(this.times.first());
        }

        @Override
        public Optional<Instant> finishedAt() {
            return Optional.ofNullable(this.times.second());
        }

        @Override
        public Optional<String> lockedBy() {
            return Optional.ofNullable(this.lockedByValue);
        }

        @Override
        public Optional<ExchangeMessage> request() {
            return Optional.ofNullable(this.exchange.request());
        }

        @Override
        public Optional<ExchangeMessage> response() {
            return Optional.ofNullable(this.exchange.response());
        }

        @Override
        public Optional<String> callbackPayload() {
            return Optional.ofNullable(this.exchange.callbackPayload());
        }

        @Override
        public Optional<String> responsePayload() {
            return Optional.ofNullable(this.exchange.responsePayload());
        }
    }
}
