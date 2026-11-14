package xyz.avasilev.taskcomm.rest;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import xyz.avasilev.taskcomm.SqlQuery;

/**
 * Запрос чтения с подставленными значениями — чтобы его можно было вставить в psql и выполнить
 * (docs/adr/0165-read-sql.md). Только для показа: стартер этот текст не выполняет, его запросы идут
 * с параметрами.
 *
 * <p>Параметр {@code :имя} заменяется вне строк и идентификаторов в кавычках; {@code ::} — приведение
 * типа, не параметр. Строки — в одинарных кавычках с удвоенной кавычкой внутри: при
 * {@code standard_conforming_strings}, включённом в PostgreSQL по умолчанию, обратная косая черта
 * в них — обычный символ.
 */
public final class SqlLiterals {

    private SqlLiterals() {}

    /**
     * Текст запроса с подставленными значениями и точкой с запятой.
     *
     * @param query запрос
     * @return текст для psql
     * @throws IllegalArgumentException если у параметра нет значения или значение без литерала
     */
    public static String statement(final SqlQuery query) {
        return new Renderer(query.sql().strip(), query.parameters()).run() + ";";
    }

    /**
     * Несколько запросов подряд, каждый — со своим заголовком комментарием.
     *
     * @param statements заголовок и запрос, в порядке выдачи
     * @return текст для psql
     */
    public static String script(final Map<String, SqlQuery> statements) {
        return statements.entrySet().stream()
                .map(entry -> "-- " + entry.getKey() + "\n" + statement(entry.getValue()))
                .collect(Collectors.joining("\n\n"));
    }

    /**
     * Литерал значения.
     *
     * @param value значение параметра
     * @return литерал PostgreSQL
     * @throws IllegalArgumentException если значение такого типа в запросах чтения не встречается
     */
    static String literal(final Object value) {
        return switch (value) {
            case null -> "NULL";
            case String text -> quote(text);
            case UUID id -> quote(id.toString()) + "::uuid";
            case OffsetDateTime moment -> timestamp(moment);
            case BigDecimal number -> number.toPlainString();
            case Number number -> number.toString();
            case Boolean flag -> flag.toString().toUpperCase(Locale.ROOT);
            case Collection<?> items -> list(items);
            default -> throw new IllegalArgumentException("Значение параметра SQL без литерала: " + value.getClass());
        };
    }

    private static String quote(final String text) {
        return "'" + text.replace("'", "''") + "'";
    }

    /** Пределы {@code OffsetDateTime} драйвер передаёт как бесконечности — так же и здесь. */
    private static String timestamp(final OffsetDateTime moment) {
        final String text;
        if (OffsetDateTime.MIN.equals(moment)) {
            text = "-infinity";
        } else if (OffsetDateTime.MAX.equals(moment)) {
            text = "infinity";
        } else {
            text = moment.toString();
        }
        return quote(text) + "::timestamptz";
    }

    /** Список для {@code IN (...)}; пустой — {@code NULL}, с которым не совпадёт ни одна строка. */
    private static String list(final Collection<?> items) {
        final List<String> literals = items.stream().map(SqlLiterals::literal).toList();
        return literals.isEmpty() ? "NULL" : String.join(", ", literals);
    }

    /** Проход по тексту: строки и идентификаторы в кавычках копируются как есть. */
    private static final class Renderer {

        private final String sql;

        private final Map<String, Object> parameters;

        private final StringBuilder out;

        /** Открытая кавычка или {@code 0} вне кавычек. */
        private char quote;

        private int at;

        Renderer(final String sql, final Map<String, Object> parameters) {
            this.sql = sql;
            this.parameters = parameters;
            this.out = new StringBuilder(sql.length());
        }

        String run() {
            while (this.at < this.sql.length()) {
                this.step();
            }
            return this.out.toString();
        }

        private void step() {
            final char current = this.sql.charAt(this.at);
            if (this.quote != 0 || current == '\'' || current == '"') {
                this.quoted(current);
            } else if (current == ':' && this.next() == ':') {
                this.out.append("::");
                this.at += 2;
            } else if (current == ':' && Character.isLetter(this.next())) {
                this.parameter();
            } else {
                this.out.append(current);
                this.at++;
            }
        }

        private char next() {
            return this.at + 1 < this.sql.length() ? this.sql.charAt(this.at + 1) : 0;
        }

        /** Удвоенная кавычка внутри строки — закрытие и сразу открытие: текст копируется верно. */
        private void quoted(final char current) {
            if (this.quote == 0) {
                this.quote = current;
            } else if (current == this.quote) {
                this.quote = 0;
            }
            this.out.append(current);
            this.at++;
        }

        private void parameter() {
            int end = this.at + 1;
            while (end < this.sql.length() && isNamePart(this.sql.charAt(end))) {
                end++;
            }
            final String name = this.sql.substring(this.at + 1, end);
            if (!this.parameters.containsKey(name)) {
                throw new IllegalArgumentException("У параметра SQL :" + name + " нет значения");
            }
            this.out.append(literal(this.parameters.get(name)));
            this.at = end;
        }

        private static boolean isNamePart(final char symbol) {
            return Character.isLetterOrDigit(symbol) || symbol == '_';
        }
    }
}
