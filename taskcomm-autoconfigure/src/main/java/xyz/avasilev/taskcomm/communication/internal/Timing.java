package xyz.avasilev.taskcomm.communication.internal;

import java.time.Duration;
import xyz.avasilev.taskcomm.TaskcommConfigurationException;

/**
 * Сроки подсистемы коммуникаций, не зависящие от фазы: срок ожидания асинхронного ответа по умолчанию
 * (docs/adr/0086-async-response-in-outbound.md) и такт подстраховочного поллера, который находит
 * истёкшие ожидания (docs/adr/0136-communication-dedup-and-callbacks.md).
 *
 * @param responseTimeout срок ожидания ответа по умолчанию
 * @param safetyInterval такт подстраховочного поллера
 */
public record Timing(Duration responseTimeout, Duration safetyInterval) {

    /** Умолчания: ответ — час, такт подстраховки — минута. */
    public static final Timing DEFAULT = new Timing(Duration.ofHours(1), Duration.ofMinutes(1));

    /**
     * Проверяет, что оба срока положительны.
     *
     * @throws TaskcommConfigurationException если срок не положительный
     */
    public Timing {
        positive("response-timeout", responseTimeout);
        positive("safety.interval", safetyInterval);
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
