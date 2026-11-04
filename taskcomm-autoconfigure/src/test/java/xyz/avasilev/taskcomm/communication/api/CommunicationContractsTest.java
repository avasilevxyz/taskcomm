package xyz.avasilev.taskcomm.communication.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Критерий готовности этапа 5a — потребитель компилирует процессор коммуникаций, ничего не запуская:
 * процессоры по всем четырём транспортам, разбор запечатанных иерархий и итогов без ветки
 * {@code default}, разбор по умолчанию и типобезопасные вызовы сервиса по классу процессора
 * (docs/adr/0052, docs/adr/0133).
 */
class CommunicationContractsTest {

    @Test
    void hierarchyIsSealedTwice() {
        assertThat(CommunicationProcessor.class.getPermittedSubclasses())
                .containsExactlyInAnyOrder(
                        HttpCommunicationProcessor.class,
                        KafkaCommunicationProcessor.class,
                        RawCommunicationProcessor.class);
        assertThat(HttpCommunicationProcessor.class.getPermittedSubclasses())
                .containsExactlyInAnyOrder(RestCommunicationProcessor.class, SoapCommunicationProcessor.class);
        assertThat(Communication.class.getPermittedSubclasses())
                .containsExactlyInAnyOrder(Communication.Inbound.class, Communication.Outbound.class);
    }

    @Test
    void workerStyleSwitchCoversAllTransportsWithoutDefault() {
        assertThat(contractShape(new OrderProcessor())).isEqualTo("REST");
        assertThat(contractShape(new LedgerProcessor())).isEqualTo("SOAP");
        assertThat(contractShape(new StockProcessor())).isEqualTo("KAFKA");
        assertThat(contractShape(new LegacyProcessor())).isEqualTo("RAW");
    }

    @Test
    void defaultDecodingDelegatesToTransportDecoder() {
        final CommunicationHeaders headers = CommunicationHeaders.empty();

        assertThat(new OrderProcessor().decodeRequest("{}", headers, body -> new Order("json:" + body)))
                .isEqualTo(new Order("json:{}"));
        assertThat(new LedgerProcessor().decodeRequest("<a/>", headers, body -> new Order("xml:" + body)))
                .isEqualTo(new Order("xml:<a/>"));
        assertThat(new StockProcessor().decodeResponse("{}", headers, body -> new Ack("json:" + body)))
                .isEqualTo(new Ack("json:{}"));
        assertThat(new LegacyProcessor().decodeRequest("A;1", headers)).isEqualTo(new Order("A"));
    }

    @Test
    void beforeProcessAndHandleResponseDoNothingByDefault() {
        final StockProcessor processor = new StockProcessor();

        assertThatCode(() -> {
                    processor.beforeProcess(null, null);
                    processor.handleResponse(null, new Ack("ok"), null);
                })
                .doesNotThrowAnyException();
    }

    /** Разбор так же, как его сделает воркер: появление пятого транспорта сломает компиляцию здесь. */
    private static String contractShape(final CommunicationProcessor<?, ?, ?> processor) {
        return switch (processor) {
            case RestCommunicationProcessor<?, ?, ?> _ -> "REST";
            case SoapCommunicationProcessor<?, ?, ?> _ -> "SOAP";
            case KafkaCommunicationProcessor<?, ?, ?> _ -> "KAFKA";
            case RawCommunicationProcessor<?, ?, ?> _ -> "RAW";
        };
    }

    /**
     * Не вызывается: проверяет компилятором, что вызовы по классу процессора выводят типы запроса
     * и ответа из дженерика, а итоги разбираются без ветки {@code default}.
     */
    @SuppressWarnings("unused")
    private static List<Object> typedCallsCompile(
            final CommunicationService service, final CommunicationContext context) {
        final SendResult<Order> sent = service.send(
                OrderProcessor.class,
                new Order("A-1"),
                CommunicationSendOptions.defaults()
                        .inExecution(context.executionRef())
                        .dedupKey("A-1")
                        .correlationId("ref-1"));
        final SendResult<Order> byName = service.send("orders", new Order("A-2"), context.executionRef());
        final ReceiveResult<Order> received = service.receive(
                OrderProcessor.class,
                InboundRequest.of("{}").withHeaders(CommunicationHeaders.empty().with("X-Id", "1")));
        final String answer =
                switch (service.receiveAndProcess(OrderProcessor.class, InboundRequest.of("{}"))) {
                    case ReceiveAndProcessResult.Processed<Order, Ack> processed ->
                        processed.response().text();
                    case ReceiveAndProcessResult.Failed<Order, Ack> failed -> failed.reason();
                    case ReceiveAndProcessResult.Duplicate<Order, Ack> duplicate ->
                        "duplicate of " + duplicate.duplicateOf();
                    case ReceiveAndProcessResult.Unparseable<Order, Ack> unparseable -> unparseable.reason();
                };
        final ReceiveResponseResult callback = service.receiveResponse("orders", CallbackRequest.of("ref-1", "{}"));
        final List<Communication<Order>> inExecution =
                service.findInExecution(context.executionRef(), OrderProcessor.class);
        return List.of(sent, byName, received, answer, callback, inExecution);
    }

    record Order(String number) {}

    record Ack(String text) {}

    /** REST в обоих направлениях: отвечает отправителю входящей и записывает обмен исходящей. */
    static class OrderProcessor implements RestCommunicationProcessor<Order, Ack, Void> {

        @Override
        public String externalSystem() {
            return "orders";
        }

        @Override
        public Ack process(final Communication<Order> communication, final CommunicationContext context) {
            return switch (communication) {
                case Communication.Inbound<Order> inbound ->
                    new Ack("принят " + inbound.requestPayload().number());
                case Communication.Outbound<Order> outbound -> {
                    context.recordRequest(
                            ExchangeMessage.of(outbound.requestPayload().number()));
                    context.recordResponse(ExchangeMessage.of("{}").withStatus(200));
                    yield new Ack("отправлен");
                }
            };
        }
    }

    /** SOAP, ждущий колбэка. */
    static class LedgerProcessor implements SoapCommunicationProcessor<Order, Void, Ack> {

        @Override
        public String externalSystem() {
            return "ledger";
        }

        @Override
        public Void process(final Communication<Order> communication, final CommunicationContext context) {
            context.registerCorrelationId(communication.id().toString());
            return null;
        }
    }

    /** Kafka с ключами дедупликации до обработки. */
    static class StockProcessor implements KafkaCommunicationProcessor<Order, Void, Ack> {

        @Override
        public String externalSystem() {
            return "stock";
        }

        @Override
        public Void process(final Communication<Order> communication, final CommunicationContext context) {
            if (context.dedupKey("stock", communication.requestPayload().number())) {
                return null;
            }
            context.doNotAwaitResponse();
            return null;
        }
    }

    /** Свой формат: разбор целиком у автора. */
    static class LegacyProcessor implements RawCommunicationProcessor<Order, Void, Void> {

        @Override
        public String externalSystem() {
            return "legacy";
        }

        @Override
        public Order decodeRequest(final String body, final CommunicationHeaders headers) {
            return new Order(body.substring(0, body.indexOf(';')));
        }

        @Override
        public Void decodeResponse(final String body, final CommunicationHeaders headers) {
            throw new IllegalArgumentException("колбэков у системы нет");
        }

        @Override
        public Void process(final Communication<Order> communication, final CommunicationContext context) {
            return null;
        }
    }
}
