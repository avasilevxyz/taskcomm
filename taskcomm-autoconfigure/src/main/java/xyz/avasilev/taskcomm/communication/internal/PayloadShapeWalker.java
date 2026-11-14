package xyz.avasilev.taskcomm.communication.internal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.BeanProperty;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.jsonFormatVisitors.JsonAnyFormatVisitor;
import tools.jackson.databind.jsonFormatVisitors.JsonArrayFormatVisitor;
import tools.jackson.databind.jsonFormatVisitors.JsonBooleanFormatVisitor;
import tools.jackson.databind.jsonFormatVisitors.JsonFormatVisitable;
import tools.jackson.databind.jsonFormatVisitors.JsonFormatVisitorWrapper;
import tools.jackson.databind.jsonFormatVisitors.JsonIntegerFormatVisitor;
import tools.jackson.databind.jsonFormatVisitors.JsonMapFormatVisitor;
import tools.jackson.databind.jsonFormatVisitors.JsonNullFormatVisitor;
import tools.jackson.databind.jsonFormatVisitors.JsonNumberFormatVisitor;
import tools.jackson.databind.jsonFormatVisitors.JsonObjectFormatVisitor;
import tools.jackson.databind.jsonFormatVisitors.JsonStringFormatVisitor;
import xyz.avasilev.taskcomm.PayloadShape;
import xyz.avasilev.taskcomm.PayloadShape.Kind;

/**
 * Обход класса запроса маппером коммуникаций (docs/adr/0160-payload-shape.md): то, что маппер сам
 * говорит о форме значения, — с его стратегией имён и аннотациями. У задач — свой такой же обход:
 * подсистемы не делят код (CLAUDE.md, «Доменный словарь»).
 *
 * <p>Тип, который уже встретился выше по пути, и путь глубже {@link #MAX_DEPTH} — {@link Kind#ANY}:
 * у рекурсивной структуры нет конечного шаблона.
 */
final class PayloadShapeWalker {

    /** Глубина, дальше которой структура не раскрывается. */
    private static final int MAX_DEPTH = 12;

    private final JsonMapper mapper;

    PayloadShapeWalker(final JsonMapper mapper) {
        this.mapper = mapper;
    }

    PayloadShape shape(final JavaType type) {
        return this.walk(type, new ArrayDeque<>());
    }

    private PayloadShape walk(final JavaType type, final Deque<JavaType> path) {
        if (unknowable(type, path)) {
            return PayloadShape.of(Kind.ANY);
        }
        path.push(type);
        try {
            final Collector collector = new Collector(path);
            this.mapper.acceptJsonFormatVisitor(type, collector);
            return collector.result();
        } catch (final JacksonException | IllegalArgumentException unknown) {
            // Маппер не умеет описать тип — структура неизвестна, это не ошибка чтения.
            return PayloadShape.of(Kind.ANY);
        } finally {
            path.pop();
        }
    }

    /**
     * Структуру не раскрыть: тип пуст, повторяется выше по пути или путь слишком глубок. Object
     * и JsonNode маппер описывает пустым объектом, но ключей у них нет — это «что угодно».
     */
    private static boolean unknowable(final JavaType type, final Deque<JavaType> path) {
        return type == null || opaque(type) || path.size() >= MAX_DEPTH || path.contains(type);
    }

    private static boolean opaque(final JavaType type) {
        return type.isJavaLangObject() || JsonNode.class.isAssignableFrom(type.getRawClass());
    }

    /** Принимает от маппера вид одного значения. */
    private final class Collector extends JsonFormatVisitorWrapper.Base {

        private final Deque<JavaType> path;

        private Kind kind = Kind.ANY;

        private final Map<String, PayloadShape> properties = new LinkedHashMap<>();

        private PayloadShape items;

        private final List<String> values = new ArrayList<>();

        Collector(final Deque<JavaType> path) {
            this.path = path;
        }

        PayloadShape result() {
            return new PayloadShape(this.kind, this.properties, this.items, this.values);
        }

        @Override
        public JsonObjectFormatVisitor expectObjectFormat(final JavaType type) {
            this.kind = Kind.OBJECT;
            return new Properties(this);
        }

        @Override
        public JsonArrayFormatVisitor expectArrayFormat(final JavaType type) {
            this.kind = Kind.ARRAY;
            this.items = PayloadShapeWalker.this.walk(type.getContentType(), this.path);
            return new JsonArrayFormatVisitor.Base(this.getContext());
        }

        @Override
        public JsonMapFormatVisitor expectMapFormat(final JavaType type) {
            this.kind = Kind.MAP;
            this.items = PayloadShapeWalker.this.walk(type.getContentType(), this.path);
            return new JsonMapFormatVisitor.Base(this.getContext());
        }

        @Override
        public JsonStringFormatVisitor expectStringFormat(final JavaType type) {
            this.kind = Kind.STRING;
            return new JsonStringFormatVisitor.Base() {
                @Override
                public void enumTypes(final Set<String> enums) {
                    Collector.this.values.addAll(enums);
                }
            };
        }

        @Override
        public JsonIntegerFormatVisitor expectIntegerFormat(final JavaType type) {
            this.kind = Kind.INTEGER;
            return new JsonIntegerFormatVisitor.Base();
        }

        @Override
        public JsonNumberFormatVisitor expectNumberFormat(final JavaType type) {
            this.kind = Kind.NUMBER;
            return new JsonNumberFormatVisitor.Base();
        }

        @Override
        public JsonBooleanFormatVisitor expectBooleanFormat(final JavaType type) {
            this.kind = Kind.BOOLEAN;
            return new JsonBooleanFormatVisitor.Base();
        }

        @Override
        public JsonNullFormatVisitor expectNullFormat(final JavaType type) {
            this.kind = Kind.ANY;
            return new JsonNullFormatVisitor.Base();
        }

        @Override
        public JsonAnyFormatVisitor expectAnyFormat(final JavaType type) {
            this.kind = Kind.ANY;
            return new JsonAnyFormatVisitor.Base();
        }

        private void property(final String name, final JavaType type) {
            this.properties.put(name, PayloadShapeWalker.this.walk(type, this.path));
        }
    }

    /** Ключи объекта: каждое свойство, обязательное или нет, — в структуру его значения. */
    private static final class Properties extends JsonObjectFormatVisitor.Base {

        private final Collector owner;

        Properties(final Collector owner) {
            super(owner.getContext());
            this.owner = owner;
        }

        @Override
        public void property(final BeanProperty property) {
            this.owner.property(property.getName(), property.getType());
        }

        @Override
        public void property(final String name, final JsonFormatVisitable handler, final JavaType type) {
            this.owner.property(name, type);
        }

        @Override
        public void optionalProperty(final BeanProperty property) {
            this.owner.property(property.getName(), property.getType());
        }

        @Override
        public void optionalProperty(final String name, final JsonFormatVisitable handler, final JavaType type) {
            this.owner.property(name, type);
        }
    }
}
