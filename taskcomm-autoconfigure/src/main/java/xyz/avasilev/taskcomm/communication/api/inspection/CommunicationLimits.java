package xyz.avasilev.taskcomm.communication.api.inspection;

import java.time.Duration;
import java.util.Map;

/**
 * Пределы коммуникаций одной системы — итог лестницы настроек (docs/adr/0171-durations-and-limits.md).
 *
 * @param maxExecutionTimes предел одной попытки по фазе: {@code PROCESS}, {@code HANDLE_RESPONSE}
 * @param responseTimeout сколько ждать асинхронного ответа до повторной отправки
 */
public record CommunicationLimits(Map<String, Duration> maxExecutionTimes, Duration responseTimeout) {

    /**
     * Делает отображение неизменяемым.
     *
     * @throws NullPointerException если отображение пустое
     */
    public CommunicationLimits {
        maxExecutionTimes = Map.copyOf(maxExecutionTimes);
    }
}
