package xyz.avasilev.taskcomm.communication.api;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Параметры синхронной отправки исходящей (docs/adr/0167-communication-run-mode.md): параметры создания,
 * ожидание колбэка и предел ожидания размыкателя и ограничителя скорости. Неизменяемое значение;
 * каждый метод настройки возвращает новое.
 *
 * <pre>{@code
 * communicationService.sendAndProcess("bank", payment, SyncSendOptions.defaults()
 *         .send(CommunicationSendOptions.defaults().dedupKey(payment.id()))
 *         .awaitResponse(Duration.ofSeconds(20)));
 * }</pre>
 */
public final class SyncSendOptions {

    private static final SyncSendOptions DEFAULTS =
            new SyncSendOptions(CommunicationSendOptions.defaults(), null, null);

    private final CommunicationSendOptions send;
    private final Duration awaitResponse;
    private final Duration syncWait;

    private SyncSendOptions(
            final CommunicationSendOptions send, final Duration awaitResponse, final Duration syncWait) {
        this.send = send;
        this.awaitResponse = awaitResponse;
        this.syncWait = syncWait;
    }

    /**
     * Опции по умолчанию: создание по умолчанию, колбэка не ждать, предел ожидания — {@code sync-wait}
     * системы.
     *
     * @return опции по умолчанию
     */
    public static SyncSendOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Параметры создания: дело, ключи дедупликации и корреляции. «Не раньше» у синхронной отправки
     * не бывает — ответ нужен сейчас; приоритет на её обработку не влияет — очереди у неё нет.
     *
     * @param options параметры создания
     * @return новые опции
     */
    public SyncSendOptions send(final CommunicationSendOptions options) {
        return new SyncSendOptions(Objects.requireNonNull(options, "options"), this.awaitResponse, this.syncWait);
    }

    /**
     * Ждать колбэк — опросом строки коммуникации, не дольше предела. Не задано — вызов возвращается после
     * синхронного ответа, колбэк обработает движок в {@code handleResponse}. Всё ожидание поток занят.
     *
     * @param limit предел ожидания, больше нуля
     * @return новые опции
     * @throws IllegalArgumentException если предел не больше нуля
     */
    public SyncSendOptions awaitResponse(final Duration limit) {
        return new SyncSendOptions(this.send, positive(limit, "awaitResponse"), this.syncWait);
    }

    /**
     * Предел ожидания разомкнутого размыкателя и слота ограничителя скорости вместо {@code sync-wait}
     * системы. Истёк — {@link SendAndProcessResult.Unavailable}, коммуникация не создаётся.
     *
     * @param limit предел, не меньше нуля; ноль — не ждать
     * @return новые опции
     * @throws IllegalArgumentException если предел меньше нуля
     */
    public SyncSendOptions syncWait(final Duration limit) {
        Objects.requireNonNull(limit, "syncWait");
        if (limit.isNegative()) {
            throw new IllegalArgumentException("Предел ожидания не может быть отрицательным, а передан " + limit);
        }
        return new SyncSendOptions(this.send, this.awaitResponse, limit);
    }

    /**
     * Параметры создания.
     *
     * @return параметры создания
     */
    public CommunicationSendOptions send() {
        return this.send;
    }

    /**
     * Предел ожидания колбэка.
     *
     * @return предел или пусто — колбэка не ждать
     */
    public Optional<Duration> awaitResponse() {
        return Optional.ofNullable(this.awaitResponse);
    }

    /**
     * Предел ожидания размыкателя и ограничителя скорости.
     *
     * @return предел или пусто — {@code sync-wait} системы
     */
    public Optional<Duration> syncWait() {
        return Optional.ofNullable(this.syncWait);
    }

    private static Duration positive(final Duration limit, final String name) {
        Objects.requireNonNull(limit, name);
        if (limit.isNegative() || limit.isZero()) {
            throw new IllegalArgumentException("Предел " + name + " должен быть больше нуля, а передан " + limit);
        }
        return limit;
    }
}
