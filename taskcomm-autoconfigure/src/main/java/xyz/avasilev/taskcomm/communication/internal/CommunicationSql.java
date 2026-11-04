package xyz.avasilev.taskcomm.communication.internal;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Function;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Мелкие приёмы SQL подсистемы коммуникаций. Такие же есть у задач, но общего кода у подсистем нет
 * (docs/adr/0010-separate-engines.md).
 */
final class CommunicationSql {

    /** Внешняя сторона коммуникации — по ней ищется процессор. */
    static final String EXTERNAL_SYSTEM = "coalesce(c.source, c.destination)";

    private CommunicationSql() {}

    /**
     * Параметр-момент для JDBC: {@link Instant#MIN} — нерассчитанная граница активных партиций —
     * уходит как {@code -infinity}.
     *
     * @param moment момент
     * @return тот же момент со смещением UTC
     */
    static OffsetDateTime parameter(final Instant moment) {
        return Instant.MIN.equals(moment) ? OffsetDateTime.MIN : moment.atOffset(ZoneOffset.UTC);
    }

    /**
     * Столбец значений для {@code unnest(CAST(:param AS тип[]))}: строками, пустые — {@code NULL}.
     *
     * @param items строки пачки
     * @param value значение столбца у строки
     * @param <T> тип строки пачки
     * @return столбец
     */
    static <T> String[] column(final List<T> items, final Function<T, ?> value) {
        return items.stream()
                .map(value)
                .map(item -> item == null ? null : String.valueOf(item))
                .toArray(String[]::new);
    }

    /**
     * Длительность параметром для {@code make_interval(secs => ...)} (docs/adr/0079-database-clock.md).
     *
     * @param duration длительность
     * @return секунды с долями
     */
    static double seconds(final Duration duration) {
        return duration.toNanos() / 1e9;
    }

    /**
     * Шаблон своих коротких транзакций: всегда новая и явный {@code READ COMMITTED}.
     *
     * @param transactionManager менеджер транзакций стартера
     * @return шаблон транзакций
     */
    static TransactionTemplate transactions(final PlatformTransactionManager transactionManager) {
        final TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return transactions;
    }

    /**
     * Ставит {@code plan_cache_mode = force_custom_plan} только на текущую транзакцию: граница активных
     * партиций известна планировщику (docs/adr/0101-active-partition-horizon.md).
     *
     * @param jdbc клиент в открытой транзакции
     */
    static void forceCustomPlan(final JdbcClient jdbc) {
        jdbc.sql("SELECT set_config('plan_cache_mode', 'force_custom_plan', true)")
                .query(String.class)
                .single();
    }

    /**
     * Причина неуспеха по исключению.
     *
     * @param failure исключение
     * @return класс и сообщение
     */
    static String describe(final Throwable failure) {
        return failure.getMessage() == null
                ? failure.getClass().getName()
                : failure.getClass().getName() + ": " + failure.getMessage();
    }
}
