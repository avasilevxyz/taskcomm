package xyz.avasilev.taskcomm.communication.internal.settings;

import java.time.Duration;
import xyz.avasilev.taskcomm.TaskcommConfigurationException;

/**
 * Сроки подсистемы коммуникаций, не зависящие от фазы: срок ожидания асинхронного ответа по умолчанию
 * (docs/adr/0086-async-response-in-outbound.md) и такт подстраховочного поллера, который находит
 * истёкшие ожидания (docs/adr/0136-communication-dedup-and-callbacks.md).
 *
 * @param responseTimeout срок ожидания ответа по умолчанию
 * @param safetyInterval такт подстраховочного поллера
 * @param syncWait предел ожидания защиты отправки синхронной исходящей по умолчанию
 *     (docs/adr/0167-communication-run-mode.md)
 */
public record Timing(Duration responseTimeout, Duration safetyInterval, Duration syncWait) {

    /** Умолчания: ответ — час, такт подстраховки — минута, ожидание защиты синхронной — 30 секунд. */
    public static final Timing DEFAULT = new Timing(Duration.ofHours(1), Duration.ofMinutes(1), Duration.ofSeconds(30));

    /**
     * Проверяет, что оба срока положительны.
     *
     * @throws TaskcommConfigurationException если срок не положительный
     */
    public Timing {
        positive("response-timeout", responseTimeout);
        positive("safety.interval", safetyInterval);
        notNegative("sync-wait", syncWait);
    }

    /**
     * Проверяет срок, которому ноль позволен.
     *
     * @param name имя свойства для сообщения
     * @param value срок
     * @throws TaskcommConfigurationException если срока нет или он отрицательный
     */
    static void notNegative(final String name, final Duration value) {
        if (value == null || value.isNegative()) {
            throw new TaskcommConfigurationException(name + " = " + value + ", а должен быть не меньше нуля.");
        }
    }

    /**
     * Проверяет срок.
     *
     * @param name имя свойства для сообщения
     * @param value срок
     * @throws TaskcommConfigurationException если срок не положительный
     */
    static void positive(final String name, final Duration value) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new TaskcommConfigurationException(name + " = " + value + ", а должен быть положительным.");
        }
    }
}
