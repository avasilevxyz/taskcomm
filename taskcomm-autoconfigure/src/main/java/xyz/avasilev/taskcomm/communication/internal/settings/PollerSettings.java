package xyz.avasilev.taskcomm.communication.internal.settings;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import xyz.avasilev.taskcomm.TaskcommConfigurationException;

/**
 * Итоговые настройки одного поллера коммуникаций (docs/adr/0063-claim-ordering-and-pollers.md).
 * Недопустимое значение не даёт собрать настройки — проверки «падение при старте».
 *
 * @param name имя поллера: {@code main} у основного, ключ {@code taskcomm.communication.pollers}
 *     у выделенного
 * @param systems внешние системы выделенного поллера; у основного пусто — он берёт все, кроме выделенных
 * @param interval пауза между захватами при пустой очереди
 * @param batchSize верхняя граница одного захвата
 * @param workers размер пула воркеров
 * @param form форма захвата
 * @param deadlineStep шаг дедлайна {@code K} в {@code claim_order_at = next_attempt_at + priority × K}
 * @param fifoShare доля захватов по чистому FIFO при форме {@code quota}, в пределах {@code (0, 1)}
 */
public record PollerSettings(
        String name,
        Set<String> systems,
        Duration interval,
        int batchSize,
        int workers,
        ClaimForm form,
        Duration deadlineStep,
        double fifoShare) {

    /** Имя основного поллера. */
    public static final String MAIN = "main";

    /**
     * Проверяет значения и копирует набор систем.
     *
     * @throws TaskcommConfigurationException если с такими значениями стартовать нельзя
     */
    public PollerSettings {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(form, "form");
        systems = Set.copyOf(systems);
        if (MAIN.equals(name) != systems.isEmpty()) {
            throw new TaskcommConfigurationException("Поллер коммуникаций '" + name + "': набор систем задаётся "
                    + "у каждого выделенного поллера и только у них; основной берёт все системы, кроме выделенных.");
        }
        if (interval.isNegative() || interval.isZero()) {
            throw new TaskcommConfigurationException(
                    "Поллер коммуникаций '" + name + "': interval = " + interval + ", а должен быть положительным.");
        }
        if (batchSize < 1 || workers < 1) {
            throw new TaskcommConfigurationException("Поллер коммуникаций '" + name + "': batch-size = " + batchSize
                    + ", workers = " + workers + " — оба должны быть не меньше 1.");
        }
        if (deadlineStep.isNegative() || deadlineStep.isZero()) {
            throw new TaskcommConfigurationException("Поллер коммуникаций '" + name + "': deadline-step = "
                    + deadlineStep + ", а должен быть положительным.");
        }
        if (!(fifoShare > 0 && fifoShare < 1)) {
            throw new TaskcommConfigurationException("Поллер коммуникаций '" + name + "': fifo-share = " + fifoShare
                    + ", а должна лежать в (0, 1). Чистый строгий приоритет — форма priority.");
        }
    }
}
