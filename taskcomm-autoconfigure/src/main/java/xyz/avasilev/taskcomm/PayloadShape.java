package xyz.avasilev.taskcomm;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Структура JSON нагрузки — какие ключи и какого вида значения ждёт процессор
 * (docs/adr/0160-payload-shape.md). Строится обходом класса нагрузки тем же маппером, которым
 * стартер её разбирает, и потому учитывает стратегию имён, {@code @JsonProperty} и {@code @JsonIgnore}.
 * Это подсказка для интерфейса, а не схема: последнее слово — за разбором при создании.
 *
 * @param kind вид значения
 * @param properties ключи объекта в порядке сериализации; пусто у всех видов, кроме {@link Kind#OBJECT}
 * @param items структура элемента массива или значения отображения; {@code null} у прочих видов
 * @param values допустимые значения перечисления; пусто, если значение не перечисление
 */
public record PayloadShape(Kind kind, Map<String, PayloadShape> properties, PayloadShape items, List<String> values) {

    /**
     * Проверяет вид и делает коллекции неизменяемыми.
     *
     * @throws NullPointerException если вид пуст
     */
    public PayloadShape {
        Objects.requireNonNull(kind, "kind");
        properties = properties == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(properties));
        values = values == null ? List.of() : List.copyOf(values);
    }

    /**
     * Значение без вложенной структуры.
     *
     * @param kind вид значения
     * @return структура
     */
    public static PayloadShape of(final Kind kind) {
        return new PayloadShape(kind, Map.of(), null, List.of());
    }

    /** Вид значения в JSON. */
    public enum Kind {

        /** Строка, в том числе дата и время, которые маппер пишет строкой. */
        STRING,

        /** Целое число. */
        INTEGER,

        /** Число с дробной частью. */
        NUMBER,

        /** {@code true} или {@code false}. */
        BOOLEAN,

        /** Объект с известными ключами — {@link PayloadShape#properties()}. */
        OBJECT,

        /** Объект с произвольными ключами; значения — {@link PayloadShape#items()}. */
        MAP,

        /** Массив; элементы — {@link PayloadShape#items()}. */
        ARRAY,

        /** Что угодно: структура заранее не известна — свой сериализатор, полиморфизм, {@code Object}. */
        ANY
    }
}
