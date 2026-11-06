package xyz.avasilev.taskcomm.communication.internal.settings;

import java.time.Duration;
import java.util.Objects;
import xyz.avasilev.taskcomm.TaskcommConfigurationException;

/**
 * Пороги размыкателя подсистемы (docs/adr/0065-communication-circuit-breaker.md). Числа — отправная
 * точка, а не проверенные нагрузкой значения; поэтому размыкатель выключен по умолчанию.
 *
 * @param enabled включён ли по умолчанию для всех систем
 * @param window окно, за которое считается доля отказов
 * @param minimumCalls минимальное число вызовов в окне для размыкания
 * @param failureRateThreshold доля отказов, при которой размыкается, в {@code (0, 1]}
 * @param backoff ожидание до пробы — растёт по правилам backoff; лимит попыток не используется
 * @param probeCalls сколько вызовов в пробе
 * @param probeTimeout срок пробы
 */
public record BreakerSettings(
        boolean enabled,
        Duration window,
        int minimumCalls,
        double failureRateThreshold,
        RetryPolicy backoff,
        int probeCalls,
        Duration probeTimeout) {

    /** Умолчания: выключен, окно минута, десять вызовов, половина отказов, ожидание 30s–10m, проба из трёх за минуту. */
    static final BreakerSettings DEFAULT = new BreakerSettings(
            false,
            Duration.ofSeconds(60),
            10,
            0.5,
            new RetryPolicy(1, Duration.ofSeconds(30), 2.0, Duration.ofMinutes(10), 0.2),
            3,
            Duration.ofMinutes(1));

    /**
     * Проверяет значения — падение при старте (docs/rules/configuration.md).
     *
     * @throws TaskcommConfigurationException если с такими значениями стартовать нельзя
     */
    public BreakerSettings {
        Objects.requireNonNull(backoff, "backoff");
        if (!(failureRateThreshold > 0 && failureRateThreshold <= 1)) {
            throw new TaskcommConfigurationException(
                    "circuit-breaker.failure-rate-threshold = " + failureRateThreshold + ", а должна лежать в (0, 1].");
        }
        if (minimumCalls < 1 || probeCalls < 1) {
            throw new TaskcommConfigurationException("circuit-breaker: minimum-calls = " + minimumCalls
                    + ", probe.calls = " + probeCalls + " — оба должны быть не меньше 1.");
        }
        Timing.positive("circuit-breaker.window", window);
        Timing.positive("circuit-breaker.probe.timeout", probeTimeout);
    }
}
