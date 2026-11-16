package xyz.avasilev.taskcomm.ui.demo;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import xyz.avasilev.taskcomm.communication.api.Communication;
import xyz.avasilev.taskcomm.communication.api.CommunicationHeaders;
import xyz.avasilev.taskcomm.communication.api.ExchangeMessage;
import xyz.avasilev.taskcomm.communication.api.processor.CommunicationContext;
import xyz.avasilev.taskcomm.communication.api.processor.RestCommunicationProcessor;

/**
 * Остатки складов — система, которую раскрытие отгрузки вызывает синхронно
 * (docs/adr/0167-communication-run-mode.md): раскрыть задачу на группы можно только по её ответу.
 * Первая попытка отвечает 503 — повтор идёт здесь же, в потоке раскрытия.
 */
@Configuration(proxyBeanMethods = false)
class DemoStock {

    private static final List<String> CITIES = List.of("Подольск", "Химки", "Тверь");

    @Bean
    StockProcessor demoStockProcessor() {
        return new StockProcessor();
    }

    /**
     * Где взять позиции заказа.
     *
     * @param orderId номер заказа
     * @param warehouses сколько складов нужно
     */
    record StockQuery(String orderId, int warehouses) {}

    /**
     * Склад с остатком.
     *
     * @param warehouse номер склада
     * @param city город
     */
    record Warehouse(int warehouse, String city) {}

    /**
     * Ответ остатков: склады, на которых есть позиции заказа.
     *
     * @param warehouses склады
     */
    record StockAnswer(List<Warehouse> warehouses) {}

    /** Остатки по REST: синхронный ответ, без колбэка; первая попытка — 503. */
    static final class StockProcessor implements RestCommunicationProcessor<StockQuery, StockAnswer, Void> {

        @Override
        public String externalSystem() {
            return "demo-stock";
        }

        @Override
        public String title() {
            return "Остатки складов";
        }

        @Override
        public String description() {
            return "Синхронно из раскрытия отгрузки: на каких складах есть позиции; первая попытка — 503";
        }

        @Override
        public StockAnswer process(final Communication<StockQuery> communication, final CommunicationContext context) {
            final StockQuery query = communication.requestPayload();
            final String requestId = "stk-req-" + query.orderId() + "-" + context.attemptNumber();
            final CommunicationHeaders json = CommunicationHeaders.empty().with("Content-Type", "application/json");
            context.recordRequest(ExchangeMessage.of(
                            "{\"order\":\"" + query.orderId() + "\",\"warehouses\":" + query.warehouses() + "}")
                    .withId(requestId)
                    .withAt(Instant.now())
                    .withHeaders(json));
            if (context.attemptNumber() == 1) {
                // Отказной статус ответа — неуспех попытки; повтор пойдёт в потоке раскрытия.
                context.recordResponse(ExchangeMessage.of("{\"error\":\"temporarily unavailable\"}")
                        .withId("stk-" + UUID.randomUUID())
                        .withAt(Instant.now())
                        .withStatus(503)
                        .withHeaders(json));
                return null;
            }
            final List<Warehouse> warehouses = new ArrayList<>();
            for (int number = 1; number <= query.warehouses(); number++) {
                warehouses.add(new Warehouse(number, CITIES.get((number - 1) % CITIES.size())));
            }
            final StringBuilder body = new StringBuilder("{\"warehouses\":[");
            for (final Warehouse warehouse : warehouses) {
                body.append(warehouse.warehouse() > 1 ? "," : "")
                        .append("{\"warehouse\":")
                        .append(warehouse.warehouse())
                        .append(",\"city\":\"")
                        .append(warehouse.city())
                        .append("\"}");
            }
            context.recordResponse(ExchangeMessage.of(body.append("]}").toString())
                    .withId("stk-" + UUID.randomUUID())
                    .withAt(Instant.now())
                    .withStatus(200)
                    .withHeaders(json));
            return new StockAnswer(warehouses);
        }
    }
}
