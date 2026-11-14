package xyz.avasilev.taskcomm.task.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.ResolvableType;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;
import xyz.avasilev.taskcomm.PayloadShape;
import xyz.avasilev.taskcomm.PayloadShape.Kind;

class TaskPayloadShapeTest {

    private static final TaskPayloadCodec CODEC =
            new TaskPayloadCodec(JsonMapper.builder().build());

    @Test
    void describesRecordWithNestedValues() {
        final PayloadShape shape = CODEC.shape(ResolvableType.forClass(Order.class));

        assertThat(shape.kind()).isEqualTo(Kind.OBJECT);
        assertThat(shape.properties().keySet())
                .containsExactly("id", "customer", "money", "lines", "tags", "state", "due");
        assertThat(shape.properties().get("id").kind()).isEqualTo(Kind.STRING);
        final PayloadShape money = shape.properties().get("money");
        assertThat(money.properties().get("amount").kind()).isEqualTo(Kind.INTEGER);
        assertThat(money.properties().get("rate").kind()).isEqualTo(Kind.NUMBER);
        assertThat(money.properties().get("urgent").kind()).isEqualTo(Kind.BOOLEAN);
        assertThat(shape.properties().get("customer").properties().get("inn").kind())
                .isEqualTo(Kind.STRING);
        assertThat(shape.properties().get("lines").kind()).isEqualTo(Kind.ARRAY);
        assertThat(shape.properties().get("lines").items().properties()).containsOnlyKeys("sku", "count");
        assertThat(shape.properties().get("tags").kind()).isEqualTo(Kind.MAP);
        assertThat(shape.properties().get("tags").items().kind()).isEqualTo(Kind.INTEGER);
        assertThat(shape.properties().get("state").values()).containsExactly("NEW", "PAID");
        assertThat(shape.properties().get("due").kind()).isEqualTo(Kind.STRING);
    }

    @Test
    void followsAnnotations() {
        assertThat(CODEC.shape(ResolvableType.forClass(Hidden.class)).properties())
                .containsOnlyKeys("external", "plain");
    }

    @Test
    void namesKeysByMapperStrategy() {
        final TaskPayloadCodec snake = new TaskPayloadCodec(JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .build());

        assertThat(snake.shape(ResolvableType.forClass(Line.class)).properties())
                .containsOnlyKeys("sku", "count");
        assertThat(snake.shape(ResolvableType.forClass(Customer.class)).properties())
                .containsOnlyKeys("full_name", "inn");
    }

    @Test
    void stopsAtRecursionAndUnknownStructure() {
        final PayloadShape node = CODEC.shape(ResolvableType.forClass(Node.class));

        assertThat(node.properties().get("children").items().kind()).isEqualTo(Kind.ANY);
        assertThat(node.properties().get("extra").kind()).isEqualTo(Kind.ANY);
    }

    /**
     * Заказ.
     *
     * @param id номер
     * @param customer клиент
     * @param money деньги
     * @param lines строки
     * @param tags метки
     * @param state состояние
     * @param due срок
     */
    record Order(
            String id,
            Customer customer,
            Money money,
            List<Line> lines,
            Map<String, Integer> tags,
            State state,
            LocalDate due) {}

    /**
     * Деньги заказа.
     *
     * @param amount сумма
     * @param rate курс
     * @param urgent срочный
     */
    record Money(long amount, double rate, boolean urgent) {}

    /**
     * Поля под аннотациями.
     *
     * @param secret не сериализуется
     * @param internal сериализуется под другим именем
     * @param plain как есть
     */
    record Hidden(
            @JsonIgnore String secret,
            @JsonProperty("external") String internal,
            String plain) {}

    /**
     * Клиент.
     *
     * @param fullName название
     * @param inn ИНН
     */
    record Customer(String fullName, String inn) {}

    /**
     * Строка заказа.
     *
     * @param sku артикул
     * @param count количество
     */
    record Line(String sku, int count) {}

    /** Состояние заказа. */
    enum State {
        /** Новый. */
        NEW,
        /** Оплачен. */
        PAID
    }

    /**
     * Рекурсивный узел.
     *
     * @param name имя
     * @param children дети
     * @param extra что угодно
     */
    record Node(String name, List<Node> children, Object extra) {}
}
