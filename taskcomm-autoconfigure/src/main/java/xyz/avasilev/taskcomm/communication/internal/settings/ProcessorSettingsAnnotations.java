package xyz.avasilev.taskcomm.communication.internal.settings;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.annotation.MergedAnnotations.SearchStrategy;
import org.springframework.util.ReflectionUtils;
import xyz.avasilev.taskcomm.TaskcommConfigurationException;
import xyz.avasilev.taskcomm.communication.api.processor.CommunicationProcessorSettings;
import xyz.avasilev.taskcomm.communication.internal.CommunicationPhase;

/**
 * Чтение {@link CommunicationProcessorSettings} с процессора: с класса и с методов {@code process}
 * и {@code handleResponse} (docs/adr/0134-communication-public-api-shape.md). Несколько аннотаций
 * на одном месте складываются; один атрибут, заданный дважды, — падение при старте. Настройки
 * системы — приоритет, срок ожидания ответа, отказные статусы, исключения размыкателя — на методе
 * запрещены.
 *
 * <p>Аннотация настроек задач на процессоре коммуникаций — тоже падение (docs/adr/0066-processor-settings-annotation-names.md).
 * Ищется по имени: подсистема коммуникаций от задач не зависит.
 */
final class ProcessorSettingsAnnotations {

    /** Имя аннотации настроек задач — чужой подсистемы. */
    static final String FOREIGN = "xyz.avasilev.taskcomm.task.api.processor.TaskProcessorSettings";

    private static final List<String> SYSTEM_ONLY = List.of(
            "priority", "responseTimeout", "failureStatuses", "rateLimitPerInstance", "circuitBreakerExceptions");

    private ProcessorSettingsAnnotations() {}

    /**
     * Аннотации на классе процессора — уровень 4.
     *
     * @param processorClass класс процессора без прокси
     * @return заданные настройки
     * @throws TaskcommConfigurationException если атрибут задан дважды, длительность не разбирается или
     *     на процессоре аннотация настроек задач
     */
    static SettingsOverride onClass(final Class<?> processorClass) {
        return read(processorClass, "класс " + processorClass.getName()).toOverride();
    }

    /**
     * Аннотации на методе фазы — уровень 3.
     *
     * @param processorClass класс процессора без прокси
     * @param phase фаза — по ней выбирается метод
     * @return заданные настройки; метод не переопределён — ничего не задано
     * @throws TaskcommConfigurationException если на методе задана настройка системы
     */
    static SettingsOverride onMethod(final Class<?> processorClass, final CommunicationPhase phase) {
        final Method found = Arrays.stream(ReflectionUtils.getUniqueDeclaredMethods(processorClass))
                .filter(method -> method.getName().equals(phase.methodName()) && !method.isBridge())
                .filter(method -> !method.getDeclaringClass().isInterface())
                .findFirst()
                .orElse(null);
        if (found == null) {
            return SettingsOverride.NONE;
        }
        final String place = "метод " + processorClass.getName() + "." + phase.methodName();
        final Attributes attributes = read(found, place);
        for (final String attribute : SYSTEM_ONLY) {
            if (attributes.has(attribute)) {
                throw new TaskcommConfigurationException("Атрибут " + attribute + " на методе запрещён (" + place
                        + "): это настройка внешней системы, а не попытки. Перенесите его на класс процессора "
                        + "(docs/adr/0134-communication-public-api-shape.md).");
            }
        }
        return attributes.toOverride();
    }

    private static Attributes read(final AnnotatedElement element, final String place) {
        final MergedAnnotations annotations = MergedAnnotations.from(element, SearchStrategy.DIRECT);
        if (annotations.isPresent(FOREIGN)) {
            throw new TaskcommConfigurationException("@TaskProcessorSettings на процессоре коммуникаций (" + place
                    + "): у подсистемы своя аннотация — @CommunicationProcessorSettings "
                    + "(docs/adr/0066-processor-settings-annotation-names.md).");
        }
        final Attributes attributes = new Attributes(place);
        annotations.stream(CommunicationProcessorSettings.class).forEach(attributes::add);
        return attributes;
    }

    /** Заданные атрибуты всех аннотаций одного места с их источниками. */
    private static final class Attributes {

        private final String place;

        private final Map<String, Object> values = new HashMap<>();

        private final Map<String, String> sources = new HashMap<>();

        Attributes(final String place) {
            this.place = place;
        }

        void add(final MergedAnnotation<CommunicationProcessorSettings> annotation) {
            final String source = "@" + annotation.getRoot().getType().getSimpleName();
            this.put("priority", annotation.getInt("priority"), source);
            this.put("leaseDuration", annotation.getString("leaseDuration"), source);
            this.put("heartbeatInterval", annotation.getString("heartbeatInterval"), source);
            this.put("maxExecutionTime", annotation.getString("maxExecutionTime"), source);
            this.put("maxAttempts", annotation.getInt("maxAttempts"), source);
            this.put("initialDelay", annotation.getString("initialDelay"), source);
            this.put("multiplier", annotation.getDouble("multiplier"), source);
            this.put("maxDelay", annotation.getString("maxDelay"), source);
            this.put("jitter", annotation.getDouble("jitter"), source);
            this.put("responseTimeout", annotation.getString("responseTimeout"), source);
            this.put("failureStatuses", List.of(annotation.getStringArray("failureStatuses")), source);
            this.put("rateLimitPerInstance", annotation.getDouble("rateLimitPerInstance"), source);
            this.put("circuitBreakerExceptions", List.of(annotation.getClassArray("circuitBreakerExceptions")), source);
        }

        boolean has(final String attribute) {
            return this.values.containsKey(attribute);
        }

        @SuppressWarnings("unchecked")
        SettingsOverride toOverride() {
            return new SettingsOverride(
                    (Integer) this.values.get("priority"),
                    new LeaseOverride(
                            this.duration("leaseDuration"),
                            this.duration("heartbeatInterval"),
                            this.duration("maxExecutionTime")),
                    new RetryOverride(
                            (Integer) this.values.get("maxAttempts"),
                            this.duration("initialDelay"),
                            (Double) this.values.get("multiplier"),
                            this.duration("maxDelay"),
                            (Double) this.values.get("jitter")),
                    (List<String>) this.values.get("failureStatuses"),
                    this.duration("responseTimeout"),
                    (Double) this.values.get("rateLimitPerInstance"),
                    new BreakerOverride(null, this.exceptionNames()));
        }

        private void put(final String attribute, final Object value, final String source) {
            if (isUnset(value)) {
                return;
            }
            final String previous = this.sources.putIfAbsent(attribute, source);
            if (previous != null) {
                throw new TaskcommConfigurationException("Атрибут " + attribute + " задан дважды (" + this.place
                        + "): в " + previous + " и в " + source + ". Оставьте одно значение.");
            }
            this.values.put(attribute, value);
        }

        @SuppressWarnings("unchecked")
        private List<String> exceptionNames() {
            final List<Class<?>> classes = (List<Class<?>>) this.values.get("circuitBreakerExceptions");
            return classes == null ? null : classes.stream().map(Class::getName).toList();
        }

        private Duration duration(final String attribute) {
            final String text = (String) this.values.get(attribute);
            if (text == null) {
                return null;
            }
            try {
                return DurationStyle.detectAndParse(text);
            } catch (final IllegalArgumentException invalid) {
                throw new TaskcommConfigurationException(
                        "Атрибут " + attribute + " = \"" + text + "\" (" + this.place + ") — не длительность. "
                                + "Формат — как у Duration в Spring Boot: \"30s\", \"2h\".",
                        invalid);
            }
        }

        private static boolean isUnset(final Object value) {
            return switch (value) {
                case final Integer number -> number == 0;
                case final Double number -> number == 0;
                case final String text -> text.isEmpty();
                case final List<?> list -> list.isEmpty();
                default -> throw new IllegalStateException("Неожиданный атрибут " + value);
            };
        }
    }
}
