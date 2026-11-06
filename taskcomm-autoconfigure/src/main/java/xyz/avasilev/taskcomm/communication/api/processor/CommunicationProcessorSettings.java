package xyz.avasilev.taskcomm.communication.api.processor;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Настройки процессора коммуникаций по умолчанию — от автора, который знает свою внешнюю систему
 * (docs/adr/0061-processor-configuration-annotations.md, docs/adr/0066-processor-settings-annotation-names.md).
 * Своя у подсистемы: {@code @TaskProcessorSettings} на процессоре коммуникаций — падение при старте.
 *
 * <p>Вешается на класс процессора и на его методы {@code process} и {@code handleResponse}; метод
 * перекрывает класс. <b>Любое значение из {@code application.yml} сильнее любой аннотации</b>;
 * итог по каждой системе и методу печатается при старте. Настройки системы — приоритет, срок ожидания
 * ответа, отказные статусы, исключения размыкателя — только на классе: на методе это падение при
 * старте.
 *
 * <p>Незаданный атрибут — ноль, пустая строка или пустой массив. Длительности — в формате
 * {@code Duration} Spring Boot: {@code "30s"}, {@code "2h"}.
 *
 * <pre>{@code
 * @CommunicationProcessorSettings(maxAttempts = 1)   // принимается синхронно
 * public class OrderInboundProcessor implements RestCommunicationProcessor<Order, Ack, Void> { ... }
 * }</pre>
 */
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CommunicationProcessorSettings {

    /**
     * Приоритет коммуникаций системы по умолчанию. Шкала по возрастанию: <b>меньше значит важнее</b>.
     * Только на классе.
     *
     * @return приоритет; {@code 0} — не задан
     */
    int priority() default 0;

    /**
     * Длительность аренды.
     *
     * @return длительность; пусто — не задана
     */
    String leaseDuration() default "";

    /**
     * Интервал heartbeat — заметно меньше аренды, порядка трети.
     *
     * @return интервал; пусто — не задан
     */
    String heartbeatInterval() default "";

    /**
     * Предел обработки одной попытки: дальше аренда не продлевается и попытка закрывается неуспехом.
     * Поток процессора при этом не прерывается — таймаут клиента внешней системы обязан быть короче.
     *
     * @return предел; пусто — не задан
     */
    String maxExecutionTime() default "";

    /**
     * Лимит попыток в цикле повторов, не меньше единицы. Системе, которую принимают синхронно,
     * задайте единицу (docs/adr/0133-synchronous-inbound-reception.md).
     *
     * @return лимит; {@code 0} — не задан
     */
    int maxAttempts() default 0;

    /**
     * Первая задержка backoff.
     *
     * @return задержка; пусто — не задана
     */
    String initialDelay() default "";

    /**
     * Множитель backoff, не меньше единицы.
     *
     * @return множитель; {@code 0} — не задан
     */
    double multiplier() default 0;

    /**
     * Верхняя граница задержки backoff.
     *
     * @return граница; пусто — не задана
     */
    String maxDelay() default "";

    /**
     * Доля джиттера в {@code (0, 1]}. Джиттер обязателен.
     *
     * @return доля; {@code 0} — не задана
     */
    double jitter() default 0;

    /**
     * Срок ожидания асинхронного ответа; истёк — повторная отправка
     * (docs/adr/0086-async-response-in-outbound.md). Только на классе.
     *
     * @return срок; пусто — не задан
     */
    String responseTimeout() default "";

    /**
     * Отказные статусы ответа: записанный ответ с таким статусом делает попытку неуспешной
     * (docs/adr/0065-communication-circuit-breaker.md). Значения — код ({@code "429"}) или класс
     * ({@code "5xx"}). Пустой массив — умолчание транспорта: {@code 5xx} и {@code 429} у REST и SOAP,
     * ничего у RAW; выключить список у REST можно только в {@code yaml}. Только на классе.
     *
     * @return отказные статусы; пусто — не заданы
     */
    String[] failureStatuses() default {};

    /**
     * Лимит отправок исходящих в секунду на экземпляр приложения: три экземпляра с лимитом 10 дают
     * внешней системе 30 (docs/adr/0011-communication-model.md). Дробный — реже раза в секунду. Только
     * на классе.
     *
     * @return лимит; {@code 0} — не ограничено
     */
    double rateLimitPerInstance() default 0;

    /**
     * Исключения, которые считает размыкатель. Исключение вне списка всё равно делает попытку
     * неуспешной — список сужает только размыкатель. Только на классе.
     *
     * @return считающиеся исключения; пусто — все
     */
    Class<? extends Throwable>[] circuitBreakerExceptions() default {};
}
