package xyz.avasilev.taskcomm.communication.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.ResolvableType;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;
import xyz.avasilev.taskcomm.PayloadShape;
import xyz.avasilev.taskcomm.PayloadShape.Kind;

class CommunicationRequestShapeTest {

    @Test
    void describesRequestByCommunicationMapper() {
        final CommunicationCodec codec = new CommunicationCodec(JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .build());

        final PayloadShape shape = codec.shape(ResolvableType.forClass(Payment.class));

        assertThat(shape.kind()).isEqualTo(Kind.OBJECT);
        assertThat(shape.properties()).containsOnlyKeys("order_id", "amount", "items", "self");
        assertThat(shape.properties().get("amount").kind()).isEqualTo(Kind.INTEGER);
        assertThat(shape.properties().get("items").items().kind()).isEqualTo(Kind.STRING);
        assertThat(shape.properties().get("self").kind()).isEqualTo(Kind.ANY);
    }

    /**
     * Платёж.
     *
     * @param orderId заказ
     * @param amount сумма
     * @param items позиции
     * @param self ссылка на такой же платёж — рекурсия
     */
    record Payment(String orderId, int amount, List<String> items, Payment self) {}
}
