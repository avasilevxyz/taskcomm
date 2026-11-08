package xyz.avasilev.taskcomm.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import xyz.avasilev.taskcomm.TestPostgres;
import xyz.avasilev.taskcomm.communication.api.Communication;
import xyz.avasilev.taskcomm.communication.api.CommunicationService;
import xyz.avasilev.taskcomm.communication.api.processor.CommunicationContext;
import xyz.avasilev.taskcomm.communication.api.processor.CommunicationProcessorSettings;
import xyz.avasilev.taskcomm.communication.api.processor.KafkaCommunicationProcessor;

/**
 * Ограничение скорости и размыкатель на настоящем движке (docs/adr/0137-rate-limit-and-circuit-breaker.md):
 * отправки в систему с лимитом идут не чаще лимита; после доли отказов размыкатель перестаёт захватывать
 * систему, и её коммуникации ждут в очереди.
 */
class CommunicationGuardsIT {

    private static final Duration WAIT = Duration.ofSeconds(20);

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    TaskcommAutoConfiguration.class,
                    TaskcommSchemaAutoConfiguration.class,
                    TaskcommCommunicationAutoConfiguration.class))
            .withUserConfiguration(TaskcommTaskAutoConfigurationIT.Database.class, Processors.class)
            .withBean(String.class, TestPostgres::newSchema)
            .withPropertyValues(
                    "taskcomm.schema.initialize=always",
                    "taskcomm.communication.poll.interval=100ms",
                    "taskcomm.communication.lease.duration=6s",
                    "taskcomm.communication.lease.heartbeat-interval=1s",
                    "taskcomm.communication.retry.max-attempts=1",
                    "taskcomm.communication.systems.broken.circuit-breaker.enabled=true",
                    "taskcomm.communication.circuit-breaker.minimum-calls=2",
                    "taskcomm.communication.circuit-breaker.wait.initial-delay=1h",
                    "taskcomm.communication.circuit-breaker.wait.max-delay=2h");

    @Test
    void sendsToLimitedSystemNoFasterThanLimit() {
        this.contextRunner.run(context -> {
            final List<UUID> ids = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                ids.add(context.getBean(CommunicationService.class)
                        .send("slow", "x" + i)
                        .communication()
                        .id());
            }
            final JdbcClient jdbc = JdbcClient.create(context.getBean(DataSource.class));
            await().atMost(WAIT).until(() -> count(jdbc, "slow", "SUCCEEDED") == ids.size());

            // Лимит действует между захватом и отправкой, поэтому меряется по вызовам процессора,
            // а не по started_at попыток: один такт захватывает пачку с одним now() транзакции.
            assertThat(context.getBean(SlowProcessor.class).span()).isGreaterThanOrEqualTo(Duration.ofMillis(800));
        });
    }

    @Test
    void openBreakerStopsClaimingSystem() {
        this.contextRunner.run(context -> {
            final CommunicationService communications = context.getBean(CommunicationService.class);
            final JdbcClient jdbc = JdbcClient.create(context.getBean(DataSource.class));
            communications.send("broken", "a");
            communications.send("broken", "b");
            await().atMost(WAIT).until(() -> count(jdbc, "broken", "FAILED") == 2);

            final UUID waiting =
                    communications.send("broken", "c").communication().id();
            Thread.sleep(1500);

            assertThat(jdbc.sql("SELECT status FROM tc_communication WHERE id = :id")
                            .param("id", waiting)
                            .query(String.class)
                            .single())
                    .isEqualTo("PENDING");
        });
    }

    private static int count(final JdbcClient jdbc, final String destination, final String status) {
        return jdbc.sql("SELECT count(*) FROM tc_communication WHERE destination = :destination AND status = :status")
                .param("destination", destination)
                .param("status", status)
                .query(Integer.class)
                .single();
    }

    /** Система с лимитом пять отправок в секунду: запоминает моменты вызовов. */
    @CommunicationProcessorSettings(rateLimitPerInstance = 5)
    static class SlowProcessor implements KafkaCommunicationProcessor<String, Void, Void> {

        private final List<Long> calls = new CopyOnWriteArrayList<>();

        @Override
        public String externalSystem() {
            return "slow";
        }

        @Override
        public Void process(final Communication<String> communication, final CommunicationContext context) {
            this.calls.add(System.nanoTime());
            return null;
        }

        Duration span() {
            return Duration.ofNanos(this.calls.getLast() - this.calls.getFirst());
        }
    }

    /** Система, которая всегда отказывает. */
    static class BrokenProcessor implements KafkaCommunicationProcessor<String, Void, Void> {

        @Override
        public String externalSystem() {
            return "broken";
        }

        @Override
        public Void process(final Communication<String> communication, final CommunicationContext context) {
            throw new IllegalStateException("система лежит");
        }
    }

    /** Процессоры теста. */
    @Configuration(proxyBeanMethods = false)
    static class Processors {

        @Bean
        SlowProcessor slowProcessor() {
            return new SlowProcessor();
        }

        @Bean
        BrokenProcessor brokenProcessor() {
            return new BrokenProcessor();
        }
    }
}
