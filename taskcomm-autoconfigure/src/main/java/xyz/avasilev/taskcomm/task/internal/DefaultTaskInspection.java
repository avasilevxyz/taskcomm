package xyz.avasilev.taskcomm.task.internal;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.util.function.SingletonSupplier;
import xyz.avasilev.taskcomm.execution.api.HistoryWindow;
import xyz.avasilev.taskcomm.execution.api.Page;
import xyz.avasilev.taskcomm.execution.api.PageRequest;
import xyz.avasilev.taskcomm.task.api.FieldType;
import xyz.avasilev.taskcomm.task.api.SearchableFieldSnapshot;
import xyz.avasilev.taskcomm.task.api.TaskAttemptSnapshot;
import xyz.avasilev.taskcomm.task.api.TaskInspection;
import xyz.avasilev.taskcomm.task.api.TaskQuery;
import xyz.avasilev.taskcomm.task.api.TaskSnapshot;
import xyz.avasilev.taskcomm.task.api.TaskTypeSummary;

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

    private final JdbcClient jdbc;

    private final Supplier<TaskProcessorRegistry> registry;

    /**
     * Собирает чтение.
     *
     * @param dataSource {@code DataSource} стартера
     * @param registry реестр процессоров — лениво; по нему — признак осиротевшего типа
     */
    public DefaultTaskInspection(final DataSource dataSource, final Supplier<TaskProcessorRegistry> registry) {
        this.jdbc = JdbcClient.create(dataSource);
        this.registry = SingletonSupplier.of(registry);
    }

    @Override
    public Page<TaskSnapshot> list(final TaskQuery query, final HistoryWindow window, final PageRequest page) {
        final List<String> where = new ArrayList<>();
        final Map<String, Object> params = new LinkedHashMap<>();
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
        final PayloadSearch.Condition search = PayloadSearch.condition(this.jdbc, query);
        if (search != null) {
            where.add(search.sql());
            params.putAll(search.params());
        }
        page.cursor().map(TaskCursor::decode).ifPresent(cursor -> {
            where.add("(e.execution_created_at, e.created_at, e.id) < (:cursorExecution, :cursorCreated, :cursorId)");
            params.put("cursorExecution", TaskSql.parameter(cursor.executionCreatedAt()));
            params.put("cursorCreated", TaskSql.parameter(cursor.createdAt()));
            params.put("cursorId", cursor.id());
        });
        params.put("limit", page.size() + 1);
        final Set<String> known = this.registry.get().types();
        final List<TaskSnapshot> rows = this.jdbc
                .sql(select(query.level()) + (where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where))
                        + " ORDER BY e.execution_created_at DESC, e.created_at DESC, e.id DESC LIMIT :limit")
                .params(params)
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
        return this.jdbc
                .sql(ATTEMPTS)
                .param(
                        "executionCreatedAt",
                        TaskSql.parameter(entity.executionRef().createdAt()))
                .param("level", entity.level().name())
                .param("id", entity.id())
                .query((rs, rowNum) -> TaskSnapshots.attempt(rs))
                .list();
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

    private static String select(final TaskSnapshot.Level level) {
        return switch (level) {
            case TASK -> TASKS;
            case BATCH -> BATCHES;
            case UNIT -> UNITS;
        };
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
