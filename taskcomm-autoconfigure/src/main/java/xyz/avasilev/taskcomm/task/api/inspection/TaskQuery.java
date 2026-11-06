package xyz.avasilev.taskcomm.task.api.inspection;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import xyz.avasilev.taskcomm.execution.api.ExecutionRef;

/**
 * Условия выдачи сущностей задач одного уровня: тип, статусы, дело, родитель и поля поиска
 * по нагрузке (docs/adr/0138-inspection-api.md). Неизменяемое значение; каждый метод {@code with…}
 * возвращает новое. Всё, кроме уровня, необязательно.
 *
 * <p>Поля поиска — только объявленные процессорами (docs/adr/0037-search-field-registry.md); тип
 * задачи не обязателен: ищется во всех типах, объявивших <b>все</b> указанные поля.
 *
 * <pre>{@code
 * inspection.list(TaskQuery.of(TaskSnapshot.Level.UNIT)
 *         .withStatuses(Set.of("FAILED"))
 *         .withField("orderId", "A-1"), window, PageRequest.first(50));
 * }</pre>
 */
public final class TaskQuery {

    private final TaskSnapshot.Level level;

    private final String taskType;

    private final Set<String> statuses;

    private final ExecutionRef execution;

    private final UUID parentId;

    private final Map<String, String> fields;

    private TaskQuery(
            final TaskSnapshot.Level level,
            final String taskType,
            final Set<String> statuses,
            final ExecutionRef execution,
            final UUID parentId,
            final Map<String, String> fields) {
        this.level = level;
        this.taskType = taskType;
        this.statuses = statuses;
        this.execution = execution;
        this.parentId = parentId;
        this.fields = fields;
    }

    /**
     * Все сущности уровня.
     *
     * @param level уровень
     * @return условия
     */
    public static TaskQuery of(final TaskSnapshot.Level level) {
        return new TaskQuery(Objects.requireNonNull(level, "level"), null, Set.of(), null, null, Map.of());
    }

    /**
     * Только этот тип задачи.
     *
     * @param value тип
     * @return новые условия
     */
    public TaskQuery withType(final String value) {
        return new TaskQuery(
                this.level,
                Objects.requireNonNull(value, "taskType"),
                this.statuses,
                this.execution,
                this.parentId,
                this.fields);
    }

    /**
     * Только эти статусы.
     *
     * @param values статусы — имена значений перечисления статусов уровня
     * @return новые условия
     */
    public TaskQuery withStatuses(final Set<String> values) {
        return new TaskQuery(this.level, this.taskType, Set.copyOf(values), this.execution, this.parentId, this.fields);
    }

    /**
     * Только сущности этого дела — чтение идёт в его партиции, окно не применяется.
     *
     * @param value дело
     * @return новые условия
     */
    public TaskQuery inExecution(final ExecutionRef value) {
        return new TaskQuery(
                this.level,
                this.taskType,
                this.statuses,
                Objects.requireNonNull(value, "execution"),
                this.parentId,
                this.fields);
    }

    /**
     * Только дети этого родителя: у групп и подзадач — задачи, у подзадач — и группы.
     *
     * @param value идентификатор задачи или группы
     * @return новые условия
     */
    public TaskQuery withParent(final UUID value) {
        return new TaskQuery(
                this.level,
                this.taskType,
                this.statuses,
                this.execution,
                Objects.requireNonNull(value, "parent"),
                this.fields);
    }

    /**
     * Поле нагрузки равно значению.
     *
     * @param name имя поля из реестра поиска
     * @param value значение
     * @return новые условия
     */
    public TaskQuery withField(final String name, final String value) {
        final Map<String, String> copy = new LinkedHashMap<>(this.fields);
        copy.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "value"));
        return new TaskQuery(
                this.level,
                this.taskType,
                this.statuses,
                this.execution,
                this.parentId,
                Collections.unmodifiableMap(copy));
    }

    /**
     * Уровень.
     *
     * @return уровень
     */
    public TaskSnapshot.Level level() {
        return this.level;
    }

    /**
     * Тип задачи.
     *
     * @return тип или пусто — все
     */
    public Optional<String> taskType() {
        return Optional.ofNullable(this.taskType);
    }

    /**
     * Статусы.
     *
     * @return статусы; пусто — все
     */
    public Set<String> statuses() {
        return this.statuses;
    }

    /**
     * Дело.
     *
     * @return дело или пусто
     */
    public Optional<ExecutionRef> execution() {
        return Optional.ofNullable(this.execution);
    }

    /**
     * Родитель.
     *
     * @return идентификатор родителя или пусто
     */
    public Optional<UUID> parentId() {
        return Optional.ofNullable(this.parentId);
    }

    /**
     * Поля поиска.
     *
     * @return имя поля → значение, в порядке добавления
     */
    public Map<String, String> fields() {
        return this.fields;
    }
}
