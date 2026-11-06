package xyz.avasilev.taskcomm.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.avasilev.taskcomm.TaskcommException;
import xyz.avasilev.taskcomm.TestPostgres;
import xyz.avasilev.taskcomm.communication.api.Communication;
import xyz.avasilev.taskcomm.communication.api.CommunicationHeaders;
import xyz.avasilev.taskcomm.communication.api.CommunicationService;
import xyz.avasilev.taskcomm.communication.api.CommunicationStatus;
import xyz.avasilev.taskcomm.communication.api.ExchangeMessage;
import xyz.avasilev.taskcomm.communication.api.InboundRequest;
import xyz.avasilev.taskcomm.communication.api.ReceiveAndProcessResult;
import xyz.avasilev.taskcomm.communication.api.ReceiveResult;
import xyz.avasilev.taskcomm.communication.api.SendResult;
import xyz.avasilev.taskcomm.communication.api.processor.CommunicationContext;
import xyz.avasilev.taskcomm.communication.api.processor.RestCommunicationProcessor;
import xyz.avasilev.taskcomm.communication.api.processor.SoapCommunicationProcessor;

/**
 * Коммуникации проходят путь до терминального статуса через публичный API и настоящий движок
 * (docs/adr/0135-communication-engine.md): исходящая с записанным обменом и формой контракта при первом
 * захвате, исход по отказному статусу ответа, входящая с повтором и копией запроса, неразобранное
 * тело, синхронный приём с ответом и без повтора, отказ внутри транзакции, системы без процессора,
 * разбор SOAP по умолчанию.
 */
class CommunicationLifecycleIT {

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
                    "taskcomm.communication.retry.max-attempts=3",
                    "taskcomm.communication.retry.initial-delay=0s",
                    "taskcomm.communication.retry.multiplier=1",
                    "taskcomm.communication.retry.max-delay=0s");

    @Test
    void outboundSucceedsWithRecordedExchangeAndShapeAtFirstClaim() {
        this.contextRunner.run(context -> {
            final SendResult<Order> sent =
                    context.getBean(CommunicationService.class).send("orders", new Order("A-1"));
            final UUID id = sent.communication().id();

            assertThat(sent).isInstanceOf(SendResult.Created.class);
            assertThat(sent.communication().status()).isEqualTo(CommunicationStatus.PENDING);
            awaitStatus(context.getBean(DataSource.class), id, "SUCCEEDED");
            final Map<String, Object> attempt = lastAttempt(context.getBean(DataSource.class), id);
            assertThat(attempt)
                    .containsEntry("status", "SUCCEEDED")
                    .containsEntry("request_body", "A-1")
                    .containsEntry("response_body", "{\"accepted\":true}");
            assertThat(attempt.get("response_status")).hasToString("200");
            assertThat(attempt.get("response_payload")).hasToString("{\"text\": \"отправлен A-1\"}");
            assertThat(column(context.getBean(DataSource.class), id, "contract_shape"))
                    .isEqualTo("REST");
        });
    }

    @Test
    void failureStatusMakesAttemptFailedAndRetriesUntilExhausted() {
        this.contextRunner.run(context -> {
            final UUID id = context.getBean(CommunicationService.class)
                    .send("orders", new Order("503"))
                    .communication()
                    .id();

            awaitStatus(context.getBean(DataSource.class), id, "FAILED");
            assertThat(column(context.getBean(DataSource.class), id, "status_description"))
                    .isEqualTo("попытки исчерпаны");
            assertThat(attemptColumn(context.getBean(DataSource.class), id, "status_description"))
                    .containsExactly("ответ 503", "ответ 503", "ответ 503");
        });
    }

    @Test
    void inboundRetryCopiesRequestIntoNextAttempt() {
        this.contextRunner.run(context -> {
            final ReceiveResult<Order> received = context.getBean(CommunicationService.class)
                    .receive(
                            OrderProcessor.class,
                            InboundRequest.of("{\"number\":\"retry\"}")
                                    .withRequestId("m-1")
                                    .withHeaders(CommunicationHeaders.empty().with("X-Trace", "t")));
            final UUID id = received.communication().id();

            assertThat(received).isInstanceOf(ReceiveResult.Accepted.class);
            assertThat(received.communication().requestPayload()).isEqualTo(new Order("retry"));
            assertThat(column(context.getBean(DataSource.class), id, "contract_shape"))
                    .isEqualTo("REST");
            awaitStatus(context.getBean(DataSource.class), id, "SUCCEEDED");
            assertThat(attemptColumn(context.getBean(DataSource.class), id, "status"))
                    .containsExactly("FAILED", "SUCCEEDED");
            assertThat(attemptColumn(context.getBean(DataSource.class), id, "request_id"))
                    .containsExactly("m-1", "m-1");
            assertThat(attemptColumn(context.getBean(DataSource.class), id, "request_body"))
                    .containsOnly("{\"number\":\"retry\"}");
        });
    }

    @Test
    void unparseableBodyFailsAtOnceWithoutCallingProcessor() {
        this.contextRunner.run(context -> {
            final ReceiveResult<?> received =
                    context.getBean(CommunicationService.class).receive("orders", InboundRequest.of("не json"));
            final UUID id = received.communication().id();

            assertThat(received)
                    .isInstanceOfSatisfying(
                            ReceiveResult.Unparseable.class,
                            unparseable -> assertThat(unparseable.reason()).startsWith("тело не разбирается"));
            assertThat(received.communication().status()).isEqualTo(CommunicationStatus.FAILED);
            assertThat(column(context.getBean(DataSource.class), id, "status")).isEqualTo("FAILED");
            assertThat(attemptColumn(context.getBean(DataSource.class), id, "request_body"))
                    .containsExactly("не json");
            assertThat(attemptColumn(context.getBean(DataSource.class), id, "status"))
                    .containsExactly("FAILED");
        });
    }

    @Test
    void syncReceptionReturnsProcessorResponseAndStoresIt() {
        this.contextRunner.run(context -> {
            final ReceiveAndProcessResult<Order, Ack> result = context.getBean(CommunicationService.class)
                    .receiveAndProcess(OrderProcessor.class, InboundRequest.of("{\"number\":\"B-2\"}"));

            assertThat(result)
                    .isInstanceOfSatisfying(
                            ReceiveAndProcessResult.Processed.class,
                            processed -> assertThat(processed.response()).isEqualTo(new Ack("принят B-2")));
            assertThat(result.communication().status()).isEqualTo(CommunicationStatus.SUCCEEDED);
            final UUID id = result.communication().id();
            assertThat(lastAttempt(context.getBean(DataSource.class), id).get("response_payload"))
                    .hasToString("{\"text\": \"принят B-2\"}");
        });
    }

    @Test
    void syncReceptionFailureIsNotRetried() {
        this.contextRunner.run(context -> {
            final ReceiveAndProcessResult<?, ?> result = context.getBean(CommunicationService.class)
                    .receiveAndProcess("orders", InboundRequest.of("{\"number\":\"retry\"}"));

            assertThat(result).isInstanceOf(ReceiveAndProcessResult.Failed.class);
            assertThat(result.communication().status()).isEqualTo(CommunicationStatus.FAILED);
            assertThat(result.communication().statusDescription())
                    .hasValueSatisfying(reason -> assertThat(reason).startsWith("синхронный приём не повторяется: "));
            assertThat(attemptColumn(
                            context.getBean(DataSource.class),
                            result.communication().id(),
                            "status"))
                    .containsExactly("FAILED");
        });
    }

    @Test
    void syncReceptionInsideTransactionIsRejectedBeforeWriting() {
        this.contextRunner.run(context -> {
            final TransactionTemplate transaction =
                    new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            assertThatThrownBy(
                            () -> transaction.executeWithoutResult(status -> context.getBean(CommunicationService.class)
                                    .receiveAndProcess("orders", InboundRequest.of("{\"number\":\"C-3\"}"))))
                    .isInstanceOf(TaskcommException.class)
                    .hasMessageContaining("внутри активной транзакции");
            assertThat(count(context.getBean(DataSource.class))).isZero();
        });
    }

    @Test
    void unknownSystemIsRejectedBeforeWriting() {
        this.contextRunner.run(context -> {
            final CommunicationService communications = context.getBean(CommunicationService.class);

            assertThatThrownBy(() -> communications.send("nobody", new Order("x")))
                    .isInstanceOf(TaskcommException.class)
                    .hasMessageContaining("'nobody'");
            assertThatThrownBy(() -> communications.receive("nobody", InboundRequest.of("{}")))
                    .isInstanceOf(TaskcommException.class);
            assertThat(count(context.getBean(DataSource.class))).isZero();
        });
    }

    @Test
    void soapBodyIsUnwrappedFromEnvelopeByDefault() {
        this.contextRunner.run(context -> {
            final ReceiveResult<?> received =
                    context.getBean(CommunicationService.class).receive("ledger", InboundRequest.of("""
                                    <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
                                      <soap:Header/>
                                      <soap:Body><Order><number>L-7</number></Order></soap:Body>
                                    </soap:Envelope>
                                    """));

            assertThat(received).isInstanceOf(ReceiveResult.Accepted.class);
            assertThat(received.communication().requestPayload()).isEqualTo(new Order("L-7"));
            awaitStatus(
                    context.getBean(DataSource.class), received.communication().id(), "SUCCEEDED");
            assertThat(context.getBean(LedgerProcessor.class).received.get()).isEqualTo(1);
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

    private static List<String> attemptColumn(final DataSource dataSource, final UUID id, final String column) {
        return JdbcClient.create(dataSource)
                .sql("SELECT " + column + "::text FROM tc_communication_attempt WHERE communication_id = :id"
                        + " AND started_at IS NOT NULL ORDER BY started_at")
                .param("id", id)
                .query(String.class)
                .list();
    }

    private static Map<String, Object> lastAttempt(final DataSource dataSource, final UUID id) {
        return JdbcClient.create(dataSource).sql("""
                        SELECT status, request_body, response_body, response_status, response_payload::text
                          FROM tc_communication_attempt
                         WHERE communication_id = :id AND started_at IS NOT NULL
                         ORDER BY started_at DESC LIMIT 1
                        """).param("id", id).query().singleRow();
    }

    private static int count(final DataSource dataSource) {
        return JdbcClient.create(dataSource)
                .sql("SELECT count(*) FROM tc_communication")
                .query(Integer.class)
                .single();
    }

    /**
     * Заказ.
     *
     * @param number номер
     */
    record Order(String number) {}

    /**
     * Ответ.
     *
     * @param text текст
     */
    record Ack(String text) {}

    /**
     * Система заказов по REST в обоих направлениях. Номер {@code 503} — отказной ответ; {@code retry} —
     * первая попытка падает исключением.
     */
    static class OrderProcessor implements RestCommunicationProcessor<Order, Ack, Void> {

        private final AtomicInteger retries = new AtomicInteger();

        @Override
        public String externalSystem() {
            return "orders";
        }

        @Override
        public Ack process(final Communication<Order> communication, final CommunicationContext context) {
            final String number = communication.requestPayload().number();
            if ("retry".equals(number) && this.retries.getAndIncrement() == 0) {
                throw new IllegalStateException("временный сбой");
            }
            return switch (communication) {
                case Communication.Inbound<Order> _ -> new Ack("принят " + number);
                case Communication.Outbound<Order> _ -> {
                    context.recordRequest(ExchangeMessage.of(number));
                    final int status = "503".equals(number) ? 503 : 200;
                    context.recordResponse(
                            ExchangeMessage.of("{\"accepted\":true}").withStatus(status));
                    yield new Ack("отправлен " + number);
                }
            };
        }
    }

    /** Система по SOAP: разбор по умолчанию, считает принятые. */
    static class LedgerProcessor implements SoapCommunicationProcessor<Order, Void, Void> {

        final AtomicInteger received = new AtomicInteger();

        @Override
        public String externalSystem() {
            return "ledger";
        }

        @Override
        public Void process(final Communication<Order> communication, final CommunicationContext context) {
            this.received.incrementAndGet();
            return null;
        }
    }

    /** Процессоры теста. */
    @Configuration(proxyBeanMethods = false)
    static class Processors {

        @Bean
        OrderProcessor orderProcessor() {
            return new OrderProcessor();
        }

        @Bean
        LedgerProcessor ledgerProcessor() {
            return new LedgerProcessor();
        }
    }
}
