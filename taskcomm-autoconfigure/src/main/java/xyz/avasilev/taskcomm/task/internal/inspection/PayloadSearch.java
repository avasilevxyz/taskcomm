package xyz.avasilev.taskcomm.task.internal.inspection;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import xyz.avasilev.taskcomm.task.api.inspection.TaskQuery;
import xyz.avasilev.taskcomm.task.api.inspection.UnknownSearchFieldException;
import xyz.avasilev.taskcomm.task.api.processor.FieldType;

/**
 * Условие поиска по нагрузке из реестра {@code tc_searchable_field} (docs/adr/0037-search-field-registry.md,
 * docs/rules/rest-and-search.md). Типы выводятся по именам полей: ищется во всех типах уровня, объявивших
 * <b>все</b> указанные поля; имя разворачивается в {@code OR} по всем объявлениям этого типа; ветка, чей
 * тип значения не принимает переданное значение, отбрасывается.
 *
 * <p>Пользовательский ввод в SQL не попадает никогда: значения — параметры, а путь — из реестра, где
 * его грамматика проверена при объявлении (docs/adr/0127-search-field-sync.md). Строковое поле
 * однословного пути сравнивается выражением {@code payload ->> 'имя'} — его обслужит частичный индекс
 * из правил поиска.
 */
final class PayloadSearch {

    private static final String DECLARATIONS = """
            SELECT task_type, field_name, field_type, json_path FROM tc_searchable_field
             WHERE level = :level AND field_name IN (:names)
             ORDER BY task_type, field_name, json_path
            """;

    private static final Pattern DOT = Pattern.compile("\\.");

    private static final String KNOWN = "SELECT DISTINCT field_name FROM tc_searchable_field WHERE level = :level";

    private PayloadSearch() {}

    /**
     * Условие на строки уровня с псевдонимом {@code e} и его параметры.
     *
     * @param jdbc клиент
     * @param query условия выдачи
     * @return условие или {@code null}, если полей нет
     * @throws UnknownSearchFieldException если поле или сочетание не объявлено, а значение не подошло
     */
    static Condition condition(final JdbcClient jdbc, final TaskQuery query) {
        if (query.fields().isEmpty()) {
            return null;
        }
        final String level = query.level().name();
        final Map<String, Map<String, List<Declaration>>> byType = new LinkedHashMap<>();
        jdbc
                .sql(DECLARATIONS)
                .param("level", level)
                .param("names", List.copyOf(query.fields().keySet()))
                .query((rs, rowNum) -> new Declaration(
                        rs.getString("task_type"),
                        rs.getString("field_name"),
                        FieldType.valueOf(rs.getString("field_type")),
                        rs.getString("json_path")))
                .list()
                .stream()
                .filter(d -> query.taskType().map(d.taskType()::equals).orElse(true))
                .forEach(d -> byType.computeIfAbsent(d.taskType(), t -> new LinkedHashMap<>())
                        .computeIfAbsent(d.name(), n -> new ArrayList<>())
                        .add(d));
        final Map<String, Object> params = new LinkedHashMap<>();
        final List<String> types = new ArrayList<>();
        for (final Map.Entry<String, Map<String, List<Declaration>>> type : byType.entrySet()) {
            if (type.getValue().keySet().containsAll(query.fields().keySet())) {
                final String branch = typeBranch(type.getKey(), type.getValue(), query.fields(), params);
                if (branch != null) {
                    types.add(branch);
                }
            }
        }
        if (types.isEmpty()) {
            final Set<String> known = new LinkedHashSet<>(
                    jdbc.sql(KNOWN).param("level", level).query(String.class).list());
            throw new UnknownSearchFieldException(
                    "Поиск по полям " + query.fields().keySet() + " уровня " + level
                            + ": ни один тип не объявил все эти поля с подходящим типом значения.",
                    known);
        }
        return new Condition("(" + String.join(" OR ", types) + ")", params);
    }

    private static String typeBranch(
            final String taskType,
            final Map<String, List<Declaration>> fields,
            final Map<String, String> values,
            final Map<String, Object> params) {
        final List<String> conditions = new ArrayList<>();
        final String typeParam = "sType" + params.size();
        params.put(typeParam, taskType);
        conditions.add("e.task_type = :" + typeParam);
        for (final Map.Entry<String, String> value : values.entrySet()) {
            final List<String> branches = new ArrayList<>();
            for (final Declaration declaration : fields.get(value.getKey())) {
                final String branch = branch(declaration, value.getValue(), params);
                if (branch != null) {
                    branches.add(branch);
                }
            }
            if (branches.isEmpty()) {
                return null;
            }
            conditions.add("(" + String.join(" OR ", branches) + ")");
        }
        return "(" + String.join(" AND ", conditions) + ")";
    }

    /** Ветка одного объявления или {@code null}, если значение его типу не подходит. */
    private static String branch(final Declaration declaration, final String value, final Map<String, Object> params) {
        final String expression = expression(declaration.jsonPath());
        final String param = "sValue" + params.size();
        if (declaration.type() == FieldType.STRING) {
            params.put(param, value);
            return expression + " = :" + param;
        }
        final BigDecimal number;
        try {
            number = new BigDecimal(value);
        } catch (final NumberFormatException notNumber) {
            return null;
        }
        params.put(param, number);
        return "(CASE WHEN " + expression + " ~ '^-?[0-9]+(\\.[0-9]+)?$' THEN CAST(" + expression
                + " AS numeric) END) = :" + param;
    }

    /** {@code $.a.b} → {@code e.payload #>> '{a,b}'}; однословный путь — {@code e.payload ->> 'a'}. */
    private static String expression(final String jsonPath) {
        final String[] names = DOT.split(jsonPath.substring(2), -1);
        return names.length == 1
                ? "(e.payload ->> '" + names[0] + "')"
                : "(e.payload #>> '{" + String.join(",", names) + "}')";
    }

    /**
     * Условие и его параметры.
     *
     * @param sql условие
     * @param params параметры
     */
    record Condition(String sql, Map<String, Object> params) {}

    /**
     * Объявление поля.
     *
     * @param taskType тип задачи
     * @param name имя поля
     * @param type тип значения
     * @param jsonPath путь
     */
    private record Declaration(String taskType, String name, FieldType type, String jsonPath) {}
}
