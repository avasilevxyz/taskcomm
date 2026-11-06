package xyz.avasilev.taskcomm.communication.internal.observation;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.avasilev.taskcomm.communication.internal.CommunicationPartitionHorizon;
import xyz.avasilev.taskcomm.communication.internal.CommunicationSql;
import xyz.avasilev.taskcomm.communication.internal.settings.ClaimForm;
import xyz.avasilev.taskcomm.communication.internal.settings.PollerLayout;
import xyz.avasilev.taskcomm.communication.internal.settings.PollerSettings;
import xyz.avasilev.taskcomm.execution.internal.UuidV7;

/**
 * Снимок метрик коммуникаций на всю схему в {@code tc_metrics_snapshot}
 * (docs/adr/0102-schema-wide-metrics-snapshot.md, docs/adr/0142-metrics-snapshot-per-subsystem.md).
 * Своя реализация — общего кода со снимком задач нет (docs/adr/0010-separate-engines.md).
 *
 * <p>Такт делает две вещи. <b>Подсчёт</b> — один экземпляр за такт: транзакция начинается
 * с {@code pg_try_advisory_xact_lock} по постоянному ключу подсистемы; не взял блокировку или снимок
 * моложе такта — подсчёта нет. Подсчёт пишет строки коммуникаций одной транзакцией и удаляет строки,
 * которых в этом такте не было. <b>Чтение</b> — на каждом экземпляре: строки коммуникаций уходят в
 * {@link CommunicationMetrics#snapshot}, и опрос метрик в базу не ходит.
 *
 * <p>Ключ блокировки — {@code (0x74630005, 0)}, записан в docs/rules/partitioning.md.
 */
@Slf4j
final class CommunicationMetricsSnapshot {

    /** Первая часть ключа блокировки подсчёта; вторая — 0. */
    static final int LOCK = 0x74630005;

    private static final String TRY_LOCK = "SELECT pg_try_advisory_xact_lock(:key, 0)";

    private static final String FRESH = """
            SELECT coalesce(max(computed_at) > now() - make_interval(secs => :seconds), false)
              FROM tc_metrics_snapshot
             WHERE subsystem = 'COMMUNICATION'
            """;

    /**
     * Ждущие коммуникации известных систем. {@code tried} — есть ли у отложенной закрытая попытка:
     * нет — отложенный запуск, есть — повтор. Открытая попытка у коммуникации есть всегда
     * (docs/adr/0054-communication-attempt-lifecycle.md), поэтому признак — именно закрытая.
     */
    private static final String PENDING = """
            SELECT system,
                   count(*) FILTER (WHERE NOT delayed) AS queue,
                   extract(epoch FROM now() - min(next_attempt_at) FILTER (WHERE NOT delayed)) AS oldest,
                   extract(epoch FROM now() - min(claim_order_at) FILTER (WHERE NOT delayed)) AS overdue,
                   count(*) FILTER (WHERE delayed AND NOT tried) AS scheduled,
                   count(*) FILTER (WHERE delayed AND tried) AS retry
              FROM (SELECT coalesce(c.source, c.destination) AS system, c.next_attempt_at, c.claim_order_at,
                           c.next_attempt_at > now() AS delayed,
                           c.next_attempt_at > now() AND EXISTS (
                               SELECT 1 FROM tc_communication_attempt a
                                WHERE a.execution_created_at >= :activeFrom
                                  AND a.execution_created_at = c.execution_created_at
                                  AND a.communication_id = c.id
                                  AND a.finished_at IS NOT NULL) AS tried
                      FROM tc_communication c
                     WHERE c.status = 'PENDING'
                       AND coalesce(c.source, c.destination) = ANY(CAST(:systems AS text[]))
                       AND c.execution_created_at >= :activeFrom) p
             GROUP BY system
            """;

    /** Незавершённые всех систем — известных и осиротевших. */
    private static final String UNFINISHED = """
            SELECT coalesce(source, destination) AS system, count(*) AS unfinished
              FROM tc_communication
             WHERE status IN ('PENDING', 'RUNNING', 'AWAITING_RESPONSE')
               AND execution_created_at >= :activeFrom
             GROUP BY coalesce(source, destination)
            """;

    private static final String UPSERT = """
            INSERT INTO tc_metrics_snapshot AS s (
                id, created_at, updated_at, subsystem, metric, type_or_system, poller, reason, value, computed_at)
            SELECT CAST(r.id AS uuid), now(), now(), 'COMMUNICATION', r.metric, r.type_or_system, r.poller,
                   r.reason, CAST(r.value AS double precision), now()
              FROM unnest(CAST(:ids AS text[]), CAST(:metrics AS text[]), CAST(:systems AS text[]),
                          CAST(:pollers AS text[]), CAST(:reasons AS text[]), CAST(:values AS text[]))
                   AS r(id, metric, type_or_system, poller, reason, value)
            ON CONFLICT ON CONSTRAINT tc_metrics_snapshot_labels_uq DO UPDATE
               SET value = EXCLUDED.value, computed_at = EXCLUDED.computed_at, updated_at = EXCLUDED.updated_at
            """;

    private static final String DELETE_STALE =
            "DELETE FROM tc_metrics_snapshot WHERE subsystem = 'COMMUNICATION' AND computed_at < now()";

    private static final String READ = """
            SELECT metric, type_or_system, poller, reason, value,
                   extract(epoch FROM now() - computed_at) AS age
              FROM tc_metrics_snapshot
             WHERE subsystem = 'COMMUNICATION'
            """;

    private final JdbcClient jdbc;

    private final TransactionTemplate transactions;

    private final UuidV7 ids;

    private final PollerLayout layout;

    private final Set<String> known;

    private final Duration interval;

    private final CommunicationMetrics metrics;

    /**
     * Собирает снимок.
     *
     * @param dataSource {@code DataSource} стартера
     * @param transactionManager менеджер транзакций стартера
     * @param ids генератор идентификаторов
     * @param layout раскладка поллеров — метка {@code poller} и форма захвата
     * @param known системы, для которых есть процессор
     * @param interval такт снимка
     * @param metrics куда отдавать прочитанный снимок
     */
    CommunicationMetricsSnapshot(
            final DataSource dataSource,
            final PlatformTransactionManager transactionManager,
            final UuidV7 ids,
            final PollerLayout layout,
            final Set<String> known,
            final Duration interval,
            final CommunicationMetrics metrics) {
        this.jdbc = JdbcClient.create(dataSource);
        this.transactions = CommunicationSql.transactions(transactionManager);
        this.ids = ids;
        this.layout = layout;
        this.known = Set.copyOf(known);
        this.interval = interval;
        this.metrics = metrics;
    }

    /** Такт: подсчёт, если он за этим экземпляром, и чтение. Ошибка одного шага не отменяет другой. */
    void tick() {
        try {
            this.refresh();
        } catch (final RuntimeException failure) {
            log.warn("Снимок метрик коммуникаций: подсчёт не удался, повтор на следующем такте", failure);
        }
        try {
            this.publish();
        } catch (final RuntimeException failure) {
            log.warn("Снимок метрик коммуникаций: чтение не удалось, публикуются прежние значения", failure);
        }
    }

    /**
     * Подсчёт под блокировкой подсистемы.
     *
     * @return посчитал ли снимок этот экземпляр: {@code false} — блокировка у другого или снимок моложе такта
     */
    boolean refresh() {
        return Boolean.TRUE.equals(this.transactions.execute(status -> {
            if (!Boolean.TRUE.equals(this.jdbc
                    .sql(TRY_LOCK)
                    .param("key", LOCK)
                    .query(Boolean.class)
                    .single())) {
                return false;
            }
            if (Boolean.TRUE.equals(this.jdbc
                    .sql(FRESH)
                    .param("seconds", this.interval.toMillis() / 1000.0)
                    .query(Boolean.class)
                    .single())) {
                return false;
            }
            this.write(this.count());
            return true;
        }));
    }

    /** Читает строки коммуникаций и отдаёт их метрикам. */
    void publish() {
        final List<CommunicationMetrics.SnapshotRow> rows = new ArrayList<>();
        final double[] age = {Double.NaN};
        this.jdbc.sql(READ).query(rs -> {
            rows.add(new CommunicationMetrics.SnapshotRow(
                    rs.getString("metric"),
                    rs.getString("type_or_system"),
                    rs.getString("poller"),
                    rs.getString("reason"),
                    rs.getDouble("value")));
            final double rowAge = rs.getDouble("age");
            age[0] = Double.isNaN(age[0]) ? rowAge : Math.max(age[0], rowAge);
        });
        this.metrics.snapshot(rows, age[0]);
    }

    /**
     * Строки такта. Известная система получает все свои ряды, даже нулевые; осиротевшая — только
     * незавершённые и осиротевшие, пока они есть (docs/adr/0105-orphan-communications.md).
     */
    private List<CommunicationMetrics.SnapshotRow> count() {
        final Instant activeFrom = CommunicationPartitionHorizon.activeFrom(this.jdbc);
        final Map<String, Pending> pending = new HashMap<>();
        this.jdbc
                .sql(PENDING)
                .param("activeFrom", CommunicationSql.parameter(activeFrom))
                .param("systems", this.known.toArray(String[]::new))
                .query(rs -> {
                    pending.put(
                            rs.getString("system"),
                            new Pending(
                                    rs.getLong("queue"),
                                    rs.getDouble("oldest"),
                                    rs.getDouble("overdue"),
                                    rs.getLong("scheduled"),
                                    rs.getLong("retry")));
                });
        final Map<String, Long> unfinished = new TreeMap<>();
        this.jdbc
                .sql(UNFINISHED)
                .param("activeFrom", CommunicationSql.parameter(activeFrom))
                .query(rs -> {
                    unfinished.put(rs.getString("system"), rs.getLong("unfinished"));
                });
        final List<CommunicationMetrics.SnapshotRow> rows = new ArrayList<>();
        for (final String system : new TreeSet<>(this.known)) {
            this.knownRows(
                    rows, system, pending.getOrDefault(system, Pending.NONE), unfinished.getOrDefault(system, 0L));
        }
        unfinished.forEach((system, count) -> {
            if (!this.known.contains(system)) {
                rows.add(new CommunicationMetrics.SnapshotRow("UNFINISHED", system, "", "", count));
                rows.add(new CommunicationMetrics.SnapshotRow("ORPHANED", system, "", "", count));
            }
        });
        return rows;
    }

    private void knownRows(
            final List<CommunicationMetrics.SnapshotRow> rows,
            final String system,
            final Pending pending,
            final long unfinished) {
        final PollerSettings poller = this.layout.pollerOf(system);
        rows.add(new CommunicationMetrics.SnapshotRow("QUEUE", system, poller.name(), "", pending.queue()));
        rows.add(new CommunicationMetrics.SnapshotRow(
                "DELAYED", system, poller.name(), "SCHEDULED", pending.scheduled()));
        rows.add(new CommunicationMetrics.SnapshotRow("DELAYED", system, poller.name(), "RETRY", pending.retry()));
        rows.add(new CommunicationMetrics.SnapshotRow(
                "OLDEST_CLAIMABLE_AGE", system, poller.name(), "", pending.oldest()));
        if (poller.form() == ClaimForm.DEADLINE) {
            rows.add(new CommunicationMetrics.SnapshotRow(
                    "OVERDUE", system, poller.name(), "", Math.max(0.0, pending.overdue())));
        }
        rows.add(new CommunicationMetrics.SnapshotRow("UNFINISHED", system, "", "", unfinished));
    }

    private void write(final List<CommunicationMetrics.SnapshotRow> rows) {
        this.jdbc
                .sql(UPSERT)
                .param(
                        "ids",
                        rows.stream().map(row -> this.ids.next().toString()).toArray(String[]::new))
                .param(
                        "metrics",
                        rows.stream()
                                .map(CommunicationMetrics.SnapshotRow::metric)
                                .toArray(String[]::new))
                .param(
                        "systems",
                        rows.stream()
                                .map(CommunicationMetrics.SnapshotRow::system)
                                .toArray(String[]::new))
                .param(
                        "pollers",
                        rows.stream()
                                .map(CommunicationMetrics.SnapshotRow::poller)
                                .toArray(String[]::new))
                .param(
                        "reasons",
                        rows.stream()
                                .map(CommunicationMetrics.SnapshotRow::reason)
                                .toArray(String[]::new))
                .param(
                        "values",
                        rows.stream().map(row -> Double.toString(row.value())).toArray(String[]::new))
                .update();
        this.jdbc.sql(DELETE_STALE).update();
    }

    /**
     * Ждущие коммуникации одной системы.
     *
     * @param queue захватываемые сейчас
     * @param oldest возраст самой старой захватываемой, секунды; 0 — захватываемых нет
     * @param overdue наибольшая просрочка {@code claim_order_at}, секунды; может быть отрицательной
     * @param scheduled отложенный запуск
     * @param retry ждущие повтора
     */
    private record Pending(long queue, double oldest, double overdue, long scheduled, long retry) {

        static final Pending NONE = new Pending(0, 0, 0, 0, 0);
    }
}
