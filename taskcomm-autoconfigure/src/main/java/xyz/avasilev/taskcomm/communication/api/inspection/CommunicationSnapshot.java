package xyz.avasilev.taskcomm.communication.api.inspection;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import xyz.avasilev.taskcomm.communication.api.Communication;
import xyz.avasilev.taskcomm.communication.api.CommunicationService;
import xyz.avasilev.taskcomm.communication.api.CommunicationStatus;
import xyz.avasilev.taskcomm.execution.api.ExecutionRef;
import xyz.avasilev.taskcomm.execution.api.Origin;

/**
 * Снимок коммуникации для разбора и выдачи (docs/adr/0138-inspection-api.md). Доменная форма запроса —
 * сырым JSON: снимок читается и у системы без процессора. Для работы с доменной формой —
 * {@link Communication} из {@link CommunicationService}.
 *
 * <p>Интерфейс, а не запись: новое поле появляется новым методом. Реализует его стартер.
 */
public interface CommunicationSnapshot {

    /**
     * Идентификатор.
     *
     * @return идентификатор
     */
    UUID id();

    /**
     * Дело.
     *
     * @return ссылка на дело
     */
    ExecutionRef executionRef();

    /**
     * Направление.
     *
     * @return входящая или исходящая
     */
    Direction direction();

    /**
     * Внешняя система — {@code source} входящей, {@code destination} исходящей.
     *
     * @return имя системы
     */
    String externalSystem();

    /**
     * Статус.
     *
     * @return статус
     */
    CommunicationStatus status();

    /**
     * Причина статуса.
     *
     * @return причина или пусто
     */
    Optional<String> statusDescription();

    /**
     * Фаза: {@code PROCESS} или {@code HANDLE_RESPONSE}.
     *
     * @return фаза
     */
    String phase();

    /**
     * Форма транспортного контракта: {@code REST}, {@code SOAP}, {@code KAFKA}, {@code RAW}.
     *
     * @return форма или пусто у исходящей до первого захвата
     */
    Optional<String> contractShape();

    /**
     * Приоритет; меньше значит важнее.
     *
     * @return приоритет
     */
    int priority();

    /**
     * Время создания по часам базы.
     *
     * @return время создания
     */
    Instant createdAt();

    /**
     * Время последнего изменения.
     *
     * @return время изменения
     */
    Instant updatedAt();

    /**
     * Кем создана внутри дела.
     *
     * @return указатель на создателя или пусто
     */
    Optional<Origin> origin();

    /**
     * Доменная форма запроса сырым JSON.
     *
     * @return JSON или пусто у входящей с неразобранным телом
     */
    Optional<String> requestPayload();

    /**
     * Осиротела ли: у системы нет процессора (docs/adr/0105-orphan-communications.md).
     *
     * @return {@code true}, если процессора системы нет
     */
    boolean orphan();

    /** Направление коммуникации. */
    enum Direction {

        /** Входящая. */
        INBOUND,

        /** Исходящая. */
        OUTBOUND
    }
}
