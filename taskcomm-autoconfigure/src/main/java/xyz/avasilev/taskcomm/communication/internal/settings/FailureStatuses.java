package xyz.avasilev.taskcomm.communication.internal.settings;

import java.util.List;
import xyz.avasilev.taskcomm.TaskcommConfigurationException;
import xyz.avasilev.taskcomm.communication.internal.ContractShape;

/**
 * Отказные статусы ответа внешней системы (docs/adr/0065-communication-circuit-breaker.md):
 * записанный ответ с таким статусом делает попытку неуспешной, даже если процессор вернулся нормально.
 * Значение — код ({@code 429}) или класс ({@code 5xx}).
 *
 * @param values значения, как заданы
 */
public record FailureStatuses(List<String> values) {

    /** Умолчание REST и SOAP: {@code 4xx} не всегда проблема системы. */
    static final FailureStatuses HTTP_DEFAULT = new FailureStatuses(List.of("5xx", "429"));

    /** Умолчание RAW и Kafka: у RAW смысл статуса знает только автор процессора, у Kafka статуса нет. */
    static final FailureStatuses NONE = new FailureStatuses(List.of());

    private static final String CODE = "[1-5]\\d\\d";

    private static final String CLASS = "[1-5]xx";

    /**
     * Проверяет значения.
     *
     * @throws TaskcommConfigurationException если значение — не код и не класс
     */
    public FailureStatuses {
        values = List.copyOf(values);
        for (final String value : values) {
            if (!value.matches(CODE) && !value.matches(CLASS)) {
                throw new TaskcommConfigurationException(
                        "failure-statuses: '" + value + "' — не код ответа " + "(«429») и не класс («5xx»).");
            }
        }
    }

    /**
     * Умолчание транспорта.
     *
     * @param shape форма контракта
     * @return отказные статусы по умолчанию
     */
    static FailureStatuses defaultFor(final ContractShape shape) {
        return shape.http() ? HTTP_DEFAULT : NONE;
    }

    /**
     * Отказной ли статус.
     *
     * @param status код ответа
     * @return {@code true}, если ответ с этим кодом — неуспех
     */
    public boolean failed(final int status) {
        final String code = String.valueOf(status);
        return this.values.stream().anyMatch(value -> value.equals(code) || sameClass(value, code));
    }

    private static boolean sameClass(final String value, final String code) {
        return value.endsWith("xx") && code.length() == 3 && code.charAt(0) == value.charAt(0);
    }
}
