package xyz.avasilev.taskcomm.communication.api;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import xyz.avasilev.taskcomm.execution.api.ExecutionRef;

/**
 * Условия выдачи коммуникаций: направление, система, статусы, дело, идентификатор запроса
 * (docs/adr/0138-inspection-api.md). Неизменяемое значение; каждый метод {@code with…} возвращает новое.
 *
 * <p>Поиск по {@code request_id} — операция разбора инцидента: лукап идёт в окне чтения истории
 * и в горячих путях не годится (docs/adr/0055-communication-exchange-storage.md).
 */
public final class CommunicationQuery {

    private static final CommunicationQuery ALL = new CommunicationQuery(null, null, Set.of(), null, null);

    private final CommunicationSnapshot.Direction direction;

    private final String externalSystem;

    private final Set<CommunicationStatus> statuses;

    private final ExecutionRef execution;

    private final String requestId;

    private CommunicationQuery(
            final CommunicationSnapshot.Direction direction,
            final String externalSystem,
            final Set<CommunicationStatus> statuses,
            final ExecutionRef execution,
            final String requestId) {
        this.direction = direction;
        this.externalSystem = externalSystem;
        this.statuses = statuses;
        this.execution = execution;
        this.requestId = requestId;
    }

    /**
     * Все коммуникации.
     *
     * @return условия
     */
    public static CommunicationQuery all() {
        return ALL;
    }

    /**
     * Только это направление.
     *
     * @param value направление
     * @return новые условия
     */
    public CommunicationQuery withDirection(final CommunicationSnapshot.Direction value) {
        return new CommunicationQuery(
                Objects.requireNonNull(value, "direction"),
                this.externalSystem,
                this.statuses,
                this.execution,
                this.requestId);
    }

    /**
     * Только эта внешняя система.
     *
     * @param value система
     * @return новые условия
     */
    public CommunicationQuery withSystem(final String value) {
        return new CommunicationQuery(
                this.direction, Objects.requireNonNull(value, "system"), this.statuses, this.execution, this.requestId);
    }

    /**
     * Только эти статусы.
     *
     * @param values статусы
     * @return новые условия
     */
    public CommunicationQuery withStatuses(final Set<CommunicationStatus> values) {
        return new CommunicationQuery(
                this.direction, this.externalSystem, Set.copyOf(values), this.execution, this.requestId);
    }

    /**
     * Только коммуникации дела — чтение в его партиции, окно не применяется.
     *
     * @param value дело
     * @return новые условия
     */
    public CommunicationQuery inExecution(final ExecutionRef value) {
        return new CommunicationQuery(
                this.direction,
                this.externalSystem,
                this.statuses,
                Objects.requireNonNull(value, "execution"),
                this.requestId);
    }

    /**
     * Только коммуникации, у попытки которых такой {@code request_id}.
     *
     * @param value идентификатор запроса в протоколе внешней системы
     * @return новые условия
     */
    public CommunicationQuery withRequestId(final String value) {
        return new CommunicationQuery(
                this.direction,
                this.externalSystem,
                this.statuses,
                this.execution,
                Objects.requireNonNull(value, "requestId"));
    }

    /**
     * Направление.
     *
     * @return направление или пусто — оба
     */
    public Optional<CommunicationSnapshot.Direction> direction() {
        return Optional.ofNullable(this.direction);
    }

    /**
     * Система.
     *
     * @return система или пусто — все
     */
    public Optional<String> externalSystem() {
        return Optional.ofNullable(this.externalSystem);
    }

    /**
     * Статусы.
     *
     * @return статусы; пусто — все
     */
    public Set<CommunicationStatus> statuses() {
        return this.statuses;
    }

    /**
     * Дело.
     *
     * @return дело или пусто
     */
    public Optional<ExecutionRef> execution() {
        return Optional.ofNullable(this.execution);
    }

    /**
     * Идентификатор запроса.
     *
     * @return идентификатор или пусто
     */
    public Optional<String> requestId() {
        return Optional.ofNullable(this.requestId);
    }
}
