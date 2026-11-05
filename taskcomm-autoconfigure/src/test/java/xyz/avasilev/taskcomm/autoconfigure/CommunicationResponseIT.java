package xyz.avasilev.taskcomm.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.avasilev.taskcomm.TaskcommException;
import xyz.avasilev.taskcomm.TestPostgres;
import xyz.avasilev.taskcomm.communication.api.CallbackRequest;
import xyz.avasilev.taskcomm.communication.api.Communication;
import xyz.avasilev.taskcomm.communication.api.CommunicationContext;
import xyz.avasilev.taskcomm.communication.api.CommunicationSendOptions;
import xyz.avasilev.taskcomm.communication.api.CommunicationService;
import xyz.avasilev.taskcomm.communication.api.ExchangeMessage;
import xyz.avasilev.taskcomm.communication.api.InboundRequest;
import xyz.avasilev.taskcomm.communication.api.ReceiveResponseResult;
import xyz.avasilev.taskcomm.communication.api.ReceiveResult;
import xyz.avasilev.taskcomm.communication.api.RestCommunicationProcessor;
import xyz.avasilev.taskcomm.communication.api.SendResult;
import xyz.avasilev.taskcomm.execution.api.OriginRelation;

/**
 * Дедупликация, корреляция и асинхронный ответ через публичный API и настоящий движок
 * (docs/adr/0136-communication-dedup-and-callbacks.md): дубликат при создании и из процессора,
 * ключ по последней успешной, ожидание ответа по ключу корреляции, колбэк и {@code handleResponse},
 * колбэк без ожидающей и с неразбираемым телом, повторная отправка по сроку и отказ ключу в
 * транзакции строже {@code READ COMMITTED}.
 */
class CommunicationResponseIT {

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
                    "taskcomm.communication.lease.duration=6s",
                    "taskcomm.communication.lease.heartbeat-interval=1s",
                    "taskcomm.communication.retry.max-attempts=2",
                    "taskcomm.communication.retry.initial-delay=0s",
                    "taskcomm.communication.retry.multiplier=1",
                    "taskcomm.communication.retry.max-delay=0s");

    @Test
    void secondSendWithSameKeyIsSkippedDuplicate() {
        this.contextRunner.run(context -> {
            final CommunicationService communications = context.getBean(CommunicationService.class);
            final SendResult<Payment> first = communications.send(
                    "bank",
                    new Payment("p-1"),
                    CommunicationSendOptions.defaults().dedupKey("p-1"));
            final SendResult<Payment> second = communications.send(
                    "bank",
                    new Payment("p-1"),
                    CommunicationSendOptions.defaults().dedupKey("p-1"));

            assertThat(first).isInstanceOf(SendResult.Created.class);
            assertThat(second)
                    .isInstanceOfSatisfying(
                            SendResult.Duplicate.class,
                            duplicate -> assertThat(duplicate.duplicateOf())
                                    .isEqualTo(first.communication().id()));
            assertThat(column(
                            context.getBean(DataSource.class),
                            second.communication().id(),
                            "status_description"))
                    .isEqualTo("дубликат коммуникации " + first.communication().id() + " по ключу p-1");
            awaitStatus(context.getBean(DataSource.class), first.communication().id(), "SUCCEEDED");
        });
    }

    @Test
    void lastSucceededKeyLetsChangedValueThrough() {
        this.contextRunner.run(context -> {
            final CommunicationService communications = context.getBean(CommunicationService.class);
            final ReceiveResult<?> first = communications.receive(
                    "bank", InboundRequest.of("{\"id\":\"sku\"}").withDedupKey("sku", "price-1"));
            awaitStatus(context.getBean(DataSource.class), first.communication().id(), "SUCCEEDED");

            assertThat(communications.receive(
                            "bank", InboundRequest.of("{\"id\":\"sku\"}").withDedupKey("sku", "price-1")))
                    .isInstanceOf(ReceiveResult.Duplicate.class);
            assertThat(communications.receive(
                            "bank", InboundRequest.of("{\"id\":\"sku\"}").withDedupKey("sku", "price-2")))
                    .isInstanceOf(ReceiveResult.Accepted.class);
        });
    }

    @Test
    void keyFromProcessStopsDuplicateAsSkipped() {
        this.contextRunner.run(context -> {
            final CommunicationService communications = context.getBean(CommunicationService.class);
            final UUID first = communications
                    .send("bank", new Payment("dedup-7"))
                    .communication()
                    .id();
            awaitStatus(context.getBean(DataSource.class), first, "SUCCEEDED");
            final UUID second = communications
                    .send("bank", new Payment("dedup-7"))
                    .communication()
                    .id();

            awaitStatus(context.getBean(DataSource.class), second, "SKIPPED");
            assertThat(column(context.getBean(DataSource.class), second, "status_description"))
                    .isEqualTo("дубликат коммуникации " + first + " по ключу dedup-7");
        });
    }

    @Test
    void registeredKeyMakesOutboundWaitAndCallbackIsHandled() {
        this.contextRunner.run(context -> {
            final CommunicationService communications = context.getBean(CommunicationService.class);
            final UUID id = communications
                    .send("bank", new Payment("wait-1"))
                    .communication()
                    .id();
            awaitStatus(context.getBean(DataSource.class), id, "AWAITING_RESPONSE");

            final ReceiveResponseResult result =
                    communications.receiveResponse("bank", CallbackRequest.of("ref-wait-1", "{\"status\":\"ok\"}"));

            assertThat(result).isInstanceOf(ReceiveResponseResult.Attached.class);
            assertThat(result.communication().id()).isEqualTo(id);
            awaitStatus(context.getBean(DataSource.class), id, "SUCCEEDED");
            assertThat(context.getBean(BankProcessor.class).confirmations).containsExactly(new Confirmation("ok"));
        });
    }

    @Test
    void doNotAwaitEndsSendingWithSuccess() {
        this.contextRunner.run(context -> {
            final UUID id = context.getBean(CommunicationService.class)
                    .send("bank", new Payment("nowait-1"))
                    .communication()
                    .id();

            awaitStatus(context.getBean(DataSource.class), id, "SUCCEEDED");
        });
    }

    @Test
    void secondCallbackAndUnknownKeyBecomeFailedInbound() {
        this.contextRunner.run(context -> {
            final CommunicationService communications = context.getBean(CommunicationService.class);
            final UUID id = communications
                    .send("bank", new Payment("wait-2"))
                    .communication()
                    .id();
            awaitStatus(context.getBean(DataSource.class), id, "AWAITING_RESPONSE");
            communications.receiveResponse("bank", CallbackRequest.of("ref-wait-2", "{\"status\":\"ok\"}"));

            final ReceiveResponseResult again =
                    communications.receiveResponse("bank", CallbackRequest.of("ref-wait-2", "{\"status\":\"ok\"}"));
            final ReceiveResponseResult unknown =
                    communications.receiveResponse("bank", CallbackRequest.of("no-such", "{\"status\":\"ok\"}"));

            assertThat(again).isInstanceOf(ReceiveResponseResult.Unmatched.class);
            assertThat(again.communication().statusDescription()).contains("ожидающий запрос по ключу не найден");
            assertThat(again.communication().origin()).hasValueSatisfying(origin -> {
                assertThat(origin.id()).isEqualTo(id);
                assertThat(origin.relation()).isEqualTo(OriginRelation.RESPONSE);
            });
            assertThat(unknown).isInstanceOf(ReceiveResponseResult.Unmatched.class);
            assertThat(unknown.communication().origin()).isEmpty();
        });
    }

    @Test
    void unparseableCallbackFailsWaitingOutbound() {
        this.contextRunner.run(context -> {
            final CommunicationService communications = context.getBean(CommunicationService.class);
            final UUID id = communications
                    .send("bank", new Payment("wait-3"))
                    .communication()
                    .id();
            awaitStatus(context.getBean(DataSource.class), id, "AWAITING_RESPONSE");

            final ReceiveResponseResult result =
                    communications.receiveResponse("bank", CallbackRequest.of("ref-wait-3", "не json"));

            assertThat(result).isInstanceOf(ReceiveResponseResult.Unparseable.class);
            assertThat(column(context.getBean(DataSource.class), id, "status")).isEqualTo("FAILED");
            assertThat(column(context.getBean(DataSource.class), id, "status_description"))
                    .isEqualTo("колбэк не разбирается");
        });
    }

    @Test
    void expiredWaitResendsThenFails() {
        this.contextRunner
                .withPropertyValues("taskcomm.communication.systems.bank.response-timeout=1s")
                .run(context -> {
                    final UUID id = context.getBean(CommunicationService.class)
                            .send("bank", new Payment("wait-4"))
                            .communication()
                            .id();

                    awaitStatus(context.getBean(DataSource.class), id, "FAILED");
                    assertThat(column(context.getBean(DataSource.class), id, "status_description"))
                            .isEqualTo("ответ не получен за PT1S");
                    assertThat(JdbcClient.create(context.getBean(DataSource.class))
                                    .sql("SELECT count(*) FROM tc_communication_attempt WHERE communication_id = :id"
                                            + " AND phase = 'PROCESS' AND status = 'SUCCEEDED'")
                                    .param("id", id)
                                    .query(Integer.class)
                                    .single())
                            .isEqualTo(2);
                });
    }

    @Test
    void dedupKeyInRepeatableReadTransactionIsRejected() {
        this.contextRunner.run(context -> {
            final TransactionTemplate strict =
                    new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            strict.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);

            assertThatThrownBy(() -> strict.executeWithoutResult(status -> context.getBean(CommunicationService.class)
                            .send(
                                    "bank",
                                    new Payment("p-9"),
                                    CommunicationSendOptions.defaults().dedupKey("p-9"))))
                    .isInstanceOf(TaskcommException.class)
                    .hasMessageContaining("READ COMMITTED");
        });
    }

    private static void awaitStatus(final DataSource dataSource, final UUID id, final String status) {
        await().atMost(WAIT)
                .untilAsserted(
                        () -> assertThat(column(dataSource, id, "status")).isEqualTo(status));
    }

    private static String column(final DataSource dataSource, final UUID id, final String column) {
        return JdbcClient.create(dataSource)
                .sql("SELECT " + column + " FROM tc_communication WHERE id = :id")
                .param("id", id)
                .query(String.class)
                .single();
    }

    /**
     * Платёж.
     *
     * @param id идентификатор
     */
    record Payment(String id) {}

    /**
     * Подтверждение банка — колбэк.
     *
     * @param status статус
     */
    record Confirmation(String status) {}

    /**
     * Банк по REST: {@code wait-*} регистрирует ключ корреляции {@code ref-<id>} и ждёт ответа,
     * {@code nowait-*} регистрирует, но не ждёт, {@code dedup-*} задаёт ключ дедупликации из {@code process}.
     */
    static class BankProcessor implements RestCommunicationProcessor<Payment, Void, Confirmation> {

        final List<Confirmation> confirmations = new CopyOnWriteArrayList<>();

        @Override
        public String externalSystem() {
            return "bank";
        }

        @Override
        public Void process(final Communication<Payment> communication, final CommunicationContext context) {
            final String id = communication.requestPayload().id();
            if (communication instanceof Communication.Inbound<Payment>) {
                return null;
            }
            if (id.startsWith("dedup-") && context.dedupKey(id)) {
                return null;
            }
            context.recordResponse(ExchangeMessage.of("{}").withStatus(200));
            if (id.startsWith("wait-") || id.startsWith("nowait-")) {
                context.registerCorrelationId("ref-" + id);
            }
            if (id.startsWith("nowait-")) {
                context.doNotAwaitResponse();
            }
            return null;
        }

        @Override
        public void handleResponse(
                final Communication.Outbound<Payment> communication,
                final Confirmation response,
                final CommunicationContext context) {
            this.confirmations.add(response);
        }
    }

    /** Процессор теста. */
    @Configuration(proxyBeanMethods = false)
    static class Processors {

        @Bean
        BankProcessor bankProcessor() {
            return new BankProcessor();
        }
    }
}
