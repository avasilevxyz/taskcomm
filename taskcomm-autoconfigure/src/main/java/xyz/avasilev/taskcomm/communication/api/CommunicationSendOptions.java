package xyz.avasilev.taskcomm.communication.api;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import xyz.avasilev.taskcomm.execution.api.ExecutionRef;

/**
 * Необязательные параметры создания исходящей: дело, «не раньше», приоритет, ключи дедупликации
 * и корреляции. Неизменяемое значение; каждый метод настройки возвращает новое. Само по себе ничего
 * не создаёт — передаётся в {@link CommunicationService#send(String, Object, CommunicationSendOptions)}.
 *
 * <pre>{@code
 * communicationService.send("supplier-b", update, CommunicationSendOptions.defaults()
 *         .inExecution(context.executionRef())
 *         .dedupKey(update.sku(), update.priceHash())
 *         .correlationId(ref));
 * }</pre>
 */
public final class CommunicationSendOptions {

    private static final CommunicationSendOptions DEFAULTS =
            new CommunicationSendOptions(null, false, null, 0, List.of(), List.of());

    private final ExecutionRef executionRef;
    private final boolean newExecution;
    private final Instant notBefore;
    private final int priority;
    private final List<DedupKey> dedupKeys;
    private final List<CorrelationKey> correlationKeys;

    private CommunicationSendOptions(
            final ExecutionRef executionRef,
            final boolean newExecution,
            final Instant notBefore,
            final int priority,
            final List<DedupKey> dedupKeys,
            final List<CorrelationKey> correlationKeys) {
        this.executionRef = executionRef;
        this.newExecution = newExecution;
        this.notBefore = notBefore;
        this.priority = priority;
        this.dedupKeys = dedupKeys;
        this.correlationKeys = correlationKeys;
    }

    /**
     * Опции по умолчанию: дело по правилам привязки, без отсрочки, приоритет системы, без ключей.
     *
     * @return опции по умолчанию
     */
    public static CommunicationSendOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Создаёт коммуникацию в указанном деле — из любого потока. Отменяет {@link #inNewExecution()}.
     *
     * @param execution ссылка на дело
     * @return новые опции
     */
    public CommunicationSendOptions inExecution(final ExecutionRef execution) {
        return new CommunicationSendOptions(
                Objects.requireNonNull(execution, "execution"),
                false,
                this.notBefore,
                this.priority,
                this.dedupKeys,
                this.correlationKeys);
    }

    /**
     * Отказ от привязки: коммуникация заводит новое дело, даже внутри процессора. Отменяет
     * {@link #inExecution(ExecutionRef)}.
     *
     * @return новые опции
     */
    public CommunicationSendOptions inNewExecution() {
        return new CommunicationSendOptions(
                null, true, this.notBefore, this.priority, this.dedupKeys, this.correlationKeys);
    }

    /**
     * Не захватывать раньше момента — по часам <b>базы</b>, с точностью до такта поллера
     * (docs/adr/0050-deferred-start.md).
     *
     * @param moment момент, не раньше которого коммуникацию можно взять
     * @return новые опции
     */
    public CommunicationSendOptions notBefore(final Instant moment) {
        return new CommunicationSendOptions(
                this.executionRef,
                this.newExecution,
                Objects.requireNonNull(moment, "moment"),
                this.priority,
                this.dedupKeys,
                this.correlationKeys);
    }

    /**
     * Приоритет вместо умолчания системы. Шкала по возрастанию: <b>меньше значит важнее</b>.
     *
     * @param value приоритет, больше нуля
     * @return новые опции
     * @throws IllegalArgumentException если значение не больше нуля
     */
    public CommunicationSendOptions priority(final int value) {
        if (value <= 0) {
            throw new IllegalArgumentException("Приоритет должен быть больше нуля, а передан " + value);
        }
        return new CommunicationSendOptions(
                this.executionRef, this.newExecution, this.notBefore, value, this.dedupKeys, this.correlationKeys);
    }

    /**
     * Ключ дедупликации по всей истории. Проверяется в транзакции создания, только при
     * {@code READ COMMITTED} (docs/adr/0095-dedup-requires-read-committed.md).
     *
     * @param key значение ключа
     * @return новые опции
     */
    public CommunicationSendOptions dedupKey(final String key) {
        return this.withDedup(new DedupKey.WholeHistory(key));
    }

    /**
     * Ключ дедупликации по последней успешной с этим предметом.
     *
     * @param subject предмет
     * @param key значение ключа
     * @return новые опции
     */
    public CommunicationSendOptions dedupKey(final String subject, final String key) {
        return this.withDedup(new DedupKey.LastSucceeded(subject, key));
    }

    /**
     * Ключ корреляции от системы-получателя. <b>Включает ожидание асинхронного ответа</b>
     * (docs/adr/0104-wait-defined-by-correlation-key.md).
     *
     * @param correlationId значение ключа
     * @return новые опции
     */
    public CommunicationSendOptions correlationId(final String correlationId) {
        return this.withCorrelation(CorrelationKey.of(correlationId));
    }

    /**
     * Ключ корреляции от явно названной системы. Ожидание включает, только если это система-получатель.
     *
     * @param externalSystem система, от которой ждут колбэк
     * @param correlationId значение ключа
     * @return новые опции
     */
    public CommunicationSendOptions correlationId(final String externalSystem, final String correlationId) {
        return this.withCorrelation(CorrelationKey.of(externalSystem, correlationId));
    }

    /**
     * Явно указанное дело.
     *
     * @return ссылка на дело или пусто
     */
    public Optional<ExecutionRef> executionRef() {
        return Optional.ofNullable(this.executionRef);
    }

    /**
     * Заводить ли новое дело вместо привязки.
     *
     * @return {@code true}, если вызван {@link #inNewExecution()}
     */
    public boolean newExecution() {
        return this.newExecution;
    }

    /**
     * Момент «не раньше».
     *
     * @return момент или пусто
     */
    public Optional<Instant> notBefore() {
        return Optional.ofNullable(this.notBefore);
    }

    /**
     * Явный приоритет.
     *
     * @return приоритет или пусто — тогда умолчание системы
     */
    public Optional<Integer> priority() {
        return this.priority == 0 ? Optional.empty() : Optional.of(this.priority);
    }

    /**
     * Ключи дедупликации.
     *
     * @return ключи в порядке добавления
     */
    public List<DedupKey> dedupKeys() {
        return this.dedupKeys;
    }

    /**
     * Ключи корреляции.
     *
     * @return ключи в порядке добавления
     */
    public List<CorrelationKey> correlationKeys() {
        return this.correlationKeys;
    }

    private CommunicationSendOptions withDedup(final DedupKey key) {
        final List<DedupKey> keys = new ArrayList<>(this.dedupKeys);
        keys.add(key);
        return new CommunicationSendOptions(
                this.executionRef,
                this.newExecution,
                this.notBefore,
                this.priority,
                List.copyOf(keys),
                this.correlationKeys);
    }

    private CommunicationSendOptions withCorrelation(final CorrelationKey key) {
        final List<CorrelationKey> keys = new ArrayList<>(this.correlationKeys);
        keys.add(key);
        return new CommunicationSendOptions(
                this.executionRef, this.newExecution, this.notBefore, this.priority, this.dedupKeys, List.copyOf(keys));
    }
}
