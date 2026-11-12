package xyz.avasilev.taskcomm.ui.demo;

import java.util.List;
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
import xyz.avasilev.taskcomm.communication.api.CommunicationService;
import xyz.avasilev.taskcomm.communication.api.ExchangeMessage;
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
import xyz.avasilev.taskcomm.task.api.processor.UnitTaskProcessor;

/**
 * Процессоры демо и начальная работа: заказ, который отправляет платёж в банк (ребро «кто кого создал»),
 * и заказ, который падает без повтора; сверка подзадачами, часть которых не сходится; выгрузка группами;
 * банк, отвечающий колбэком через несколько секунд.
 */
@Configuration(proxyBeanMethods = false)
class DemoProcessors {

    @Bean
    OrdersProcessor demoOrders(@Lazy final CommunicationService communications) {
        return new OrdersProcessor(communications);
    }

    @Bean
    ReconciliationProcessor demoReconciliation() {
        return new ReconciliationProcessor();
    }

    @Bean
    ExportProcessor demoExport() {
        return new ExportProcessor();
    }

    @Bean(destroyMethod = "close")
    BankProcessor demoBank(@Lazy final CommunicationService communications) {
        return new BankProcessor(communications);
    }

    @Bean
    ApplicationRunner demoWork(final TaskService tasks) {
        return args -> {
            tasks.submit("demo-orders", new Order("A-1", false));
            tasks.submit("demo-orders", new Order("A-2", true));
            tasks.submit("demo-reconciliation", new Reconciliation("A-3", 24, 7));
            tasks.submit("demo-export", new Export(3, 5));
        };
    }

    private static void pause(final long millis) {
        try {
            Thread.sleep(millis);
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    /**
     * Заказ.
     *
     * @param orderId номер заказа
     * @param fail упасть без повтора
     */
    record Order(String orderId, boolean fail) {}

    /**
     * Сверка: сколько строк и какая из них падает.
     *
     * @param orderId номер заказа
     * @param count строк
     * @param failEvery падает каждая такая строка; 0 — ни одна
     */
    record Reconciliation(String orderId, int count, int failEvery) {}

    /**
     * Строка сверки.
     *
     * @param line номер строки
     * @param fail упасть
     */
    record Line(int line, boolean fail) {}

    /**
     * Выгрузка по поставщикам.
     *
     * @param suppliers поставщиков
     * @param perSupplier позиций у каждого
     */
    record Export(int suppliers, int perSupplier) {}

    /**
     * Поставщик.
     *
     * @param supplier номер поставщика
     * @param items позиций
     */
    record Supplier(int supplier, int items) {}

    /**
     * Позиция поставщика.
     *
     * @param supplier поставщик
     * @param item позиция
     */
    record Item(int supplier, int item) {}

    /**
     * Платёж.
     *
     * @param id идентификатор
     * @param amount сумма
     */
    record Payment(String id, int amount) {}

    /**
     * Подтверждение банка — колбэк.
     *
     * @param status статус
     */
    record Confirmation(String status) {}

    /** Заказ: отправляет платёж в банк или падает без повтора. */
    static final class OrdersProcessor implements FlatTaskProcessor<Order, String> {

        private final CommunicationService communications;

        OrdersProcessor(final CommunicationService communications) {
            this.communications = communications;
        }

        @Override
        public String taskType() {
            return "demo-orders";
        }

        @Override
        public List<SearchableField> searchableTaskFields() {
            return List.of(SearchableField.string("orderId", "$.orderId", "Номер заказа"));
        }

        @Override
        public String process(final Task<Order> task, final ProcessingContext context) {
            if (task.payload().fail()) {
                context.doNotRetry("заказ " + task.payload().orderId() + " помечен как неисправимый");
                throw new IllegalStateException("заказ не прошёл проверку");
            }
            this.communications.send("demo-bank", new Payment(task.payload().orderId(), 100));
            return "оплата заказа " + task.payload().orderId() + " отправлена";
        }
    }

    /** Сверка подзадачами: каждая {@code failEvery}-я строка не сходится. */
    static final class ReconciliationProcessor implements UnitTaskProcessor<Reconciliation, Line, String, Long> {

        @Override
        public String taskType() {
            return "demo-reconciliation";
        }

        @Override
        public List<SearchableField> searchableTaskFields() {
            return List.of(SearchableField.string("orderId", "$.orderId", "Номер заказа"));
        }

        @Override
        public void expand(
                final Task<Reconciliation> task, final Emitter<Line> units, final ProcessingContext context) {
            final Reconciliation payload = task.payload();
            for (int line = 1; line <= payload.count(); line++) {
                units.emit(new Line(line, payload.failEvery() > 0 && line % payload.failEvery() == 0));
            }
        }

        @Override
        public String process(final TaskUnit<Line> unit, final ProcessingContext context) {
            pause(300);
            if (unit.payload().fail()) {
                context.doNotRetry("строка " + unit.payload().line() + " не сходится");
                throw new IllegalStateException(
                        "расхождение в строке " + unit.payload().line());
            }
            return "строка " + unit.payload().line() + " сошлась";
        }

        @Override
        public Long collect(
                final Task<Reconciliation> task,
                final Stream<CollectedUnit<Line, String>> units,
                final ProcessingContext context) {
            return units.count();
        }
    }

    /** Выгрузка группами по поставщикам. */
    static final class ExportProcessor implements BatchTaskProcessor<Export, Supplier, Item, String, Long, Long> {

        @Override
        public String taskType() {
            return "demo-export";
        }

        @Override
        public void expand(final Task<Export> task, final Emitter<Supplier> batches, final ProcessingContext context) {
            for (int supplier = 1; supplier <= task.payload().suppliers(); supplier++) {
                batches.emit(new Supplier(supplier, task.payload().perSupplier()));
            }
        }

        @Override
        public void expandBatch(
                final TaskBatch<Supplier> batch, final Emitter<Item> units, final ProcessingContext context) {
            for (int item = 1; item <= batch.payload().items(); item++) {
                units.emit(new Item(batch.payload().supplier(), item));
            }
        }

        @Override
        public String process(final TaskUnit<Item> unit, final ProcessingContext context) {
            pause(200);
            return "позиция " + unit.payload().item() + " выгружена";
        }

        @Override
        public Long collectBatch(
                final TaskBatch<Supplier> batch,
                final Stream<CollectedUnit<Item, String>> units,
                final ProcessingContext context) {
            return units.count();
        }

        @Override
        public Long collect(
                final Task<Export> task,
                final Stream<CollectedBatch<Supplier, Long>> batches,
                final ProcessingContext context) {
            return batches.count();
        }
    }

    /** Банк: принимает платёж, ждёт колбэка по ключу {@code ref-<id>} и сам присылает его через 5 секунд. */
    static final class BankProcessor
            implements RestCommunicationProcessor<Payment, String, Confirmation>, AutoCloseable {

        private final CommunicationService communications;

        private final ScheduledExecutorService callbacks = Executors.newSingleThreadScheduledExecutor();

        BankProcessor(final CommunicationService communications) {
            this.communications = communications;
        }

        @Override
        public String externalSystem() {
            return "demo-bank";
        }

        @Override
        public String process(final Communication<Payment> communication, final CommunicationContext context) {
            final Payment payment = communication.requestPayload();
            context.recordRequest(
                    ExchangeMessage.of("{\"order\":\"" + payment.id() + "\",\"amount\":" + payment.amount() + "}"));
            context.recordResponse(ExchangeMessage.of("{\"accepted\":true}").withStatus(202));
            final String reference = "ref-" + payment.id();
            context.registerCorrelationId(reference);
            var _ = this.callbacks.schedule(
                    () -> this.communications.receiveResponse(
                            "demo-bank", CallbackRequest.of(reference, "{\"status\":\"settled\"}")),
                    5,
                    TimeUnit.SECONDS);
            return "принято банком";
        }

        @Override
        public void handleResponse(
                final Communication.Outbound<Payment> communication,
                final Confirmation response,
                final CommunicationContext context) {
            // подтверждение сохранено в обмене попытки — больше ничего не нужно
        }

        @Override
        public void close() {
            this.callbacks.shutdownNow();
        }
    }
}
