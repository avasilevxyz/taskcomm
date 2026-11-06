package xyz.avasilev.taskcomm.task.internal.observation;

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
import xyz.avasilev.taskcomm.execution.internal.UuidV7;
import xyz.avasilev.taskcomm.task.internal.TaskLevel;
import xyz.avasilev.taskcomm.task.internal.TaskPartitionHorizon;
import xyz.avasilev.taskcomm.task.internal.TaskSql;
import xyz.avasilev.taskcomm.task.internal.settings.ClaimForm;
import xyz.avasilev.taskcomm.task.internal.settings.PollerLayout;
import xyz.avasilev.taskcomm.task.internal.settings.PollerSettings;

/**
 * Снимок метрик задач на всю схему в {@code tc_metrics_snapshot}
 * (docs/adr/0102-schema-wide-metrics-snapshot.md, docs/adr/0142-metrics-snapshot-per-subsystem.md).
 *
 * <p>Такт делает две вещи. <b>Подсчёт</b> — один экземпляр за такт: транзакция начинается
 * с {@code pg_try_advisory_xact_lock} по постоянному ключу подсистемы; не взял блокировку или снимок
 * моложе такта — подсчёта нет. Подсчёт пишет строки задач одной транзакцией и удаляет строки, которых
 * в этом такте не было. <b>Чтение</b> — на каждом экземпляре: строки задач уходят в
 * {@link TaskMetrics#snapshot}, и опрос метрик в базу не ходит.
 *
 * <p>Ключ блокировки — {@code (0x74630004, 0)}, записан в docs/rules/partitioning.md. Строки
 * коммуникаций подсистема не трогает: их считает своим ключом подсистема коммуникаций.
 */
@Slf4j
final class TaskMetricsSnapshot {

    /** Первая часть ключа блокировки подсчёта; вторая — 0. */
    static final int LOCK = 0x74630004;

    private static final String TRY_LOCK = "SELECT pg_try_advisory_xact_lock(:key, 0)";

    private static final String FRESH = """
            SELECT coalesce(max(computed_at) > now() - make_interval(secs => :seconds), false)
              FROM tc_metrics_snapshot
             WHERE subsystem = 'TASK'
            """;

    /**
     * Ждущая работа известных типов по трём уровням. {@code tried} — есть ли у отложенной строки
     * попытки: нет — отложенный запуск, есть — повтор (docs/adr/0050-deferred-start.md).
     */
    private static final String PENDING =
            """
            WITH pending AS (
                %s
                UNION ALL
                %s
                UNION ALL
                %s
            )
            SELECT task_type,
                   count(*) FILTER (WHERE NOT delayed) AS queue,
                   extract(epoch FROM now() - min(next_attempt_at) FILTER (WHERE NOT delayed)) AS oldest,
                   extract(epoch FROM now() - min(claim_order_at) FILTER (WHERE NOT delayed)) AS overdue,
                   count(*) FILTER (WHERE delayed AND NOT tried) AS scheduled,
                   count(*) FILTER (WHERE delayed AND tried) AS retry
              FROM pending
             GROUP BY task_type
            """.formatted(pending(TaskLevel.TASK), pending(TaskLevel.BATCH), pending(TaskLevel.UNIT));

    /** Незавершённые всех типов — известных и осиротевших; статусы — как у проверки удаления партиций. */
    private static final String UNFINISHED = """
            SELECT task_type, count(*) AS unfinished FROM (
                SELECT task_type FROM tc_task
                 WHERE execution_created_at >= :activeFrom AND status IN ('PENDING', 'RUNNING', 'AWAITING_CHILDREN')
                UNION ALL
                SELECT task_type FROM tc_task_batch
                 WHERE execution_created_at >= :activeFrom
                   AND status IN ('AWAITING_PARENT', 'PENDING', 'RUNNING', 'AWAITING_CHILDREN')
                UNION ALL
                SELECT task_type FROM tc_task_unit
                 WHERE execution_created_at >= :activeFrom AND status IN ('AWAITING_PARENT', 'PENDING', 'RUNNING')
            ) u
             GROUP BY task_type
            """;

    private static final String UPSERT = """
            INSERT INTO tc_metrics_snapshot AS s (
                id, created_at, updated_at, subsystem, metric, type_or_system, poller, reason, value, computed_at)
            SELECT CAST(r.id AS uuid), now(), now(), 'TASK', r.metric, r.type_or_system, r.poller, r.reason,
                   CAST(r.value AS double precision), now()
              FROM unnest(CAST(:ids AS text[]), CAST(:metrics AS text[]), CAST(:types AS text[]),
                          CAST(:pollers AS text[]), CAST(:reasons AS text[]), CAST(:values AS text[]))
                   AS r(id, metric, type_or_system, poller, reason, value)
            ON CONFLICT ON CONSTRAINT tc_metrics_snapshot_labels_uq DO UPDATE
               SET value = EXCLUDED.value, computed_at = EXCLUDED.computed_at, updated_at = EXCLUDED.updated_at
            """;

    private static final String DELETE_STALE =
            "DELETE FROM tc_metrics_snapshot WHERE subsystem = 'TASK' AND computed_at < now()";

    private static final String READ = """
            SELECT metric, type_or_system, poller, reason, value,
                   extract(epoch FROM now() - computed_at) AS age
              FROM tc_metrics_snapshot
             WHERE subsystem = 'TASK'
            """;

    private final JdbcClient jdbc;

    private final TransactionTemplate transactions;

    private final UuidV7 ids;

    private final PollerLayout layout;

    private final Set<String> known;

    private final Duration interval;

    private final TaskMetrics metrics;

    /**
     * Собирает снимок.
     *
     * @param dataSource {@code DataSource} стартера
     * @param transactionManager менеджер транзакций стартера
     * @param ids генератор идентификаторов
     * @param layout раскладка поллеров — метка {@code poller} и форма захвата
     * @param known типы, для которых есть процессор
     * @param interval такт снимка
     * @param metrics куда отдавать прочитанный снимок
     */
    TaskMetricsSnapshot(
            final DataSource dataSource,
            final PlatformTransactionManager transactionManager,
            final UuidV7 ids,
            final PollerLayout layout,
            final Set<String> known,
            final Duration interval,
            final TaskMetrics metrics) {
        this.jdbc = JdbcClient.create(dataSource);
        this.transactions = TaskSql.transactions(transactionManager);
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
            log.warn("Снимок метрик задач: подсчёт не удался, повтор на следующем такте", failure);
        }
        try {
            this.publish();
        } catch (final RuntimeException failure) {
            log.warn("Снимок метрик задач: чтение не удалось, публикуются прежние значения", failure);
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

    /** Читает строки задач и отдаёт их метрикам. */
    void publish() {
        final List<TaskMetrics.SnapshotRow> rows = new ArrayList<>();
        final double[] age = {Double.NaN};
        this.jdbc.sql(READ).query(rs -> {
            rows.add(new TaskMetrics.SnapshotRow(
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
     * Строки такта. Известный тип получает все свои ряды, даже нулевые: ряд не пропадает, когда
     * очередь пуста. Осиротевший тип — только незавершённые и осиротевшие, пока они есть.
     */
    private List<TaskMetrics.SnapshotRow> count() {
        final Instant activeFrom = TaskPartitionHorizon.activeFrom(this.jdbc);
        final Map<String, Pending> pending = new HashMap<>();
        this.jdbc
                .sql(PENDING)
                .param("activeFrom", TaskSql.parameter(activeFrom))
                .param("types", this.known.toArray(String[]::new))
                .query(rs -> {
                    pending.put(
                            rs.getString("task_type"),
                            new Pending(
                                    rs.getLong("queue"),
                                    rs.getDouble("oldest"),
                                    rs.getDouble("overdue"),
                                    rs.getLong("scheduled"),
                                    rs.getLong("retry")));
                });
        final Map<String, Long> unfinished = new HashMap<>();
        this.jdbc
                .sql(UNFINISHED)
                .param("activeFrom", TaskSql.parameter(activeFrom))
                .query(rs -> {
                    unfinished.put(rs.getString("task_type"), rs.getLong("unfinished"));
                });
        final List<TaskMetrics.SnapshotRow> rows = new ArrayList<>();
        for (final String type : new TreeSet<>(this.known)) {
            this.knownRows(rows, type, pending.getOrDefault(type, Pending.NONE), unfinished.getOrDefault(type, 0L));
        }
        for (final Map.Entry<String, Long> entry : new TreeMap<>(unfinished).entrySet()) {
            if (!this.known.contains(entry.getKey())) {
                rows.add(new TaskMetrics.SnapshotRow("UNFINISHED", entry.getKey(), "", "", entry.getValue()));
                rows.add(new TaskMetrics.SnapshotRow("ORPHANED", entry.getKey(), "", "", entry.getValue()));
            }
        }
        return rows;
    }

    private void knownRows(
            final List<TaskMetrics.SnapshotRow> rows, final String type, final Pending pending, final long unfinished) {
        final PollerSettings poller = this.layout.pollerOf(type);
        rows.add(new TaskMetrics.SnapshotRow("QUEUE", type, poller.name(), "", pending.queue()));
        rows.add(new TaskMetrics.SnapshotRow("DELAYED", type, poller.name(), "SCHEDULED", pending.scheduled()));
        rows.add(new TaskMetrics.SnapshotRow("DELAYED", type, poller.name(), "RETRY", pending.retry()));
        rows.add(new TaskMetrics.SnapshotRow("OLDEST_CLAIMABLE_AGE", type, poller.name(), "", pending.oldest()));
        if (poller.form() == ClaimForm.DEADLINE) {
            rows.add(new TaskMetrics.SnapshotRow("OVERDUE", type, poller.name(), "", Math.max(0.0, pending.overdue())));
        }
        rows.add(new TaskMetrics.SnapshotRow("UNFINISHED", type, "", "", unfinished));
    }

    private void write(final List<TaskMetrics.SnapshotRow> rows) {
        this.jdbc
                .sql(UPSERT)
                .param(
                        "ids",
                        rows.stream().map(row -> this.ids.next().toString()).toArray(String[]::new))
                .param(
                        "metrics",
                        rows.stream().map(TaskMetrics.SnapshotRow::metric).toArray(String[]::new))
                .param("types", rows.stream().map(TaskMetrics.SnapshotRow::type).toArray(String[]::new))
                .param(
                        "pollers",
                        rows.stream().map(TaskMetrics.SnapshotRow::poller).toArray(String[]::new))
                .param(
                        "reasons",
                        rows.stream().map(TaskMetrics.SnapshotRow::reason).toArray(String[]::new))
                .param(
                        "values",
                        rows.stream().map(row -> Double.toString(row.value())).toArray(String[]::new))
                .update();
        this.jdbc.sql(DELETE_STALE).update();
    }

    /** Ветка ждущей работы одного уровня: тип известен, строка в активных партициях. */
    private static String pending(final TaskLevel level) {
        return """
                SELECT e.task_type, e.next_attempt_at, e.claim_order_at, e.next_attempt_at > now() AS delayed,
                       e.next_attempt_at > now() AND EXISTS (
                           SELECT 1 FROM tc_task_attempt a
                            WHERE a.execution_created_at >= :activeFrom
                              AND a.execution_created_at = e.execution_created_at
                              AND a.level = '%1$s' AND a.target_id = e.id) AS tried
                  FROM %2$s e
                 WHERE e.status = 'PENDING'
                   AND e.task_type = ANY(CAST(:types AS text[]))
                   AND e.execution_created_at >= :activeFrom""".formatted(level.name(), level.table());
    }

    /**
     * Ждущая работа одного типа.
     *
     * @param queue захватываемая сейчас
     * @param oldest возраст самой старой захватываемой, секунды; 0 — захватываемой нет
     * @param overdue наибольшая просрочка {@code claim_order_at}, секунды; может быть отрицательной
     * @param scheduled отложенный запуск
     * @param retry ждущая повтора
     */
    private record Pending(long queue, double oldest, double overdue, long scheduled, long retry) {

        static final Pending NONE = new Pending(0, 0, 0, 0, 0);
    }
}
