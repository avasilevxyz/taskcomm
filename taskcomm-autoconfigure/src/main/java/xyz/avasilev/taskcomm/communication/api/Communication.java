package xyz.avasilev.taskcomm.communication.api;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import xyz.avasilev.taskcomm.execution.api.ExecutionRef;
import xyz.avasilev.taskcomm.execution.api.Origin;

/**
 * Коммуникация с внешней системой. <b>Атомарна</b>: на части не делится, повтор применяется к ней
 * целиком (docs/adr/0011-communication-model.md).
 *
 * <p>Направление — тип, а не значение поля: коммуникация либо {@link Inbound}, либо {@link Outbound},
 * и процессор разбирает её через pattern matching без ветки {@code default}. Интерфейсы, а не записи:
 * новое поле появляется новым методом и не ломает совместимость
 * (docs/adr/0111-task-public-api-shape.md). Реализует их стартер; значения — снимок на момент
 * чтения.
 *
 * <pre>{@code
 * String system = switch (communication) {
 *     case Communication.Inbound<Order> inbound -> inbound.source();
 *     case Communication.Outbound<Order> outbound -> outbound.destination();
 * };
 * }</pre>
 *
 * @param <C> доменная форма запроса
 */
public sealed interface Communication<C> permits Communication.Inbound, Communication.Outbound {

    /**
     * Идентификатор коммуникации.
     *
     * @return идентификатор, UUIDv7
     */
    UUID id();

    /**
     * Дело, которому принадлежит коммуникация.
     *
     * @return ссылка на дело
     */
    ExecutionRef executionRef();

    /**
     * Доменная форма запроса: у входящей — что прислали нам, у исходящей — что отправляем мы
     * (docs/adr/0055-communication-exchange-storage.md).
     *
     * @return нагрузка, восстановленная в тип из дженерика процессора; {@code null} у входящей,
     *     тело которой не разобралось
     */
    C requestPayload();

    /**
     * Приоритет. Шкала по возрастанию: <b>меньше значит важнее</b>.
     *
     * @return приоритет, больше нуля
     */
    int priority();

    /**
     * Статус на момент чтения.
     *
     * @return статус коммуникации
     */
    CommunicationStatus status();

    /**
     * Причина терминального статуса — для {@code SKIPPED}, {@code CANCELLED} и неуспеха.
     *
     * @return причина или пусто
     */
    Optional<String> statusDescription();

    /**
     * Время создания по часам базы.
     *
     * @return время создания
     */
    Instant createdAt();

    /**
     * Кем создана коммуникация внутри дела (docs/adr/0064-entity-origin-links.md). Пусто
     * у созданной кодом потребителя вне процессора.
     *
     * @return указатель на создателя или пусто
     */
    Optional<Origin> origin();

    /**
     * Входящая: внешняя система прислала сообщение нам.
     *
     * @param <C> доменная форма запроса
     */
    non-sealed interface Inbound<C> extends Communication<C> {

        /**
         * Внешняя система-отправитель — по ней ищется процессор.
         *
         * @return имя внешней системы
         */
        String source();
    }

    /**
     * Исходящая: мы отправляем сообщение внешней системе.
     *
     * @param <C> доменная форма запроса
     */
    non-sealed interface Outbound<C> extends Communication<C> {

        /**
         * Внешняя система-получатель — по ней ищется процессор.
         *
         * @return имя внешней системы
         */
        String destination();
    }
}
