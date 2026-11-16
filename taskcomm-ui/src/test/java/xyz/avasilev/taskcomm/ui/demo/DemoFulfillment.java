package xyz.avasilev.taskcomm.ui.demo;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import xyz.avasilev.taskcomm.communication.api.CallbackRequest;
import xyz.avasilev.taskcomm.communication.api.Communication;
import xyz.avasilev.taskcomm.communication.api.CommunicationHeaders;
import xyz.avasilev.taskcomm.communication.api.CommunicationSendOptions;
import xyz.avasilev.taskcomm.communication.api.CommunicationService;
import xyz.avasilev.taskcomm.communication.api.ExchangeMessage;
import xyz.avasilev.taskcomm.communication.api.SendAndProcessResult;
import xyz.avasilev.taskcomm.communication.api.processor.CommunicationContext;
import xyz.avasilev.taskcomm.communication.api.processor.RestCommunicationProcessor;
import xyz.avasilev.taskcomm.task.api.Task;
import xyz.avasilev.taskcomm.task.api.TaskBatch;
import xyz.avasilev.taskcomm.task.api.TaskService;
import xyz.avasilev.taskcomm.task.api.TaskUnit;
import xyz.avasilev.taskcomm.task.api.processor.BatchTaskProcessor;
import xyz.avasilev.taskcomm.task.api.processor.CollectedBatch;
import xyz.avasilev.taskcomm.task.api.processor.CollectedUnit;
import xyz.avasilev.taskcomm.task.api.processor.Emitter;
import xyz.avasilev.taskcomm.task.api.processor.FlatTaskProcessor;
import xyz.avasilev.taskcomm.task.api.processor.ProcessingContext;
import xyz.avasilev.taskcomm.task.api.processor.SearchableField;
import xyz.avasilev.taskcomm.ui.demo.DemoProcessors.Contact;
import xyz.avasilev.taskcomm.ui.demo.DemoProcessors.Customer;
import xyz.avasilev.taskcomm.ui.demo.DemoProcessors.Shipping;
import xyz.avasilev.taskcomm.ui.demo.DemoStock.StockAnswer;
import xyz.avasilev.taskcomm.ui.demo.DemoStock.StockQuery;

/**
 * Сложное дело отгрузки — чтобы было на чём смотреть экран дела. Задача с группами по складам
 * и подзадачами по позициям, каждая подзадача резервирует позицию на складе синхронной SOAP-коммуникацией;
 * сборка задачи отправляет платёж в банк; колбэк банка ставит задачу доставки, она в одной обработке
 * отправляет заявку перевозчику и два уведомления; перевозчик с первой попытки отвечает 503, со второй
 * принимает и колбэком сообщает о доставке, а тот ставит задачу закрытия с последними уведомлениями.
 * Всё это — одно дело: каждая сущность создана внутри обработки своего создателя.
 */
@Configuration(proxyBeanMethods = false)
class DemoFulfillment {

    @Bean
    FulfillmentProcessor demoFulfillmentProcessor(@Lazy final CommunicationService communications) {
        return new FulfillmentProcessor(communications);
    }

    @Bean
    ShippingProcessor demoShipping(@Lazy final CommunicationService communications) {
        return new ShippingProcessor(communications);
    }

    @Bean
    ClosingProcessor demoClosing(@Lazy final CommunicationService communications) {
        return new ClosingProcessor(communications);
    }

    @Bean(destroyMethod = "close")
    CarrierProcessor demoCarrier(@Lazy final CommunicationService communications, @Lazy final TaskService tasks) {
        return new CarrierProcessor(communications, tasks);
    }

    @Bean
    NoticeProcessor demoNotice() {
        return new NoticeProcessor();
    }

    @Bean
    ApplicationRunner demoFulfillmentWork(final TaskService tasks) {
        return args -> {
            final Customer romashka = new Customer("ООО Ромашка", "7701234567");
            // Отгрузка: отгрузка → резервы на складах → платёж → доставка → перевозчик → закрытие.
            tasks.submit(
                    "demo-fulfillment",
                    new Fulfillment(
                            "B-7",
                            romashka,
                            42_500,
                            new DemoProcessors.Contact(
                                    "Москва, ул. Лесная, 5", "+7 900 000-00-07", "orders@romashka.example"),
                            2,
                            3));
        };
    }

    /**
     * Отгрузка заказа со складов.
     *
     * @param orderId номер заказа
     * @param customer клиент
     * @param amount сумма, рублей
     * @param contact доставка и уведомления
     * @param warehouses складов
     * @param positions позиций на каждом
     */
    record Fulfillment(String orderId, Customer customer, int amount, Contact contact, int warehouses, int positions) {}

    /**
     * Склад отгрузки — группа.
     *
     * @param warehouse номер склада
     * @param city город
     * @param positions позиций
     */
    record Stock(int warehouse, String city, int positions) {}

    /**
     * Позиция к резерву — подзадача.
     *
     * @param warehouse склад
     * @param sku артикул
     * @param quantity количество
     */
    record Position(int warehouse, String sku, int quantity) {}

    /**
     * Заявка перевозчику.
     *
     * @param orderId номер заказа
     * @param contact адрес и контакты
     */
    record Shipment(String orderId, Contact contact) {}

    /**
     * Статус доставки — колбэк перевозчика.
     *
     * @param status статус
     */
    record Delivery(String status) {}

    /**
     * Закрытие заказа — задача, которую ставит колбэк перевозчика.
     *
     * @param orderId номер заказа
     * @param contact контакты
     */
    record Closing(String orderId, Contact contact) {}

    /**
     * Уведомление клиенту.
     *
     * @param channel канал: {@code sms} или {@code email}
     * @param to адресат
     * @param text текст
     */
    record Notice(String channel, String to, String text) {}

    /**
     * Отгрузка: группа на склад, подзадача на позицию — каждая резервирует позицию на складе синхронной
     * SOAP-коммуникацией; сборка задачи отправляет платёж с доставкой в банк.
     */
    static final class FulfillmentProcessor
            implements BatchTaskProcessor<Fulfillment, Stock, Position, String, Long, String> {

        private final CommunicationService communications;

        FulfillmentProcessor(final CommunicationService communications) {
            this.communications = communications;
        }

        @Override
        public String taskType() {
            return "demo-fulfillment";
        }

        @Override
        public String title() {
            return "Отгрузка заказа";
        }

        @Override
        public String taskTitle() {
            return "Отгрузка заказа {orderId}";
        }

        @Override
        public String batchTitle() {
            return "Склад {warehouse}, {city}";
        }

        @Override
        public String unitTitle() {
            return "Резерв {sku}";
        }

        @Override
        public String description() {
            return "Резервы по складам, затем платёж; дальше дело ведут колбэки банка и перевозчика";
        }

        @Override
        public List<SearchableField> searchableTaskFields() {
            return List.of(
                    SearchableField.string("orderId", "$.orderId", "Номер заказа"),
                    SearchableField.string("customer", "$.customer.name", "Клиент"));
        }

        @Override
        public List<SearchableField> searchableBatchFields() {
            return List.of(
                    SearchableField.number("warehouse", "$.warehouse", "Склад"),
                    SearchableField.string("city", "$.city", "Город склада"));
        }

        @Override
        public List<SearchableField> searchableUnitFields() {
            return List.of(SearchableField.string("sku", "$.sku", "Артикул"));
        }

        @Override
        public void expand(
                final Task<Fulfillment> task, final Emitter<Stock> batches, final ProcessingContext context) {
            // Раскрыть можно только по ответу остатков: синхронная отправка — здесь же, в потоке раскрытия
            // (docs/adr/0167-communication-run-mode.md). Коммуникация попадёт в дело под эту попытку раскрытия.
            final SendAndProcessResult<StockQuery, ?, ?> stock = this.communications.sendAndProcess(
                    "demo-stock",
                    new StockQuery(task.payload().orderId(), task.payload().warehouses()));
            if (!(stock instanceof SendAndProcessResult.Processed<StockQuery, ?, ?> processed)
                    || !(processed.response() instanceof StockAnswer answer)) {
                throw new IllegalStateException("остатки складов не получены: " + stock);
            }
            for (final DemoStock.Warehouse warehouse : answer.warehouses()) {
                batches.emit(new Stock(
                        warehouse.warehouse(), warehouse.city(), task.payload().positions()));
            }
        }

        @Override
        public void expandBatch(
                final TaskBatch<Stock> batch, final Emitter<Position> units, final ProcessingContext context) {
            for (int position = 1; position <= batch.payload().positions(); position++) {
                units.emit(new Position(
                        batch.payload().warehouse(),
                        "SKU-" + batch.payload().warehouse() + "-" + (200 + position),
                        position));
            }
        }

        @Override
        public String process(final TaskUnit<Position> unit, final ProcessingContext context) {
            DemoProcessors.pause(200);
            this.communications.send(
                    "demo-warehouse",
                    new DemoProcessors.Reservation(
                            unit.payload().sku(), unit.payload().quantity()));
            return "резерв " + unit.payload().sku() + " запрошен";
        }

        @Override
        public Long collectBatch(
                final TaskBatch<Stock> batch,
                final Stream<CollectedUnit<Position, String>> units,
                final ProcessingContext context) {
            return units.count();
        }

        @Override
        public String collect(
                final Task<Fulfillment> task,
                final Stream<CollectedBatch<Stock, Long>> batches,
                final ProcessingContext context) {
            final Fulfillment order = task.payload();
            this.communications.send(
                    "demo-bank",
                    new DemoProcessors.Payment(
                            order.orderId(),
                            order.amount(),
                            new DemoProcessors.Shipping(order.orderId(), order.contact())),
                    CommunicationSendOptions.defaults().dedupKey("payment-" + order.orderId()));
            return "резервы на " + batches.count() + " складах, платёж отправлен";
        }
    }

    /** Доставка: в одной обработке — заявка перевозчику, SMS и письмо клиенту. */
    static final class ShippingProcessor implements FlatTaskProcessor<Shipping, String> {

        private final CommunicationService communications;

        ShippingProcessor(final CommunicationService communications) {
            this.communications = communications;
        }

        @Override
        public String taskType() {
            return "demo-shipping";
        }

        @Override
        public String title() {
            return "Доставка заказа";
        }

        @Override
        public String taskTitle() {
            return "Доставка заказа {orderId}";
        }

        @Override
        public String description() {
            return "Заявка перевозчику и два уведомления клиенту — три коммуникации из одной задачи";
        }

        @Override
        public List<SearchableField> searchableTaskFields() {
            return List.of(SearchableField.string("orderId", "$.orderId", "Номер заказа"));
        }

        @Override
        public String process(final Task<Shipping> task, final ProcessingContext context) {
            final Shipping shipping = task.payload();
            this.communications.send("demo-carrier", new Shipment(shipping.orderId(), shipping.contact()));
            this.communications.send(
                    "demo-notice",
                    new Notice("sms", shipping.contact().phone(), "Заказ " + shipping.orderId() + " оплачен"));
            this.communications.send(
                    "demo-notice",
                    new Notice(
                            "email",
                            shipping.contact().email(),
                            "Заказ " + shipping.orderId() + " оплачен и передан в доставку"));
            return "доставка заказа " + shipping.orderId() + " заказана";
        }
    }

    /** Закрытие: последние уведомления клиенту после доставки. */
    static final class ClosingProcessor implements FlatTaskProcessor<Closing, String> {

        private final CommunicationService communications;

        ClosingProcessor(final CommunicationService communications) {
            this.communications = communications;
        }

        @Override
        public String taskType() {
            return "demo-closing";
        }

        @Override
        public String title() {
            return "Закрытие заказа";
        }

        @Override
        public String taskTitle() {
            return "Закрытие заказа {orderId}";
        }

        @Override
        public String description() {
            return "Ставится колбэком перевозчика; сообщает клиенту о доставке";
        }

        @Override
        public List<SearchableField> searchableTaskFields() {
            return List.of(SearchableField.string("orderId", "$.orderId", "Номер заказа"));
        }

        @Override
        public String process(final Task<Closing> task, final ProcessingContext context) {
            final Closing closing = task.payload();
            this.communications.send(
                    "demo-notice",
                    new Notice("sms", closing.contact().phone(), "Заказ " + closing.orderId() + " доставлен"));
            this.communications.send(
                    "demo-notice",
                    new Notice(
                            "email",
                            closing.contact().email(),
                            "Заказ " + closing.orderId() + " доставлен. Оцените покупку"));
            return "заказ " + closing.orderId() + " закрыт";
        }
    }

    /**
     * Перевозчик: REST с колбэком. Первая попытка отвечает 503 и падает — коммуникация повторяется;
     * вторая принимает заявку, а через 6 секунд перевозчик присылает колбэк «доставлено», который
     * ставит задачу закрытия в том же деле.
     */
    static final class CarrierProcessor
            implements RestCommunicationProcessor<Shipment, String, Delivery>, AutoCloseable {

        private final CommunicationService communications;

        private final TaskService tasks;

        private final ScheduledExecutorService callbacks = Executors.newSingleThreadScheduledExecutor();

        CarrierProcessor(final CommunicationService communications, final TaskService tasks) {
            this.communications = communications;
            this.tasks = tasks;
        }

        @Override
        public String externalSystem() {
            return "demo-carrier";
        }

        @Override
        public String title() {
            return "Перевозчик";
        }

        @Override
        public String description() {
            return "Первая попытка — 503, вторая принимает заявку; через 6 секунд колбэк о доставке";
        }

        @Override
        public String process(final Communication<Shipment> communication, final CommunicationContext context) {
            final Shipment shipment = communication.requestPayload();
            final String requestId = "dlv-req-" + shipment.orderId() + "-" + context.attemptNumber();
            context.recordRequest(ExchangeMessage.of("{\"order\":\"" + shipment.orderId() + "\",\"address\":\""
                            + shipment.contact().address() + "\"}")
                    .withId(requestId)
                    .withAt(Instant.now())
                    .withHeaders(json().with("X-Request-Id", requestId)));
            if (context.attemptNumber() == 1) {
                context.recordResponse(ExchangeMessage.of("{\"error\":\"service unavailable\"}")
                        .withId("dlv-" + UUID.randomUUID())
                        .withAt(Instant.now())
                        .withStatus(503)
                        .withHeaders(json().with("Retry-After", "10")));
                throw new IllegalStateException("перевозчик недоступен: 503");
            }
            final String tracking = "TRK-" + shipment.orderId();
            context.recordResponse(ExchangeMessage.of("{\"accepted\":true,\"tracking\":\"" + tracking + "\"}")
                    .withId("dlv-" + UUID.randomUUID())
                    .withAt(Instant.now())
                    .withStatus(201)
                    .withHeaders(json()));
            context.registerCorrelationId(tracking);
            var _ = this.callbacks.schedule(
                    () -> this.communications.receiveResponse(
                            "demo-carrier",
                            CallbackRequest.of(tracking, "{\"status\":\"delivered\"}")
                                    .withRequestId("dlv-cb-" + UUID.randomUUID())
                                    .withRequestAt(Instant.now())
                                    .withHeaders(json())),
                    6,
                    TimeUnit.SECONDS);
            return "заявка принята: " + tracking;
        }

        @Override
        public void handleResponse(
                final Communication.Outbound<Shipment> communication,
                final Delivery response,
                final CommunicationContext context) {
            final Shipment shipment = communication.requestPayload();
            this.tasks.submit("demo-closing", new Closing(shipment.orderId(), shipment.contact()));
        }

        private static CommunicationHeaders json() {
            return CommunicationHeaders.empty().with("Content-Type", "application/json");
        }

        @Override
        public void close() {
            this.callbacks.shutdownNow();
        }
    }

    /** Уведомления клиенту: SMS и почта, ответ сразу. */
    static final class NoticeProcessor implements RestCommunicationProcessor<Notice, String, Void> {

        @Override
        public String externalSystem() {
            return "demo-notice";
        }

        @Override
        public String title() {
            return "Уведомления";
        }

        @Override
        public String description() {
            return "SMS и письма клиенту; отвечает сразу";
        }

        @Override
        public String process(final Communication<Notice> communication, final CommunicationContext context) {
            final Notice notice = communication.requestPayload();
            final String requestId = "ntf-" + UUID.randomUUID();
            context.recordRequest(ExchangeMessage.of("{\"channel\":\"" + notice.channel() + "\",\"to\":\"" + notice.to()
                            + "\",\"text\":\"" + notice.text() + "\"}")
                    .withId(requestId)
                    .withAt(Instant.now())
                    .withHeaders(CommunicationHeaders.empty().with("Content-Type", "application/json")));
            context.recordResponse(ExchangeMessage.of("{\"queued\":true}")
                    .withId("ntf-ack-" + UUID.randomUUID())
                    .withAt(Instant.now())
                    .withStatus(200)
                    .withHeaders(CommunicationHeaders.empty().with("Content-Type", "application/json")));
            return notice.channel() + " → " + notice.to();
        }
    }
}
