package xyz.avasilev.taskcomm.autoconfigure;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * Здоровье движков экземпляра — <b>живость собственных потоков, и ничего больше</b>
 * (docs/adr/0051-engine-health.md). {@code DOWN}, если поток поллера или heartbeat любой подсистемы
 * не тикал дольше {@code taskcomm.health.missed-intervals} своих интервалов.
 *
 * <p>Длины очереди, истёкших аренд и доступности БД здесь нет и быть не должно: индикатор, краснеющий
 * при исправной работе под нагрузкой, обесценил бы мониторинг всего приложения.
 */
final class TaskcommHealthIndicator implements HealthIndicator {

    private final Map<String, Supplier<List<Mechanism>>> engines;

    private final int missedIntervals;

    /**
     * Собирает индикатор.
     *
     * @param engines механизмы движков по подсистемам: {@code task}, {@code communication}
     * @param missedIntervals сколько интервалов поток может не тикать
     */
    TaskcommHealthIndicator(final Map<String, Supplier<List<Mechanism>>> engines, final int missedIntervals) {
        this.engines = Map.copyOf(engines);
        this.missedIntervals = missedIntervals;
    }

    @Override
    public Health health() {
        final Map<String, Object> details = new LinkedHashMap<>();
        boolean stalled = false;
        for (final Map.Entry<String, Supplier<List<Mechanism>>> engine : new TreeMap<>(this.engines).entrySet()) {
            for (final Mechanism mechanism : engine.getValue().get()) {
                final Duration limit = mechanism.interval().multipliedBy(this.missedIntervals);
                final boolean dead = mechanism.silence().compareTo(limit) > 0;
                stalled |= dead;
                details.put(
                        engine.getKey() + "." + mechanism.name(),
                        Map.of(
                                "status", dead ? "STALLED" : "TICKING",
                                "silence", mechanism.silence().toString(),
                                "limit", limit.toString()));
            }
        }
        return (stalled ? Health.down() : Health.up()).withDetails(details).build();
    }

    /**
     * Живость одного механизма движка.
     *
     * @param name механизм: {@code poller.<имя>} или {@code heartbeat}
     * @param interval его интервал
     * @param silence сколько прошло с последнего такта
     */
    record Mechanism(String name, Duration interval, Duration silence) {}
}
