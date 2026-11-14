package xyz.avasilev.taskcomm.task.internal.inspection;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.util.function.SingletonSupplier;
import xyz.avasilev.taskcomm.PayloadShape;
import xyz.avasilev.taskcomm.SqlQuery;
import xyz.avasilev.taskcomm.execution.api.ExecutionRef;
import xyz.avasilev.taskcomm.execution.api.HistoryWindow;
import xyz.avasilev.taskcomm.execution.api.Page;
import xyz.avasilev.taskcomm.execution.api.PageRequest;
import xyz.avasilev.taskcomm.task.api.inspection.SearchableFieldSnapshot;
import xyz.avasilev.taskcomm.task.api.inspection.TaskAttemptSnapshot;
import xyz.avasilev.taskcomm.task.api.inspection.TaskInspection;
import xyz.avasilev.taskcomm.task.api.inspection.TaskQuery;
import xyz.avasilev.taskcomm.task.api.inspection.TaskSnapshot;
import xyz.avasilev.taskcomm.task.api.inspection.TaskTypeSummary;
import xyz.avasilev.taskcomm.task.api.inspection.TaskUnitCount;
import xyz.avasilev.taskcomm.task.api.processor.FieldType;
import xyz.avasilev.taskcomm.task.internal.TaskLevel;
import xyz.avasilev.taskcomm.task.internal.TaskPayloadCodec;
import xyz.avasilev.taskcomm.task.internal.TaskSql;
import xyz.avasilev.taskcomm.task.internal.processor.TaskProcessorRegistry;
import xyz.avasilev.taskcomm.task.internal.settings.TaskSettings;

/**
 * Реализация {@link TaskInspection} (docs/adr/0138-inspection-api.md): списки по ключу
 * {@code (execution_created_at, created_at, id)} от новых к старым, в окне чтения истории или в партиции
 * дела, поиск по реестру полей, журнал попыток с результатами, сводка по типам.
 */
public class DefaultTaskInspection implements TaskInspection {

    private static final String TASKS = """
            SELECT e.id, e.execution_id, e.execution_created_at, e.task_type, NULL::uuid AS task_id,
                   NULL::uuid AS batch_id, e.status, e.status_description, e.phase, e.priority, e.created_at,
                   e.updated_at, e.origin_type, e.origin_id, e.origin_attempt_id, e.origin_relation,
                   e.payload::text AS payload
              FROM tc_task e
            """;

    private static final String BATCHES = """
            SELECT e.id, e.execution_id, e.execution_created_at, e.task_type, e.task_id, NULL::uuid AS batch_id,
                   e.status, e.status_description, e.phase, e.priority, e.created_at, e.updated_at,
                   NULL::text AS origin_type, NULL::uuid AS origin_id, NULL::uuid AS origin_attempt_id,
                   NULL::text AS origin_relation, e.payload::text AS payload
              FROM tc_task_batch e
            """;

    private static final String UNITS = """
            SELECT e.id, e.execution_id, e.execution_created_at, e.task_type, e.task_id, e.batch_id,
                   e.status, e.status_description, 'PROCESS' AS phase, e.priority, e.created_at, e.updated_at,
                   NULL::text AS origin_type, NULL::uuid AS origin_id, NULL::uuid AS origin_attempt_id,
                   NULL::text AS origin_relation, e.payload::text AS payload
              FROM tc_task_unit e
            """;

    private static final String ATTEMPTS = """
            SELECT a.id, a.phase, a.status, a.status_description, a.started_at, a.finished_at, a.locked_by,
                   a.lease_renewals, r.result::text AS result
              FROM tc_task_attempt a
              LEFT JOIN tc_task_result r ON r.execution_created_at = a.execution_created_at AND r.attempt_id = a.id
             WHERE a.execution_created_at = :executionCreatedAt AND a.level = :level AND a.target_id = :id
             ORDER BY a.started_at, a.id
            """;

    private static final String FIELDS = """
            SELECT level, task_type, field_name, field_type, json_path, field_title, field_description,
                   last_declared_at
              FROM tc_searchable_field
             ORDER BY level, task_type, field_name, json_path
            """;

    private static final String UNIT_COUNTS = """
            SELECT e.task_id, e.batch_id, e.status, count(*) AS n
              FROM tc_task_unit e
             WHERE e.execution_created_at = :executionCreatedAt AND e.execution_id = :executionId
             GROUP BY e.task_id, e.batch_id, e.status
             ORDER BY e.task_id, e.batch_id NULLS FIRST, e.status
            """;

    private final JdbcClient jdbc;

    private final Supplier<TaskProcessorRegistry> registry;

    private final Supplier<TaskSettings> settings;

    private final TaskPayloadCodec codec;

    /**
     * Собирает чтение.
     *
     * @param dataSource {@code DataSource} стартера
     * @param registry реестр процессоров — лениво; по нему — признак осиротевшего типа
     * @param settings итоговые настройки — лениво; по ним — приоритет по умолчанию
     * @param codec кодек нагрузок — его маппером описывается структура нагрузки
     */
    public DefaultTaskInspection(
            final DataSource dataSource,
            final Supplier<TaskProcessorRegistry> registry,
            final Supplier<TaskSettings> settings,
            final TaskPayloadCodec codec) {
        this.jdbc = JdbcClient.create(dataSource);
        this.registry = SingletonSupplier.of(registry);
        this.settings = SingletonSupplier.of(settings);
        this.codec = codec;
    }

    @Override
    public Page<TaskSnapshot> list(final TaskQuery query, final HistoryWindow window, final PageRequest page) {
        // Строкой больше страницы: по ней список узнаёт, есть ли следующая.
        final SqlQuery statement = this.listStatement(query, window, page, page.size() + 1);
        final Set<String> known = this.registry.get().types();
        final List<TaskSnapshot> rows = this.jdbc
                .sql(statement.sql())
                .params(statement.parameters())
                .query((rs, rowNum) ->
                        TaskSnapshots.entity(rs, query.level(), !known.contains(rs.getString("task_type"))))
                .list();
        if (rows.size() <= page.size()) {
            return Page.of(rows, null, window);
        }
        final TaskSnapshot last = rows.get(page.size() - 1);
        return Page.of(
                rows.subList(0, page.size()),
                new TaskCursor(last.executionRef().createdAt(), last.createdAt(), last.id()).encode(),
                window);
    }

    @Override
    public SqlQuery listSql(final TaskQuery query, final HistoryWindow window, final PageRequest page) {
        return this.listStatement(query, window, page, page.size());
    }

    /** Запрос страницы — один у чтения и у его SQL для разбора. */
    private SqlQuery listStatement(
            final TaskQuery query, final HistoryWindow window, final PageRequest page, final int limit) {
        final List<String> where = new ArrayList<>();
        final Map<String, Object> params = new LinkedHashMap<>();
        this.conditions(query, window, where, params);
        page.cursor().map(TaskCursor::decode).ifPresent(cursor -> {
            where.add("(e.execution_created_at, e.created_at, e.id) < (:cursorExecution, :cursorCreated, :cursorId)");
            params.put("cursorExecution", TaskSql.parameter(cursor.executionCreatedAt()));
            params.put("cursorCreated", TaskSql.parameter(cursor.createdAt()));
            params.put("cursorId", cursor.id());
        });
        params.put("limit", limit);
        return new SqlQuery(
                select(query.level()) + where(where)
                        + "\n ORDER BY e.execution_created_at DESC, e.created_at DESC, e.id DESC\n LIMIT :limit",
                params);
    }

    @Override
    public long count(final TaskQuery query, final HistoryWindow window) {
        final List<String> where = new ArrayList<>();
        final Map<String, Object> params = new LinkedHashMap<>();
        this.conditions(query, window, where, params);
        final Long count = this.jdbc
                .sql("SELECT count(*) FROM "
                        + TaskLevel.valueOf(query.level().name()).table() + " e"
                        + (where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where)))
                .params(params)
                .query(Long.class)
                .single();
        return count == null ? 0 : count;
    }

    /** Условия выдачи — общие у списка и счёта: дело или окно, тип, статусы, родитель, поиск. */
    private void conditions(
            final TaskQuery query,
            final HistoryWindow window,
            final List<String> where,
            final Map<String, Object> params) {
        query.execution()
                .ifPresentOrElse(
                        execution -> {
                            where.add("e.execution_created_at = :executionCreatedAt AND e.execution_id = :executionId");
                            params.put("executionCreatedAt", TaskSql.parameter(execution.createdAt()));
                            params.put("executionId", execution.id());
                        },
                        () -> window(window, where, params));
        query.taskType().ifPresent(type -> {
            where.add("e.task_type = :taskType");
            params.put("taskType", type);
        });
        if (!query.statuses().isEmpty()) {
            where.add("e.status IN (:statuses)");
            params.put("statuses", List.copyOf(query.statuses()));
        }
        query.parentId().ifPresent(parent -> {
            where.add(
                    query.level() == TaskSnapshot.Level.UNIT
                            ? "(e.task_id = :parent OR e.batch_id = :parent)"
                            : "e.task_id = :parent");
            params.put("parent", parent);
        });
        query.parentAttemptId().ifPresent(attempt -> {
            where.add(parentAttempt(query.level()));
            params.put("parentAttempt", attempt);
        });
        final PayloadSearch.Condition search = PayloadSearch.condition(this.jdbc, query);
        if (search != null) {
            where.add(search.sql());
            params.putAll(search.params());
        }
    }

    /**
     * Дети попытки раскрытия (docs/adr/0157-attempt-children-through-batches.md): у групп — прямые,
     * у подзадач — прямые и подзадачи групп этой попытки. У задач попытки-родителя нет — выдача пуста.
     */
    private static String parentAttempt(final TaskSnapshot.Level level) {
        return switch (level) {
            case TASK -> "FALSE";
            case BATCH -> "e.parent_attempt_id = :parentAttempt";
            case UNIT -> """
                (e.parent_attempt_id = :parentAttempt OR e.batch_id IN (
                    SELECT b.id FROM tc_task_batch b
                     WHERE b.execution_created_at = e.execution_created_at AND b.parent_attempt_id = :parentAttempt))                """;
        };
    }

    @Override
    public Optional<TaskSnapshot> find(final TaskSnapshot.Level level, final UUID id, final HistoryWindow window) {
        final List<String> where = new ArrayList<>(List.of("e.id = :id"));
        final Map<String, Object> params = new LinkedHashMap<>(Map.of("id", id));
        window(window, where, params);
        final Set<String> known = this.registry.get().types();
        return this.jdbc
                .sql(select(level) + " WHERE " + String.join(" AND ", where))
                .params(params)
                .query((rs, rowNum) -> TaskSnapshots.entity(rs, level, !known.contains(rs.getString("task_type"))))
                .optional();
    }

    @Override
    public List<TaskAttemptSnapshot> attempts(final TaskSnapshot entity) {
        final SqlQuery statement = this.attemptsSql(entity);
        return this.jdbc
                .sql(statement.sql())
                .params(statement.parameters())
                .query((rs, rowNum) -> TaskSnapshots.attempt(rs))
                .list();
    }

    @Override
    public SqlQuery attemptsSql(final TaskSnapshot entity) {
        final Map<String, Object> params = new LinkedHashMap<>();
        params.put("executionCreatedAt", TaskSql.parameter(entity.executionRef().createdAt()));
        params.put("level", entity.level().name());
        params.put("id", entity.id());
        return new SqlQuery(ATTEMPTS, params);
    }

    @Override
    public List<SearchableFieldSnapshot> searchableFields() {
        return this.jdbc
                .sql(FIELDS)
                .query((rs, rowNum) -> new SearchableFieldSnapshot(
                        TaskSnapshot.Level.valueOf(rs.getString("level")),
                        rs.getString("task_type"),
                        rs.getString("field_name"),
                        FieldType.valueOf(rs.getString("field_type")),
                        rs.getString("json_path"),
                        rs.getString("field_title"),
                        rs.getString("field_description"),
                        rs.getObject("last_declared_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    @Override
    public List<TaskTypeSummary> summary(final HistoryWindow window) {
        final Set<String> known = this.registry.get().types();
        final List<TaskTypeSummary> result = new ArrayList<>();
        for (final TaskLevel level : TaskLevel.values()) {
            final List<String> where = new ArrayList<>();
            final Map<String, Object> params = new LinkedHashMap<>();
            window(window, where, params);
            result.addAll(this.jdbc
                    .sql("SELECT e.task_type, e.status, count(*) AS n FROM " + level.table() + " e"
                            + (where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where))
                            + " GROUP BY e.task_type, e.status ORDER BY e.task_type, e.status")
                    .params(params)
                    .query((rs, rowNum) -> new TaskTypeSummary(
                            TaskSnapshot.Level.valueOf(level.name()),
                            rs.getString("task_type"),
                            rs.getString("status"),
                            rs.getLong("n"),
                            !known.contains(rs.getString("task_type"))))
                    .list());
        }
        return result;
    }

    @Override
    public SqlQuery unitCountsSql(final ExecutionRef execution) {
        final Map<String, Object> params = new LinkedHashMap<>();
        params.put("executionCreatedAt", TaskSql.parameter(execution.createdAt()));
        params.put("executionId", execution.id());
        return new SqlQuery(UNIT_COUNTS, params);
    }

    @Override
    public List<TaskUnitCount> unitCounts(final ExecutionRef execution) {
        final SqlQuery statement = this.unitCountsSql(execution);
        return this.jdbc
                .sql(statement.sql())
                .params(statement.parameters())
                .query((rs, rowNum) -> new TaskUnitCount(
                        rs.getObject("task_id", UUID.class),
                        rs.getObject("batch_id", UUID.class),
                        rs.getString("status"),
                        rs.getLong("n")))
                .list();
    }

    @Override
    public Map<String, Integer> defaultPriorities() {
        final Map<String, Integer> priorities = new TreeMap<>();
        for (final String type : this.registry.get().types()) {
            priorities.put(type, this.settings.get().priority(type));
        }
        return Collections.unmodifiableMap(priorities);
    }

    @Override
    public Map<String, String> pollers() {
        final Map<String, String> pollers = new TreeMap<>();
        for (final String type : this.registry.get().types()) {
            pollers.put(type, this.settings.get().pollers().pollerOf(type).name());
        }
        return Collections.unmodifiableMap(pollers);
    }

    @Override
    public Optional<PayloadShape> payloadShape(final String taskType) {
        return this.registry
                .get()
                .find(taskType)
                .map(processor -> this.codec.shape(processor.types().task()));
    }

    private static String select(final TaskSnapshot.Level level) {
        return switch (level) {
            case TASK -> TASKS;
            case BATCH -> BATCHES;
            case UNIT -> UNITS;
        };
    }

    /** Условия построчно: запрос читается и в панели интерфейса. */
    private static String where(final List<String> where) {
        return where.isEmpty() ? "" : " WHERE " + String.join("\n   AND ", where);
    }

    private static void window(final HistoryWindow window, final List<String> where, final Map<String, Object> params) {
        window.from().ifPresent(from -> {
            where.add("e.execution_created_at >= :windowFrom");
            params.put("windowFrom", TaskSql.parameter(from));
        });
        window.to().ifPresent(to -> {
            where.add("e.execution_created_at < :windowTo");
            params.put("windowTo", TaskSql.parameter(to));
        });
    }
}
