package xyz.avasilev.taskcomm.communication.internal;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.ToIntFunction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskDecorator;

/**
 * Поллер коммуникаций: свой пул воркеров размером {@code workers} и цикл захвата
 * (docs/adr/0063-claim-ordering-and-pollers.md). <b>Захват — не больше свободных воркеров</b>
 * (docs/adr/0083-claim-limited-by-free-workers.md); полный захват — следующий сразу, как освободится
 * воркер. Захваты одного поллера не идут параллельно. Своя копия — общего кода с задачами нет.
 */
@Slf4j
class CommunicationPoller {

    private final PollerSettings settings;

    private final Map<String, ContractShape> systems;

    private final CommunicationClaimer claimer;

    private final CommunicationWorker worker;

    private final CircuitBreakers breakers;

    private final ToIntFunction<String> breakerCalls;

    private final ThreadPoolExecutor pool;

    private final Semaphore free;

    private final AtomicBoolean polling = new AtomicBoolean();

    private final AtomicBoolean requested = new AtomicBoolean();

    private volatile boolean queueMayHaveMore;

    private volatile boolean running = true;

    CommunicationPoller(
            final PollerSettings settings,
            final Map<String, ContractShape> systems,
            final CommunicationClaimer claimer,
            final CommunicationWorker worker,
            final ThreadFactory threadFactory,
            final TaskDecorator decorator,
            final CircuitBreakers breakers,
            final ToIntFunction<String> breakerCalls) {
        this.settings = settings;
        this.systems = Map.copyOf(systems);
        this.claimer = claimer;
        this.worker = worker;
        this.breakers = breakers;
        this.breakerCalls = breakerCalls;
        this.free = new Semaphore(settings.workers());
        this.pool =
                new ThreadPoolExecutor(
                        settings.workers(),
                        settings.workers(),
                        0,
                        TimeUnit.MILLISECONDS,
                        new LinkedBlockingQueue<>(),
                        threadFactory) {
                    @Override
                    public void execute(final Runnable command) {
                        super.execute(decorator == null ? command : decorator.decorate(command));
                    }
                };
    }

    PollerSettings settings() {
        return this.settings;
    }

    /** Захватывает работу, пока есть свободные воркеры и полные захваты. */
    void poll() {
        this.requested.set(true);
        while (this.requested.get() && this.polling.compareAndSet(false, true)) {
            try {
                this.requested.set(false);
                this.claimWhileFull();
            } finally {
                this.polling.set(false);
            }
        }
    }

    /**
     * Перестаёт захватывать и ждёт работающих воркеров не дольше {@code timeout}.
     *
     * @param timeout сколько ждать
     * @return {@code true}, если все воркеры вернулись
     */
    boolean stop(final Duration timeout) {
        this.running = false;
        this.pool.shutdown();
        try {
            return this.pool.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void claimWhileFull() {
        this.breakers.dueProbes(this.systems.keySet()).forEach(this::probe);
        boolean full = true;
        while (full && this.running) {
            final Map<String, ContractShape> claimable = this.claimable();
            final int ask = Math.min(this.settings.batchSize(), this.free.availablePermits());
            full = !claimable.isEmpty() && ask > 0 && this.free.tryAcquire(ask) && this.claim(claimable, ask);
        }
    }

    /** Системы поллера, чей размыкатель замкнут; все разомкнуты — в базу не ходим. */
    private Map<String, ContractShape> claimable() {
        final Map<String, ContractShape> claimable = new HashMap<>();
        this.systems.forEach((system, shape) -> {
            if (this.breakers.claimable(system)) {
                claimable.put(system, shape);
            }
        });
        return claimable;
    }

    /**
     * Проба разомкнутой системы — отдельный захват не больше {@code probe.calls} её строк
     * (docs/adr/0065-communication-circuit-breaker.md).
     */
    private void probe(final String system) {
        final int ask = Math.min(this.free.availablePermits(), this.breakerCalls.applyAsInt(system));
        if (ask == 0 || !this.free.tryAcquire(ask)) {
            this.breakers.postponeProbe(system);
            return;
        }
        List<ClaimedCommunication> claimed = List.of();
        try {
            claimed = this.claimer.claim(this.settings, Map.of(system, this.systems.get(system)), ask);
        } catch (final RuntimeException failure) {
            log.warn("Поллер коммуникаций '{}': пробный захват '{}' не удался", this.settings.name(), system, failure);
        }
        this.free.release(ask - claimed.size());
        this.breakers.probeClaimed(system, claimed.size());
        claimed.forEach(this::start);
    }

    private boolean claim(final Map<String, ContractShape> claimable, final int ask) {
        final List<ClaimedCommunication> claimed;
        try {
            claimed = this.claimer.claim(this.settings, claimable, ask);

        } catch (final RuntimeException failure) {
            this.free.release(ask);
            log.warn("Поллер коммуникаций '{}': захват не удался, повтор по такту", this.settings.name(), failure);
            return false;
        }
        this.free.release(ask - claimed.size());
        this.queueMayHaveMore = claimed.size() == ask;
        claimed.forEach(this::start);
        return this.queueMayHaveMore;
    }

    private void start(final ClaimedCommunication attempt) {
        try {
            this.pool.execute(() -> this.run(attempt));
        } catch (final RejectedExecutionException stopped) {
            this.free.release();
            log.debug(
                    "Поллер коммуникаций '{}' остановлен; попытку {} закроет переподхват истёкшей аренды",
                    this.settings.name(),
                    attempt.lease().attemptId());
        }
    }

    private void run(final ClaimedCommunication attempt) {
        try {
            this.worker.execute(attempt);
        } finally {
            this.free.release();
            if (this.queueMayHaveMore && this.running) {
                this.poll();
            }
        }
    }
}
