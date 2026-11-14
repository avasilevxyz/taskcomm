package xyz.avasilev.taskcomm;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Запрос, которым чтение получает свою выдачу, — текст и параметры (docs/adr/0165-read-sql.md). Собирается
 * тем же кодом, что выполняет чтение, и потому не расходится с ним.
 *
 * <p>Это средство разбора, а не контракт: схема таблиц внутренняя и меняется вместе со стартером, поэтому
 * строить на этом тексте отчёты и скрипты нельзя.
 *
 * @param sql текст с параметрами вида {@code :имя}
 * @param parameters значения параметров по имени, в порядке появления: {@code String}, {@code UUID},
 *     {@code Integer}, {@code BigDecimal}, {@code OffsetDateTime}, {@code List} строк или {@code null}
 */
public record SqlQuery(String sql, Map<String, Object> parameters) {

    /**
     * Проверяет текст и делает параметры неизменяемыми.
     *
     * @throws NullPointerException если текст пуст
     */
    public SqlQuery {
        Objects.requireNonNull(sql, "sql");
        parameters = parameters == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
    }
}
