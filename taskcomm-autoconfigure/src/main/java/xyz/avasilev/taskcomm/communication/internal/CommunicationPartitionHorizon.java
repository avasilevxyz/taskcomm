package xyz.avasilev.taskcomm.communication.internal;

import java.time.Instant;
import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Граница активных партиций глазами подсистемы коммуникаций (docs/adr/0101-active-partition-horizon.md):
 * чтение для отсечения партиций и оживление старой партиции на путях записи. Своя копия — общего
 * кода с задачами нет.
 *
 * <p><b>Ключ блокировки диапазона повторяет ключ кода схемы в точности</b>: первая часть
 * {@code 0x74630002}, вторая — сутки от эпохи до нижней границы диапазона в UTC
 * (docs/rules/partitioning.md). Разошедшийся ключ молча разрешил бы такту закрыть оживлённую партицию.
 */
@RequiredArgsConstructor
public class CommunicationPartitionHorizon {

    /** Первая часть ключа блокировки диапазона — {@code 0x74630002}. */
    static final int HORIZON_RANGE_LOCK = 0x74630002;

    private static final String LOCK_RANGE_QUERY = """
            SELECT lower_bound, pg_advisory_xact_lock_shared(:key, range_key)::text AS locked
              FROM (SELECT local_lower AT TIME ZONE 'UTC' AS lower_bound,
                           (extract(epoch FROM local_lower) / 86400)::int AS range_key
                      FROM (SELECT date_trunc(:unit, CAST(:moment AS timestamptz) AT TIME ZONE 'UTC') AS local_lower) l
                   ) r
            """;

    private final JdbcClient jdbc;

    private final String truncUnit;

    /**
     * Текущая граница; нерассчитанная читается как {@link Instant#MIN}.
     *
     * @param jdbc клиент на {@code DataSource} стартера
     * @return граница
     */
    public static Instant activeFrom(final JdbcClient jdbc) {
        return jdbc.sql("SELECT CASE WHEN isfinite(active_from) THEN active_from END FROM tc_partition_horizon")
                .query((rs, rowNum) -> {
                    final OffsetDateTime value = rs.getObject(1, OffsetDateTime.class);
                    return value == null ? Instant.MIN : value.toInstant();
                })
                .single();
    }

    /**
     * Диапазон активных партиций для запроса такта (docs/adr/0131-claim-upper-partition-bound.md):
     * снизу — граница, сверху — {@code now()} базы с запасом в час.
     *
     * @param jdbc клиент в транзакции такта
     * @return диапазон
     */
    public static ActiveRange activeRange(final JdbcClient jdbc) {
        return jdbc.sql("""
                        SELECT CASE WHEN isfinite(active_from) THEN active_from END AS active_from,
                               now() + interval '1 hour' AS active_to
                          FROM tc_partition_horizon
                        """)
                .query((rs, rowNum) -> {
                    final OffsetDateTime from = rs.getObject("active_from", OffsetDateTime.class);
                    return new ActiveRange(
                            from == null ? Instant.MIN : from.toInstant(),
                            rs.getObject("active_to", OffsetDateTime.class).toInstant());
                })
                .single();
    }

    /**
     * Оживляет партицию: в незавершённое состояние переходит коммуникация с этим ключом
     * партиционирования. Обязан работать в транзакции записи.
     *
     * @param executionCreatedAt ключ партиционирования оживляемой сущности
     */
    public void revive(final Instant executionCreatedAt) {
        final OffsetDateTime lower = this.jdbc
                .sql(LOCK_RANGE_QUERY)
                .param("key", HORIZON_RANGE_LOCK)
                .param("unit", this.truncUnit)
                .param("moment", CommunicationSql.parameter(executionCreatedAt))
                .query((rs, rowNum) -> rs.getObject("lower_bound", OffsetDateTime.class))
                .single();
        this.jdbc
                .sql("UPDATE tc_partition_horizon SET active_from = :lower, updated_at = now()"
                        + " WHERE active_from > :lower")
                .param("lower", lower)
                .update();
    }

    /**
     * Диапазон активных партиций запроса такта.
     *
     * @param from граница; {@link Instant#MIN} — активны все
     * @param to верхний предел: {@code now()} базы с запасом
     */
    public record ActiveRange(Instant from, Instant to) {}
}
