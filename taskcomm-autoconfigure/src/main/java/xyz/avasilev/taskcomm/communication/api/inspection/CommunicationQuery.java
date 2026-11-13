package xyz.avasilev.taskcomm.communication.api.inspection;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import xyz.avasilev.taskcomm.communication.api.CommunicationStatus;
import xyz.avasilev.taskcomm.execution.api.ExecutionRef;

/**
 * Условия выдачи коммуникаций: направление, система, статусы, дело, идентификатор запроса, ключ
 * дедупликации, ключ корреляции (docs/adr/0138-inspection-api.md). Неизменяемое значение; каждый
 * метод {@code with…} возвращает новое.
 *
 * <p>Поиск по {@code request_id} — операция разбора инцидента: лукап идёт в окне чтения истории
 * и в горячих путях не годится (docs/adr/0055-communication-exchange-storage.md). Поиск по ключу
 * дедупликации — тоже: быстрым его делают направление и система, заданные вместе с ключом
 * (docs/adr/0156-communication-keys-and-pollers-in-reading.md).
 */
public final class CommunicationQuery {

    private static final CommunicationQuery ALL =
            new CommunicationQuery(null, null, Set.of(), null, null, null, null, null, null);

    private final CommunicationSnapshot.Direction direction;

    private final String externalSystem;

    private final Set<CommunicationStatus> statuses;

    private final ExecutionRef execution;

    private final String requestId;

    private final String dedupSubject;

    private final String dedupKey;

    private final String correlationSystem;

    private final String correlationId;

    private CommunicationQuery(
            final CommunicationSnapshot.Direction direction,
            final String externalSystem,
            final Set<CommunicationStatus> statuses,
            final ExecutionRef execution,
            final String requestId,
            final String dedupSubject,
            final String dedupKey,
            final String correlationSystem,
            final String correlationId) {
        this.direction = direction;
        this.externalSystem = externalSystem;
        this.statuses = statuses;
        this.execution = execution;
        this.requestId = requestId;
        this.dedupSubject = dedupSubject;
        this.dedupKey = dedupKey;
        this.correlationSystem = correlationSystem;
        this.correlationId = correlationId;
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
                this.requestId,
                this.dedupSubject,
                this.dedupKey,
                this.correlationSystem,
                this.correlationId);
    }

    /**
     * Только эта внешняя система.
     *
     * @param value система
     * @return новые условия
     */
    public CommunicationQuery withSystem(final String value) {
        return new CommunicationQuery(
                this.direction,
                Objects.requireNonNull(value, "system"),
                this.statuses,
                this.execution,
                this.requestId,
                this.dedupSubject,
                this.dedupKey,
                this.correlationSystem,
                this.correlationId);
    }

    /**
     * Только эти статусы.
     *
     * @param values статусы
     * @return новые условия
     */
    public CommunicationQuery withStatuses(final Set<CommunicationStatus> values) {
        return new CommunicationQuery(
                this.direction,
                this.externalSystem,
                Set.copyOf(values),
                this.execution,
                this.requestId,
                this.dedupSubject,
                this.dedupKey,
                this.correlationSystem,
                this.correlationId);
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
                this.requestId,
                this.dedupSubject,
                this.dedupKey,
                this.correlationSystem,
                this.correlationId);
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
                Objects.requireNonNull(value, "requestId"),
                this.dedupSubject,
                this.dedupKey,
                this.correlationSystem,
                this.correlationId);
    }

    /**
     * Только коммуникации с таким ключом дедупликации по всей истории — без предмета. Ключи
     * дубликатов не хранятся, поэтому находится коммуникация, которой ключ принадлежит, а не её
     * дубликаты.
     *
     * @param key значение ключа
     * @return новые условия
     */
    public CommunicationQuery withDedupKey(final String key) {
        return this.dedup(null, Objects.requireNonNull(key, "dedupKey"));
    }

    /**
     * Только коммуникации с таким ключом дедупликации по последней успешной — с предметом.
     *
     * @param subject предмет ключа
     * @param key значение ключа
     * @return новые условия
     */
    public CommunicationQuery withDedupKey(final String subject, final String key) {
        return this.dedup(Objects.requireNonNull(subject, "dedupSubject"), Objects.requireNonNull(key, "dedupKey"));
    }

    /**
     * Только коммуникация-якорь этого ключа корреляции. Система ключа может не совпадать с системой
     * коммуникации (docs/adr/0075-correlation-key-scoped-by-system.md), поэтому задаётся отдельно.
     *
     * @param system система, от которой ждут колбэк
     * @param value значение ключа
     * @return новые условия
     */
    public CommunicationQuery withCorrelationKey(final String system, final String value) {
        return new CommunicationQuery(
                this.direction,
                this.externalSystem,
                this.statuses,
                this.execution,
                this.requestId,
                this.dedupSubject,
                this.dedupKey,
                Objects.requireNonNull(system, "correlationSystem"),
                Objects.requireNonNull(value, "correlationId"));
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

    /**
     * Ключ дедупликации.
     *
     * @return значение ключа или пусто
     */
    public Optional<String> dedupKey() {
        return Optional.ofNullable(this.dedupKey);
    }

    /**
     * Предмет ключа дедупликации.
     *
     * @return предмет или пусто — ключ по всей истории либо ключа нет
     */
    public Optional<String> dedupSubject() {
        return Optional.ofNullable(this.dedupSubject);
    }

    /**
     * Система ключа корреляции.
     *
     * @return система или пусто
     */
    public Optional<String> correlationSystem() {
        return Optional.ofNullable(this.correlationSystem);
    }

    /**
     * Значение ключа корреляции.
     *
     * @return значение или пусто
     */
    public Optional<String> correlationId() {
        return Optional.ofNullable(this.correlationId);
    }

    private CommunicationQuery dedup(final String subject, final String key) {
        return new CommunicationQuery(
                this.direction,
                this.externalSystem,
                this.statuses,
                this.execution,
                this.requestId,
                subject,
                key,
                this.correlationSystem,
                this.correlationId);
    }
}
