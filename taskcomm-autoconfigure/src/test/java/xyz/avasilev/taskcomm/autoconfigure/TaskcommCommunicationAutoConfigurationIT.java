package xyz.avasilev.taskcomm.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.TaskScheduler;
import tools.jackson.databind.json.JsonMapper;
import xyz.avasilev.taskcomm.TaskcommConfigurationException;
import xyz.avasilev.taskcomm.TestPostgres;
import xyz.avasilev.taskcomm.communication.api.Communication;
import xyz.avasilev.taskcomm.communication.api.CommunicationService;
import xyz.avasilev.taskcomm.communication.api.inspection.CommunicationInspection;
import xyz.avasilev.taskcomm.communication.api.processor.CommunicationContext;
import xyz.avasilev.taskcomm.communication.api.processor.CommunicationProcessorSettings;
import xyz.avasilev.taskcomm.communication.api.processor.KafkaCommunicationProcessor;
import xyz.avasilev.taskcomm.communication.internal.engine.CommunicationEngine;
import xyz.avasilev.taskcomm.communication.internal.processor.CommunicationProcessorRegistry;
import xyz.avasilev.taskcomm.communication.internal.processor.CommunicationStartup;
import xyz.avasilev.taskcomm.execution.api.ExecutionService;
import xyz.avasilev.taskcomm.task.api.processor.TaskProcessorSettings;

/**
 * Обязательные сценарии автоконфигурации коммуникаций (CLAUDE.md, раздел «Тесты») на настоящем
 * PostgreSQL: бины по умолчанию, выключение своим {@code taskcomm.communication.enabled} и корневым
 * {@code taskcomm.enabled} (docs/adr/0090), по тесту на каждую точку замены — помеченный бин берётся,
 * непомеченный того же типа нет, — отсутствие бинов чужих типов (docs/adr/0096), печать итоговых
 * настроек и падение на аннотации настроек чужой подсистемы (docs/adr/0066).
 */
@ExtendWith(OutputCaptureExtension.class)
class TaskcommCommunicationAutoConfigurationIT {

    private static final Duration WAIT = Duration.ofSeconds(20);

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    TaskcommAutoConfiguration.class,
                    TaskcommSchemaAutoConfiguration.class,
                    TaskcommCommunicationAutoConfiguration.class))
            .withUserConfiguration(TaskcommTaskAutoConfigurationIT.Database.class, PingConfiguration.class)
            .withBean(String.class, TestPostgres::newSchema)
            .withPropertyValues("taskcomm.schema.initialize=always", "taskcomm.communication.poll.interval=100ms");

    @Test
    void createsEngineAndServiceByDefault() {
        this.contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(CommunicationService.class);
            assertThat(context).hasSingleBean(CommunicationInspection.class);
            assertThat(context).hasSingleBean(CommunicationProcessorRegistry.class);
            assertThat(context).hasSingleBean(CommunicationStartup.class);
            assertThat(context.getBean(CommunicationEngine.class).isRunning()).isTrue();
        });
    }

    @Test
    void backsOffWhenDisabledByOwnSwitch() {
        this.contextRunner
                .withPropertyValues("taskcomm.communication.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(CommunicationService.class);
                    assertThat(context).doesNotHaveBean(CommunicationInspection.class);
                    assertThat(context).doesNotHaveBean(CommunicationEngine.class);
                    assertThat(context).hasSingleBean(ExecutionService.class);
                });
    }

    @Test
    void backsOffWhenDisabledByRootSwitch() {
        this.contextRunner.withPropertyValues("taskcomm.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(CommunicationService.class);
            assertThat(context).doesNotHaveBean(CommunicationInspection.class);
            assertThat(context).doesNotHaveBean(CommunicationEngine.class);
            assertThat(context).doesNotHaveBean(ExecutionService.class);
        });
    }

    @Test
    void registersNoBeansOfForeignTypesItConsumes() {
        this.contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(TaskScheduler.class);
            assertThat(context).doesNotHaveBean(Executor.class);
            assertThat(context).doesNotHaveBean(ThreadFactory.class);
            assertThat(context).doesNotHaveBean(TaskDecorator.class);
            assertThat(context).doesNotHaveBean(JsonMapper.class);
            assertThat(context).doesNotHaveBean(JdbcClient.class);
        });
    }

    @Test
    void printsResolvedSettingsWithLevels(final CapturedOutput output) {
        this.contextRunner
                .withPropertyValues("taskcomm.communication.systems.ping.retry.jitter=0.5")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(output)
                            .contains("Итоговые настройки коммуникаций")
                            .contains("система 'ping' (KAFKA), приоритет 100, отказные статусы []")
                            .contains("retry.max-attempts 2 (аннотация на классе)")
                            .contains("retry.jitter 0.5 (yaml, система)")
                            .contains("lease.max-execution-time PT5M (глобальное значение)");
                });
    }

    @Test
    void failsOnTaskSettingsAnnotationOnCommunicationProcessor() {
        this.contextRunner
                .withUserConfiguration(ForeignAnnotationConfiguration.class)
                .run(context -> assertThat(context)
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(TaskcommConfigurationException.class)
                        .hasMessageContaining("@TaskProcessorSettings на процессоре коммуникаций"));
    }

    @Test
    void failsOnUnknownPhaseInYaml() {
        this.contextRunner
                .withPropertyValues("taskcomm.communication.systems.ping.phases.expand.retry.max-attempts=2")
                .run(context -> assertThat(context)
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(TaskcommConfigurationException.class)
                        .hasMessageContaining("фазы 'expand' нет"));
    }

    @Test
    void usesPayloadMapperMarkedWithQualifier() {
        this.contextRunner
                .withUserConfiguration(TaskcommTaskAutoConfigurationIT.QualifiedMapper.class)
                .run(context -> {
                    final UUID id = context.getBean(CommunicationService.class)
                            .send("ping", new Ping("abc"))
                            .communication()
                            .id();
                    assertThat(payload(context.getBean(DataSource.class), id)).contains("ping_text");
                });
    }

    @Test
    void ignoresPayloadMapperWithoutQualifier() {
        this.contextRunner
                .withUserConfiguration(TaskcommTaskAutoConfigurationIT.UnqualifiedMapper.class)
                .run(context -> {
                    final UUID id = context.getBean(CommunicationService.class)
                            .send("ping", new Ping("abc"))
                            .communication()
                            .id();
                    assertThat(payload(context.getBean(DataSource.class), id)).contains("pingText");
                });
    }

    @Test
    void usesWorkerThreadFactoryMarkedWithQualifier() {
        this.contextRunner
                .withUserConfiguration(TaskcommTaskAutoConfigurationIT.QualifiedThreadFactory.class)
                .run(context -> assertThat(runPing(
                                context.getBean(CommunicationService.class), context.getBean(PingProcessor.class)))
                        .singleElement()
                        .asString()
                        .startsWith("consumer-worker-"));
    }

    @Test
    void ignoresWorkerThreadFactoryWithoutQualifier() {
        this.contextRunner
                .withUserConfiguration(TaskcommTaskAutoConfigurationIT.UnqualifiedThreadFactory.class)
                .run(context -> assertThat(runPing(
                                context.getBean(CommunicationService.class), context.getBean(PingProcessor.class)))
                        .singleElement()
                        .asString()
                        .startsWith("taskcomm-communication-main-"));
    }

    @Test
    void usesWorkerTaskDecoratorMarkedWithQualifier() {
        this.contextRunner
                .withUserConfiguration(TaskcommTaskAutoConfigurationIT.QualifiedDecorator.class)
                .run(context -> {
                    runPing(context.getBean(CommunicationService.class), context.getBean(PingProcessor.class));
                    assertThat(context.getBean(TaskcommTaskAutoConfigurationIT.CountingDecorator.class)
                                    .decorated
                                    .get())
                            .isPositive();
                });
    }

    @Test
    void ignoresWorkerTaskDecoratorWithoutQualifier() {
        this.contextRunner
                .withUserConfiguration(TaskcommTaskAutoConfigurationIT.UnqualifiedDecorator.class)
                .run(context -> {
                    runPing(context.getBean(CommunicationService.class), context.getBean(PingProcessor.class));
                    assertThat(context.getBean(TaskcommTaskAutoConfigurationIT.CountingDecorator.class)
                                    .decorated
                                    .get())
                            .isZero();
                });
    }

    @Test
    void runsPollersAndHeartbeatOnSchedulerMarkedWithQualifier() {
        this.contextRunner
                .withUserConfiguration(TaskcommTaskAutoConfigurationIT.QualifiedScheduler.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    // такт партиций, такт основного поллера коммуникаций, heartbeat и подстраховка
                    assertThat(context.getBean(TaskcommTaskAutoConfigurationIT.CountingScheduler.class)
                                    .scheduled
                                    .get())
                            .isEqualTo(4);
                });
    }

    @Test
    void ignoresSchedulerWithoutQualifier() {
        this.contextRunner
                .withUserConfiguration(TaskcommTaskAutoConfigurationIT.UnqualifiedScheduler.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(TaskcommTaskAutoConfigurationIT.CountingScheduler.class)
                                    .scheduled
                                    .get())
                            .isZero();
                });
    }

    private static List<String> runPing(final CommunicationService communications, final PingProcessor ping) {
        communications.send("ping", new Ping("run"));
        await().atMost(WAIT).until(() -> !ping.threads.isEmpty());
        return List.copyOf(ping.threads);
    }

    private static String payload(final DataSource dataSource, final UUID id) {
        return JdbcClient.create(dataSource)
                .sql("SELECT request_payload::text FROM tc_communication WHERE id = :id")
                .param("id", id)
                .query(String.class)
                .single();
    }

    /**
     * Нагрузка с именем поля из двух слов — по нему видно, чей маппер её записал.
     *
     * @param pingText текст
     */
    record Ping(String pingText) {}

    /** Запоминает поток, в котором выполнялся. */
    @CommunicationProcessorSettings(maxAttempts = 2)
    static class PingProcessor implements KafkaCommunicationProcessor<Ping, Void, Void> {

        final List<String> threads = new CopyOnWriteArrayList<>();

        @Override
        public String externalSystem() {
            return "ping";
        }

        @Override
        public Void process(final Communication<Ping> communication, final CommunicationContext context) {
            this.threads.add(Thread.currentThread().getName());
            return null;
        }
    }

    /** Процессор с аннотацией настроек задач. */
    @TaskProcessorSettings(maxAttempts = 2)
    static class ForeignAnnotatedProcessor implements KafkaCommunicationProcessor<Ping, Void, Void> {

        @Override
        public String externalSystem() {
            return "foreign";
        }

        @Override
        public Void process(final Communication<Ping> communication, final CommunicationContext context) {
            return null;
        }
    }

    /** Процессор теста. */
    @Configuration(proxyBeanMethods = false)
    static class PingConfiguration {

        @Bean
        PingProcessor pingProcessor() {
            return new PingProcessor();
        }
    }

    /** Процессор с чужой аннотацией. */
    @Configuration(proxyBeanMethods = false)
    static class ForeignAnnotationConfiguration {

        @Bean
        ForeignAnnotatedProcessor foreignAnnotatedProcessor() {
            return new ForeignAnnotatedProcessor();
        }
    }
}
