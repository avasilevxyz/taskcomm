package xyz.avasilev.taskcomm.task.api.inspection;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import xyz.avasilev.taskcomm.execution.api.ExecutionRef;
import xyz.avasilev.taskcomm.execution.api.Origin;
import xyz.avasilev.taskcomm.task.api.Task;
import xyz.avasilev.taskcomm.task.api.TaskBatch;
import xyz.avasilev.taskcomm.task.api.TaskBatchStatus;
import xyz.avasilev.taskcomm.task.api.TaskService;
import xyz.avasilev.taskcomm.task.api.TaskStatus;
import xyz.avasilev.taskcomm.task.api.TaskUnit;
import xyz.avasilev.taskcomm.task.api.TaskUnitStatus;

/**
 * Снимок задачи, группы или подзадачи для разбора и выдачи (docs/adr/0138-inspection-api.md).
 * Нагрузка — сырым JSON, как она лежит в базе: снимок читается и у типа без процессора, где разобрать
 * её не во что. Для работы с доменной формой — {@link Task}, {@link TaskBatch}, {@link TaskUnit}
 * из {@link TaskService}.
 *
 * <p>Интерфейс, а не запись: новое поле появляется новым методом и не ломает совместимость
 * (docs/adr/0111-task-public-api-shape.md). Реализует его стартер.
 */
public interface TaskSnapshot {

    /**
     * Уровень сущности.
     *
     * @return задача, группа или подзадача
     */
    Level level();

    /**
     * Идентификатор сущности.
     *
     * @return идентификатор
     */
    UUID id();

    /**
     * Дело сущности.
     *
     * @return ссылка на дело
     */
    ExecutionRef executionRef();

    /**
     * Тип задачи.
     *
     * @return тип
     */
    String taskType();

    /**
     * Задача группы или подзадачи.
     *
     * @return задача или пусто у самой задачи
     */
    Optional<UUID> taskId();

    /**
     * Группа подзадачи.
     *
     * @return группа или пусто у задачи, группы и подзадачи без групп
     */
    Optional<UUID> batchId();

    /**
     * Статус — значение {@link TaskStatus}, {@link TaskBatchStatus} или {@link TaskUnitStatus}
     * по уровню.
     *
     * @return статус
     */
    String status();

    /**
     * Причина статуса.
     *
     * @return причина или пусто
     */
    Optional<String> statusDescription();

    /**
     * Фаза — вид текущей или следующей попытки: {@code PROCESS}, {@code EXPAND}, {@code COLLECT}.
     *
     * @return фаза или пусто у задачи до первого захвата
     */
    Optional<String> phase();

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
     * Кем создана задача внутри дела; у групп и подзадач — пусто.
     *
     * @return указатель на создателя или пусто
     */
    Optional<Origin> origin();

    /**
     * Нагрузка сырым JSON.
     *
     * @return JSON
     */
    String payload();

    /**
     * Осиротела ли: у типа нет процессора, и сущность никто не захватывает
     * (docs/adr/0026-contract-shape-marking.md).
     *
     * @return {@code true}, если процессора типа нет
     */
    boolean orphan();

    /** Уровень сущности задач. */
    enum Level {

        /** Задача. */
        TASK,

        /** Группа. */
        BATCH,

        /** Подзадача. */
        UNIT
    }
}
