package xyz.avasilev.taskcomm.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import xyz.avasilev.taskcomm.SqlQuery;

/** Подстановка значений в SQL чтения для разбора (docs/adr/0165-read-sql.md). */
class SqlLiteralsTest {

    @Test
    void valuesBecomeLiteralsOfTheirTypes() {
        final Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", UUID.fromString("01a1d79c-e791-7575-9a1f-b225c962bbec"));
        params.put("at", OffsetDateTime.ofInstant(Instant.parse("2026-11-14T01:12:26.769356Z"), ZoneOffset.UTC));
        params.put("statuses", List.of("PENDING", "FAILED"));
        params.put("limit", 50);
        params.put("amount", new BigDecimal("12.50"));
        params.put("subject", null);

        assertThat(SqlLiterals.statement(new SqlQuery(
                        "SELECT * FROM t WHERE id = :id AND at < :at AND status IN (:statuses)"
                                + " AND n = :amount AND s IS NOT DISTINCT FROM CAST(:subject AS text) LIMIT :limit",
                        params)))
                .isEqualTo("SELECT * FROM t WHERE id = '01a1d79c-e791-7575-9a1f-b225c962bbec'::uuid"
                        + " AND at < '2026-11-14T01:12:26.769356Z'::timestamptz AND status IN ('PENDING', 'FAILED')"
                        + " AND n = 12.50 AND s IS NOT DISTINCT FROM CAST(NULL AS text) LIMIT 50;");
    }

    @Test
    void quotesCastsAndQuotedTextAreLeftAlone() {
        assertThat(SqlLiterals.statement(new SqlQuery(
                        "SELECT payload::text, e.payload ->> 'a:b' FROM t WHERE v ~ '^-?[0-9]+$' AND s = :s",
                        Map.of("s", "O'Brien \\ :x"))))
                .isEqualTo("SELECT payload::text, e.payload ->> 'a:b' FROM t WHERE v ~ '^-?[0-9]+$'"
                        + " AND s = 'O''Brien \\ :x';");
    }

    @Test
    void limitsOfTimeBecomeInfinities() {
        assertThat(SqlLiterals.literal(OffsetDateTime.MIN)).isEqualTo("'-infinity'::timestamptz");
        assertThat(SqlLiterals.literal(OffsetDateTime.MAX)).isEqualTo("'infinity'::timestamptz");
        assertThat(SqlLiterals.literal(List.of())).isEqualTo("NULL");
    }

    @Test
    void severalStatementsGoWithTheirTitles() {
        final Map<String, SqlQuery> statements = new LinkedHashMap<>();
        statements.put("Задачи", new SqlQuery("SELECT 1\n", Map.of()));
        statements.put("Группы", new SqlQuery("SELECT :n", Map.of("n", 2)));

        assertThat(SqlLiterals.script(statements)).isEqualTo("-- Задачи\nSELECT 1;\n\n-- Группы\nSELECT 2;");
    }

    @Test
    void parameterWithoutValueIsAnError() {
        assertThatThrownBy(() -> SqlLiterals.statement(new SqlQuery("SELECT :missing", Map.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(":missing");
    }
}
