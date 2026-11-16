package xyz.avasilev.taskcomm.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.avasilev.taskcomm.TaskcommException;
import xyz.avasilev.taskcomm.TestPostgres;
import xyz.avasilev.taskcomm.autoconfigure.CommunicationLifecycleIT.Ack;
import xyz.avasilev.taskcomm.autoconfigure.CommunicationLifecycleIT.Order;
import xyz.avasilev.taskcomm.autoconfigure.CommunicationLifecycleIT.OrderProcessor;
import xyz.avasilev.taskcomm.communication.api.CallbackRequest;
import xyz.avasilev.taskcomm.communication.api.Communication;
import xyz.avasilev.taskcomm.communication.api.CommunicationRestartResult;
import xyz.avasilev.taskcomm.communication.api.CommunicationSendOptions;
import xyz.avasilev.taskcomm.communication.api.CommunicationService;
import xyz.avasilev.taskcomm.communication.api.CommunicationStatus;
import xyz.avasilev.taskcomm.communication.api.ExchangeMessage;
import xyz.avasilev.taskcomm.communication.api.InboundRequest;
import xyz.avasilev.taskcomm.communication.api.ReceiveAndProcessResult;
import xyz.avasilev.taskcomm.communication.api.SendAndProcessResult;
import xyz.avasilev.taskcomm.communication.api.SyncSendOptions;
import xyz.avasilev.taskcomm.communication.api.processor.CommunicationContext;
import xyz.avasilev.taskcomm.communication.api.processor.RestCommunicationProcessor;

/**
 * Синхронный запуск через публичный API и настоящий движок (docs/adr/0167-communication-run-mode.md):
 * отправка с ответом, повтор в потоке, исчерпание, ожидание колбэка, недоступность по ограничителю
 * скорости, отказ внутри транзакции, перезапуск по режиму, переподхват и брошенная пауза — без фонового
 * повтора.
 */
class SyncRunIT {

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
                    "taskcomm.communication.safety.interval=200ms",
                    "taskcomm.communication.lease.duration=2s",
                    "taskcomm.communication.lease.heartbeat-interval=500ms",
                    "taskcomm.communication.retry.max-attempts=3",
                    "taskcomm.communication.retry.initial-delay=0s",
                    "taskcomm.communication.retry.multiplier=1",
                    "taskcomm.communication.retry.max-delay=0s");

    @Test
    void sendReturnsSyncResponseAndMarksRunMode() {
        this.contextRunner.run(context -> {
            final SendAndProcessResult<Order, ?, ?> result =
                    context.getBean(CommunicationService.class).sendAndProcess("orders", new Order("S-1"));

            assertThat(result)
                    .isInstanceOfSatisfying(
                            SendAndProcessResult.Processed.class,
                            processed -> assertThat(processed.response()).isEqualTo(new Ack("отправлен S-1")));
            final UUID id = id(result);
            final DataSource dataSource = context.getBean(DataSource.class);
            assertThat(column(dataSource, id, "status")).isEqualTo("SUCCEEDED");
            assertThat(column(dataSource, id, "run_mode")).isEqualTo("SYNC");
            assertThat(column(dataSource, id, "contract_shape")).isEqualTo("REST");
        });
    }

    @Test
    void sendRetriesInTheCallerThreadAndGivesUpWhenExhausted() {
        this.contextRunner.run(context -> {
            final CommunicationService communications = context.getBean(CommunicationService.class);
            final DataSource dataSource = context.getBean(DataSource.class);

            final SendAndProcessResult<Order, ?, ?> retried =
                    communications.sendAndProcess("orders", new Order("retry"));
            assertThat(retried).isInstanceOf(SendAndProcessResult.Processed.class);
            assertThat(attemptStatuses(dataSource, id(retried))).containsExactly("FAILED", "SUCCEEDED");

            final SendAndProcessResult<Order, ?, ?> failed = communications.sendAndProcess("orders", new Order("fail"));
            assertThat(failed)
                    .isInstanceOfSatisfying(
                            SendAndProcessResult.Failed.class,
                            result -> assertThat(result.reason()).isNotBlank());
            assertThat(attemptStatuses(dataSource, id(failed))).containsExactly("FAILED", "FAILED", "FAILED");
            assertThat(column(dataSource, id(failed), "status")).isEqualTo("FAILED");
        });
    }

    @Test
    void awaitsCallbackWhenAskedAndReportsWhenItDoesNotCome() {
        this.contextRunner.run(context -> {
            final CommunicationService communications = context.getBean(CommunicationService.class);

            final SendAndProcessResult<Order, ?, ?> responded = communications.sendAndProcess(
                    "bank", new Order("B-1"), SyncSendOptions.defaults().awaitResponse(WAIT));
            assertThat(responded)
                    .isInstanceOfSatisfying(
                            SendAndProcessResult.Responded.class,
                            result -> assertThat(result.asyncResponse()).isEqualTo(new Settled("settled")));

            final SendAndProcessResult<Order, ?, ?> silent = communications.sendAndProcess(
                    "bank", new Order("silent"), SyncSendOptions.defaults().awaitResponse(Duration.ofMillis(500)));
            assertThat(silent).isInstanceOf(SendAndProcessResult.Awaiting.class);

            final SendAndProcessResult<Order, ?, ?> notAwaited =
                    communications.sendAndProcess("bank", new Order("silent-2"));
            assertThat(notAwaited).isInstanceOf(SendAndProcessResult.Processed.class);
            assertThat(column(context.getBean(DataSource.class), id(notAwaited), "status"))
                    .isEqualTo("AWAITING_RESPONSE");
        });
    }

    @Test
    void rateLimitBeyondSyncWaitIsUnavailableWithoutWriting() {
        this.contextRunner
                .withPropertyValues("taskcomm.communication.systems.orders.rate-limit-per-instance=0.01")
                .run(context -> {
                    final CommunicationService communications = context.getBean(CommunicationService.class);
                    final SyncSendOptions noWait = SyncSendOptions.defaults().syncWait(Duration.ZERO);

                    assertThat(communications.sendAndProcess("orders", new Order("R-1"), noWait))
                            .isInstanceOf(SendAndProcessResult.Processed.class);
                    assertThat(communications.sendAndProcess("orders", new Order("R-2"), noWait))
                            .isInstanceOfSatisfying(
                                    SendAndProcessResult.Unavailable.class,
                                    unavailable ->
                                            assertThat(unavailable.reason()).contains("ограничителя скорости"));
                    assertThat(count(context.getBean(DataSource.class))).isEqualTo(1);
                });
    }

    @Test
    void sendIsRejectedInsideTransactionAndWithNotBefore() {
        this.contextRunner.run(context -> {
            final CommunicationService communications = context.getBean(CommunicationService.class);
            final TransactionTemplate transaction =
                    new TransactionTemplate(context.getBean(PlatformTransactionManager.class));

            assertThatThrownBy(() -> transaction.executeWithoutResult(
                            status -> communications.sendAndProcess("orders", new Order("T-1"))))
                    .isInstanceOf(TaskcommException.class)
                    .hasMessageContaining("внутри активной транзакции");
            assertThatThrownBy(() -> communications.sendAndProcess(
                            "orders",
                            new Order("T-2"),
                            SyncSendOptions.defaults()
                                    .send(CommunicationSendOptions.defaults().notBefore(Instant.now()))))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(count(context.getBean(DataSource.class))).isZero();
        });
    }

    @Test
    void syncOutboundIsNotRestartedButSyncInboundGoesToTheEngine() {
        this.contextRunner.run(context -> {
            final CommunicationService communications = context.getBean(CommunicationService.class);
            final DataSource dataSource = context.getBean(DataSource.class);

            final SendAndProcessResult.Failed<?, ?, ?> sent =
                    (SendAndProcessResult.Failed<?, ?, ?>) communications.sendAndProcess("orders", new Order("fail"));
            assertThat(communications.restart(sent.communication()))
                    .isInstanceOfSatisfying(
                            CommunicationRestartResult.Rejected.class,
                            rejected -> assertThat(rejected.reason()).contains("синхронная отправка"));

            final ReceiveAndProcessResult<?, ?> received =
                    communications.receiveAndProcess("orders", InboundRequest.of("{\"number\":\"fail\"}"));
            assertThat(received.communication().status()).isEqualTo(CommunicationStatus.FAILED);
            assertThat(communications.restart(received.communication()))
                    .isInstanceOf(CommunicationRestartResult.Accepted.class);
            assertThat(column(dataSource, received.communication().id(), "run_mode"))
                    .isEqualTo("ASYNC");
        });
    }

    @Test
    void abandonedSyncIsFailedNotRetriedInBackground() {
        this.contextRunner.run(context -> {
            final CommunicationService communications = context.getBean(CommunicationService.class);
            final DataSource dataSource = context.getBean(DataSource.class);
            final JdbcClient jdbc = JdbcClient.create(dataSource);

            final UUID paused = abandonInPause(jdbc, id(communications.sendAndProcess("orders", new Order("P-1"))));
            final UUID running = abandonMidAttempt(jdbc, id(communications.sendAndProcess("orders", new Order("P-2"))));

            await().atMost(WAIT).untilAsserted(() -> {
                assertThat(column(dataSource, paused, "status")).isEqualTo("FAILED");
                assertThat(column(dataSource, running, "status")).isEqualTo("FAILED");
            });
            assertThat(column(dataSource, paused, "status_description")).contains("не дождался следующей попытки");
            assertThat(column(dataSource, running, "status_description"))
                    .startsWith("синхронная: вызывающий не дождался");
            // Захват брошенную паузу не брал: её попытка так и не начата.
            assertThat(JdbcClient.create(dataSource)
                            .sql("SELECT count(*) FROM tc_communication_attempt WHERE communication_id = :id"
                                    + " AND status = 'SKIPPED' AND started_at IS NULL")
                            .param("id", paused)
                            .query(Integer.class)
                            .single())
                    .isEqualTo(1);
        });
    }

    /** Вызывающий умер в паузе между попытками: следующая открыта, срок прошёл давно. */
    private static UUID abandonInPause(final JdbcClient jdbc, final UUID communication) {
        jdbc.sql("UPDATE tc_communication SET status = 'PENDING', next_attempt_at = now() - interval '1 hour'"
                        + " WHERE id = :id")
                .param("id", communication)
                .update();
        openAttempt(jdbc, communication, null);
        return communication;
    }

    /** Вызывающий умер посреди попытки: аренда истекла. */
    private static UUID abandonMidAttempt(final JdbcClient jdbc, final UUID communication) {
        jdbc.sql("UPDATE tc_communication SET status = 'RUNNING' WHERE id = :id")
                .param("id", communication)
                .update();
        openAttempt(jdbc, communication, "dead-instance");
        return communication;
    }

    private static UUID id(final SendAndProcessResult<?, ?, ?> result) {
        return switch (result) {
            case SendAndProcessResult.Processed<?, ?, ?> r -> r.communication().id();
            case SendAndProcessResult.Responded<?, ?, ?> r -> r.communication().id();
            case SendAndProcessResult.Awaiting<?, ?, ?> r -> r.communication().id();
            case SendAndProcessResult.Failed<?, ?, ?> r -> r.communication().id();
            case SendAndProcessResult.Duplicate<?, ?, ?> r -> r.communication().id();
            case SendAndProcessResult.Unavailable<?, ?, ?> r -> throw new IllegalStateException(r.reason());
        };
    }

    /** Открытая попытка отправки: без аренды — пауза, с истёкшей арендой — брошенная. */
    private static void openAttempt(final JdbcClient jdbc, final UUID communication, final String lockedBy) {
        jdbc.sql("""
                        INSERT INTO tc_communication_attempt (
                            id, created_at, updated_at, execution_created_at, communication_id, phase, lease_renewals,
                            locked_by, locked_until, lease_token, started_at)
                        SELECT gen_random_uuid(), now(), now(), c.execution_created_at, c.id, 'PROCESS', 0,
                               CAST(:lockedBy AS text),
                               CASE WHEN CAST(:lockedBy AS text) IS NULL THEN NULL ELSE now() - interval '1 minute' END,
                               CASE WHEN CAST(:lockedBy AS text) IS NULL THEN NULL ELSE gen_random_uuid() END,
                               CASE WHEN CAST(:lockedBy AS text) IS NULL THEN NULL ELSE now() END
                          FROM tc_communication c WHERE c.id = :id
                        """).param("lockedBy", lockedBy).param("id", communication).update();
    }

    private static String column(final DataSource dataSource, final UUID id, final String column) {
        return JdbcClient.create(dataSource)
                .sql("SELECT " + column + " FROM tc_communication WHERE id = :id")
                .param("id", id)
                .query(String.class)
                .single();
    }

    private static List<String> attemptStatuses(final DataSource dataSource, final UUID id) {
        return JdbcClient.create(dataSource)
                .sql("SELECT status FROM tc_communication_attempt WHERE communication_id = :id"
                        + " AND started_at IS NOT NULL ORDER BY started_at")
                .param("id", id)
                .query(String.class)
                .list();
    }

    private static int count(final DataSource dataSource) {
        return JdbcClient.create(dataSource)
                .sql("SELECT count(*) FROM tc_communication")
                .query(Integer.class)
                .single();
    }

    /**
     * Подтверждение банка — колбэк.
     *
     * @param status статус
     */
    record Settled(String status) {}

    /**
     * Банк: принимает платёж и через 300 мс присылает колбэк по ключу {@code ref-<номер>}; номера, начинающиеся
     * с {@code silent}, колбэка не получают.
     */
    static class BankProcessor implements RestCommunicationProcessor<Order, Ack, Settled>, AutoCloseable {

        private final CommunicationService communications;

        private final ScheduledExecutorService callbacks = Executors.newSingleThreadScheduledExecutor();

        BankProcessor(final CommunicationService communications) {
            this.communications = communications;
        }

        @Override
        public String externalSystem() {
            return "bank";
        }

        @Override
        public Ack process(final Communication<Order> communication, final CommunicationContext context) {
            final String number = communication.requestPayload().number();
            context.recordRequest(ExchangeMessage.of(number));
            context.recordResponse(ExchangeMessage.of("{\"accepted\":true}").withStatus(202));
            context.registerCorrelationId("ref-" + number);
            if (!number.startsWith("silent")) {
                var _ = this.callbacks.schedule(
                        () -> this.communications.receiveResponse(
                                "bank", CallbackRequest.of("ref-" + number, "{\"status\":\"settled\"}")),
                        300,
                        TimeUnit.MILLISECONDS);
            }
            return new Ack("принят " + number);
        }

        @Override
        public void close() {
            this.callbacks.shutdownNow();
        }
    }

    /** Процессоры теста. */
    @Configuration(proxyBeanMethods = false)
    static class Processors {

        @Bean
        OrderProcessor orderProcessor() {
            return new OrderProcessor();
        }

        @Bean(destroyMethod = "close")
        BankProcessor bankProcessor(@Lazy final CommunicationService communications) {
            return new BankProcessor(communications);
        }
    }
}
