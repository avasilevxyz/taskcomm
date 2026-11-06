package xyz.avasilev.taskcomm.rest;

import java.util.List;
import java.util.Map;
import java.util.Set;
import xyz.avasilev.taskcomm.execution.api.ExecutionRef;
import xyz.avasilev.taskcomm.task.api.TaskSubmitOptions;
import xyz.avasilev.taskcomm.task.api.inspection.TaskQuery;
import xyz.avasilev.taskcomm.task.api.inspection.TaskSnapshot;

/** Условия выдачи и опции постановки задач из параметров запроса. */
final class TaskRequests {

    /** Префикс параметров поиска по нагрузке: {@code field.orderId=A-1}. */
    static final String FIELD = "field.";

    private TaskRequests() {}

    /**
     * Условия выдачи: {@code type}, {@code status}, {@code parentId}, дело, поля {@code field.<имя>}.
     *
     * @param level уровень
     * @param params все параметры
     * @param status статусы или {@code null}
     * @return условия
     */
    static TaskQuery query(
            final TaskSnapshot.Level level, final Map<String, String> params, final List<String> status) {
        final RestParams p = new RestParams(params);
        TaskQuery query = TaskQuery.of(level);
        if (p.text("type") != null) {
            query = query.withType(p.text("type"));
        }
        if (status != null) {
            query = query.withStatuses(Set.copyOf(status));
        }
        if (p.uuid("parentId") != null) {
            query = query.withParent(p.uuid("parentId"));
        }
        final ExecutionRef execution = p.execution();
        if (execution != null) {
            query = query.inExecution(execution);
        }
        for (final Map.Entry<String, String> field : params.entrySet()) {
            if (field.getKey().startsWith(FIELD)) {
                query = query.withField(field.getKey().substring(FIELD.length()), field.getValue());
            }
        }
        return query;
    }

    /**
     * Опции постановки: дело, {@code priority}, {@code notBefore}.
     *
     * @param params все параметры
     * @return опции
     */
    static TaskSubmitOptions options(final Map<String, String> params) {
        final RestParams p = new RestParams(params);
        TaskSubmitOptions options = TaskSubmitOptions.defaults();
        final ExecutionRef execution = p.execution();
        if (execution != null) {
            options = options.inExecution(execution);
        }
        if (p.text("priority") != null) {
            options = options.priority(Integer.parseInt(p.text("priority")));
        }
        if (p.instant("notBefore") != null) {
            options = options.notBefore(p.instant("notBefore"));
        }
        return options;
    }
}
