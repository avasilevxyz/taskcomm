package xyz.avasilev.taskcomm.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import xyz.avasilev.taskcomm.TaskcommConfigurationException;
import xyz.avasilev.taskcomm.TestPostgres;
import xyz.avasilev.taskcomm.task.api.Task;
import xyz.avasilev.taskcomm.task.api.TaskService;
import xyz.avasilev.taskcomm.task.api.processor.FlatTaskProcessor;
import xyz.avasilev.taskcomm.task.api.processor.ProcessingContext;

/**
 * Наблюдаемость экземпляра (docs/adr/0141-observability-instance.md): MDC вокруг процессора снимается
 * восстановлением снимка, метрики попыток — в реестре потребителя, health indicator — живость потоков;
 * без Micrometer и Actuator стартер молчит и не падает; предупреждение о нуле процессоров.
 */
@ExtendWith(OutputCaptureExtension.class)
class TaskcommObservabilityAutoConfigurationIT {

    private static final Duration WAIT = Duration.ofSeconds(20);

    private static final String NO_PROCESSORS = "не зарегистрировано ни одного процессора";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    TaskcommAutoConfiguration.class,
                    TaskcommSchemaAutoConfiguration.class,
                    TaskcommTaskAutoConfiguration.class,
                    TaskcommCommunicationAutoConfiguration.class,
                    TaskcommObservabilityAutoConfiguration.class))
            .withUserConfiguration(TaskcommTaskAutoConfigurationIT.Database.class)
            .withBean(String.class, TestPostgres::newSchema)
            .withPropertyValues(
                    "taskcomm.schema.initialize=always",
                    "taskcomm.task.poll.interval=100ms",
                    "taskcomm.task.poll.workers=1");

    @Test
    void mdcAroundProcessorIsRestoredBySnapshot() {
        this.contextRunner.withUserConfiguration(Probe.class).run(context -> {
            final TaskService tasks = context.getBean(TaskService.class);
            final MdcProbe probe = context.getBean(MdcProbe.class);
            final Task<String> first = tasks.submit("mdc", "first");
            await().atMost(WAIT).until(() -> probe.seen.containsKey("first"));
            final Task<String> second = tasks.submit("mdc", "second");
            await().atMost(WAIT).until(() -> probe.seen.containsKey("second"));

            assertThat(probe.seen.get("first"))
                    .containsEntry("tc.taskId", first.id().toString())
                    .containsEntry("tc.executionId", first.executionRef().id().toString())
                    .containsEntry("tc.attempt", "1");
            assertThat(probe.seen.get("second"))
                    .containsEntry("tc.taskId", second.id().toString())
                    .doesNotContainKey("businessId");
        });
    }

    @Test
    void attemptMetricsGoToConsumerRegistry() {
        this.contextRunner.withUserConfiguration(Probe.class, Registry.class).run(context -> {
            context.getBean(TaskService.class).submit("mdc", "metered");
            final MeterRegistry registry = context.getBean(MeterRegistry.class);
            await().atMost(WAIT)
                    .until(() -> registry.find("tc.task.attempts")
                                    .tags("type", "mdc", "outcome", "succeeded", "retry", "false")
                                    .counter()
                            != null);
            assertThat(registry.get("tc.task.attempt.duration")
                            .tags("type", "mdc", "level", "task", "phase", "process", "outcome", "succeeded")
                            .timer()
                            .count())
                    .isEqualTo(1);
        });
    }

    @Test
    void schemaWideSnapshotIsCountedAndPublishedPerSubsystem() {
        this.contextRunner.withUserConfiguration(Probe.class, Registry.class).run(context -> {
            final MeterRegistry registry = context.getBean(MeterRegistry.class);
            await().atMost(WAIT)
                    .until(() -> registry.find("tc.task.unfinished")
                                    .tags("type", "mdc")
                                    .gauge()
                            != null);
            assertThat(registry.get("tc.task.queue")
                            .tags("type", "mdc", "poller", "main")
                            .gauge()
                            .value())
                    .isZero();
            assertThat(registry.get("tc.metrics.snapshot.age")
                            .tags("subsystem", "task")
                            .gauge()
                            .value())
                    .isNotNaN();
            assertThat(registry.get("tc.metrics.snapshot.age")
                            .tags("subsystem", "communication")
                            .gauge())
                    .isNotNull();
        });
    }

    @Test
    void nonPositiveSnapshotIntervalFailsStartup() {
        this.contextRunner
                .withUserConfiguration(Probe.class)
                .withPropertyValues("taskcomm.metrics.interval=0s")
                .run(context -> assertThat(context)
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(TaskcommConfigurationException.class)
                        .hasMessageContaining("taskcomm.metrics.interval"));
    }

    @Test
    void healthIndicatorReportsLivenessOfBothEngines() {
        this.contextRunner.withUserConfiguration(Probe.class).run(context -> {
            final Health health = context.getBean("taskcommHealthIndicator", HealthIndicator.class)
                    .health();
            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(health.getDetails())
                    .containsKeys(
                            "task.poller.main",
                            "task.heartbeat",
                            "communication.poller.main",
                            "communication.heartbeat");
        });
    }

    @Test
    void healthIndicatorFollowsActuatorSwitch() {
        this.contextRunner
                .withUserConfiguration(Probe.class)
                .withPropertyValues("management.health.taskcomm.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(HealthIndicator.class);
                });
    }

    @Test
    void silentWithoutMicrometerAndActuator() {
        this.contextRunner
                .withUserConfiguration(Probe.class)
                .withClassLoader(new FilteredClassLoader(MeterRegistry.class, HealthIndicator.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean("taskcommHealthIndicator");
                    assertThat(context).doesNotHaveBean("taskcommTaskMetrics");
                    context.getBean(TaskService.class).submit("mdc", "plain");
                    await().atMost(WAIT)
                            .until(() -> context.getBean(MdcProbe.class).seen.containsKey("plain"));
                });
    }

    @Test
    void rootSwitchDisablesObservability() {
        this.contextRunner
                .withUserConfiguration(Probe.class)
                .withPropertyValues("taskcomm.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(HealthIndicator.class);
                    assertThat(context).doesNotHaveBean(TaskcommProcessorCheck.class);
                });
    }

    @Test
    void tooFewMissedIntervalsFailStartup() {
        this.contextRunner
                .withUserConfiguration(Probe.class)
                .withPropertyValues("taskcomm.health.missed-intervals=1")
                .run(context -> assertThat(context)
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(TaskcommConfigurationException.class)
                        .hasMessageContaining("missed-intervals"));
    }

    @Test
    void warnsWhenNoProcessorIsRegistered(final CapturedOutput output) {
        this.contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(TaskcommProcessorCheck.class).processors())
                    .isZero();
            assertThat(output).contains(NO_PROCESSORS);
        });
    }

    @Test
    void doesNotWarnWhenProcessorsExist(final CapturedOutput output) {
        this.contextRunner.withUserConfiguration(Probe.class).run(context -> {
            assertThat(context.getBean(TaskcommProcessorCheck.class).processors())
                    .isEqualTo(1);
            assertThat(output).doesNotContain(NO_PROCESSORS);
        });
    }

    /** Процессор, запоминающий MDC в момент вызова и кладущий свой ключ. */
    static class MdcProbe implements FlatTaskProcessor<String, Void> {

        final Map<String, Map<String, String>> seen = new ConcurrentHashMap<>();

        @Override
        public String taskType() {
            return "mdc";
        }

        @Override
        public Void process(final Task<String> task, final ProcessingContext context) {
            final Map<String, String> current = MDC.getCopyOfContextMap();
            this.seen.put(task.payload(), current == null ? Map.of() : new HashMap<>(current));
            MDC.put("businessId", "B-" + task.payload());
            return null;
        }
    }

    /** Процессор теста. */
    @Configuration(proxyBeanMethods = false)
    static class Probe {

        @Bean
        MdcProbe mdcProbe() {
            return new MdcProbe();
        }
    }

    /** Реестр Micrometer потребителя. */
    @Configuration(proxyBeanMethods = false)
    static class Registry {

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
