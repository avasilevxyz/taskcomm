package xyz.avasilev.taskcomm.communication.internal;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;
import xyz.avasilev.taskcomm.execution.internal.UuidV7;

/**
 * Движок коммуникаций экземпляра: поллеры со своими пулами воркеров и поток heartbeat. Тот же heartbeat
 * продлевает попытки синхронного приёма, идущие в потоках вызывающих
 * (docs/adr/0133-synchronous-inbound-reception.md). Своя реализация — общего кода с движком задач нет
 * (docs/adr/0010-separate-engines.md).
 *
 * <p>Такты — на планировщике потребителя под {@code @TaskcommScheduler} или собственном; пулы
 * воркеров — только свои, потоки из фабрики под {@code @TaskcommWorkerThreadFactory} или собственной
 * (docs/adr/0096-no-foreign-type-beans.md). Остановка — как у движка задач: воркеры дорабатывают не
 * дольше {@link #SHUTDOWN_TIMEOUT}, невернувшиеся не прерываются.
 */
@Slf4j
public class CommunicationEngine implements SmartLifecycle {

    /** Сколько ждать воркеров при остановке. */
    static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(25);

    private static final String THREAD_PREFIX = "taskcomm-communication-";

    private final List<CommunicationPoller> pollers;

    private final CommunicationHeartbeat heartbeat;

    private final CommunicationWorker worker;

    private final CommunicationSafetyPoller safety;

    private final Duration safetyInterval;

    private final String instance;

    private final Duration heartbeatTick;

    private final TaskScheduler externalScheduler;

    private final List<ScheduledFuture<?>> futures = new ArrayList<>();

    private ThreadPoolTaskScheduler ownScheduler;

    private boolean running;

    /**
     * Собирает движок.
     *
     * @param dataSource {@code DataSource} стартера
     * @param transactionManager менеджер транзакций стартера
     * @param ids генератор идентификаторов
     * @param settings итоговые настройки коммуникаций
     * @param registry реестр процессоров
     * @param codec кодек доменных форм
     * @param threadFactory фабрика потоков воркеров потребителя или {@code null}
     * @param decorator декоратор запусков воркеров потребителя или {@code null}
     * @param scheduler планировщик потребителя или {@code null}
     */
    public CommunicationEngine(
            final DataSource dataSource,
            final PlatformTransactionManager transactionManager,
            final UuidV7 ids,
            final CommunicationSettings settings,
            final CommunicationProcessorRegistry registry,
            final CommunicationCodec codec,
            final ThreadFactory threadFactory,
            final TaskDecorator decorator,
            final TaskScheduler scheduler) {
        this.instance = ManagementFactory.getRuntimeMXBean().getName();
        final CommunicationRetries retries = new CommunicationRetries(
                settings, () -> ThreadLocalRandom.current().nextDouble(), ids);
        final CommunicationClaimer claimer = new CommunicationClaimer(
                dataSource,
                transactionManager,
                settings,
                retries,
                () -> ThreadLocalRandom.current().nextDouble(),
                this.instance);
        this.heartbeat = new CommunicationHeartbeat(dataSource, transactionManager);
        this.heartbeatTick = settings.heartbeatTick();
        this.externalScheduler = scheduler;
        this.worker = new CommunicationWorker(
                registry,
                codec,
                new CommunicationAttemptCloser(dataSource, transactionManager, retries),
                this.heartbeat,
                new CommunicationExchange(dataSource, transactionManager, codec),
                settings,
                new ProcessorKeys(dataSource, transactionManager, ids));
        this.safety =
                new CommunicationSafetyPoller(dataSource, transactionManager, settings, retries, registry.systems());
        this.safetyInterval = settings.safetyInterval();
        final PollerLayout layout = settings.pollers();
        final List<PollerSettings> all = new ArrayList<>();
        all.add(layout.main());
        all.addAll(layout.dedicated());
        this.pollers = all.stream()
                .map(poller -> new CommunicationPoller(
                        poller,
                        claimable(layout, poller, registry),
                        claimer,
                        this.worker,
                        threadFactory != null ? threadFactory : workerThreads(poller),
                        decorator))
                .toList();
    }

    @Override
    public synchronized void start() {
        if (this.running) {
            return;
        }
        final TaskScheduler scheduler = this.externalScheduler != null ? this.externalScheduler : this.createOwn();
        for (final CommunicationPoller poller : this.pollers) {
            this.futures.add(scheduler.scheduleWithFixedDelay(
                    poller::poll, Instant.now(), poller.settings().interval()));
        }
        this.futures.add(scheduler.scheduleWithFixedDelay(
                this::renewLeases, Instant.now().plus(this.heartbeatTick), this.heartbeatTick));
        this.futures.add(scheduler.scheduleWithFixedDelay(
                this::runSafety, Instant.now().plus(this.safetyInterval), this.safetyInterval));
        this.running = true;
    }

    @Override
    public synchronized void stop() {
        if (!this.running) {
            return;
        }
        this.futures.subList(0, this.pollers.size()).forEach(future -> future.cancel(false));
        for (final CommunicationPoller poller : this.pollers) {
            if (!poller.stop(SHUTDOWN_TIMEOUT)) {
                log.warn(
                        "Поллер коммуникаций '{}': воркеры не вернулись за {}. Их попытки закроет переподхват "
                                + "истёкшей аренды, коммуникации выполнятся повторно.",
                        poller.settings().name(),
                        SHUTDOWN_TIMEOUT);
            }
        }
        this.futures.forEach(future -> future.cancel(false));
        this.futures.clear();
        if (this.ownScheduler != null) {
            this.ownScheduler.shutdown();
            this.ownScheduler = null;
        }
        this.running = false;
    }

    @Override
    public synchronized boolean isRunning() {
        return this.running;
    }

    /**
     * Воркер для синхронного приёма — тот же, что у поллеров, с тем же heartbeat.
     *
     * @return воркер
     */
    CommunicationWorker worker() {
        return this.worker;
    }

    /**
     * Имя этого экземпляра — пишется в {@code locked_by}.
     *
     * @return имя экземпляра
     */
    String instance() {
        return this.instance;
    }

    private void renewLeases() {
        try {
            this.heartbeat.renewDue(this.heartbeatTick);
            this.heartbeat.refreshCancellations();
        } catch (final RuntimeException failure) {
            log.warn("Heartbeat коммуникаций: продление аренд не удалось, повтор на следующем такте", failure);
        }
    }

    private void runSafety() {
        try {
            this.safety.tick();
        } catch (final RuntimeException failure) {
            log.warn("Подстраховочный поллер коммуникаций: такт не удался, повтор на следующем", failure);
        }
    }

    private ThreadPoolTaskScheduler createOwn() {
        final ThreadPoolTaskScheduler own = new ThreadPoolTaskScheduler();
        own.setPoolSize(2);
        own.setThreadNamePrefix(THREAD_PREFIX + "scheduler-");
        own.setDaemon(true);
        own.initialize();
        this.ownScheduler = own;
        return own;
    }

    private static Map<String, ContractShape> claimable(
            final PollerLayout layout, final PollerSettings poller, final CommunicationProcessorRegistry registry) {
        final Map<String, ContractShape> result = new LinkedHashMap<>();
        layout.claimableSystems(poller, registry.systems())
                .forEach(system ->
                        result.put(system, registry.find(system).orElseThrow().shape()));
        return result;
    }

    private static ThreadFactory workerThreads(final PollerSettings poller) {
        final CustomizableThreadFactory factory = new CustomizableThreadFactory(THREAD_PREFIX + poller.name() + "-");
        factory.setDaemon(true);
        return factory;
    }
}
