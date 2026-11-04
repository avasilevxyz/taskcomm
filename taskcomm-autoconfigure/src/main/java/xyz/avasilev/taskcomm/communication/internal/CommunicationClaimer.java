package xyz.avasilev.taskcomm.communication.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.DoubleSupplier;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.avasilev.taskcomm.execution.api.Origin;
import xyz.avasilev.taskcomm.execution.api.OriginRelation;
import xyz.avasilev.taskcomm.execution.api.OriginType;

/**
 * Захват коммуникаций (docs/adr/0054-communication-attempt-lifecycle.md,
 * docs/adr/0067-expired-lease-closes-attempt.md). Один такт — одна короткая транзакция, процессор
 * работает уже после её коммита.
 *
 * <ul>
 *   <li><b>Брошенные попытки</b> — открытые, с истёкшей арендой — закрываются {@code LEASE_LOST},
 *       коммуникация уходит в очередь со следующей попыткой или становится терминальной.
 *   <li><b>Захват — одноветочный</b>: готовая коммуникация в {@code PENDING} соединяется со своей
 *       открытой, ещё не захваченной попыткой той фазы, что записана на коммуникации, и аренда
 *       <b>проставляется в существующую строку</b>, а не вставляется.
 * </ul>
 *
 * <p>Берутся только системы поллера: осиротевшие не захватываются и не закрываются
 * (docs/adr/0105-orphan-communications.md). Форма контракта исходящей пишется при первом захвате;
 * несовпадение записанной формы с текущим процессором отменяет коммуникацию
 * (docs/adr/0072-contract-shape-check-on-children.md). Граница активных партиций — на каждой таблице,
 * под {@code force_custom_plan} (docs/adr/0101-active-partition-horizon.md).
 */
@Slf4j
class CommunicationClaimer {

    private static final String RANGE = """
            AND %1$s.execution_created_at >= :activeFrom AND %1$s.execution_created_at < :activeTo""";

    private static final String EXPIRED = """
            SELECT a.id AS attempt_id, a.execution_created_at, a.communication_id, a.phase, a.locked_by,
                   a.lease_renewals, c.direction, coalesce(c.source, c.destination) AS system
              FROM tc_communication c
              JOIN tc_communication_attempt a ON a.execution_created_at = c.execution_created_at
                   AND a.communication_id = c.id AND a.finished_at IS NULL AND a.locked_until < now() %2$s
             WHERE c.status = 'RUNNING' %1$s
               AND coalesce(c.source, c.destination) = ANY(:systems)
             ORDER BY a.locked_until
             LIMIT :expiredLimit
               FOR UPDATE OF c, a SKIP LOCKED
            """.formatted(RANGE.formatted("c"), RANGE.formatted("a"));

    private static final String CANDIDATES = """
            SELECT c.id, c.execution_created_at, c.execution_id, c.direction,
                   coalesce(c.source, c.destination) AS system, c.phase, c.contract_shape, c.priority, c.created_at,
                   c.request_payload::text AS payload, c.origin_type, c.origin_id, c.origin_attempt_id,
                   c.origin_relation, a.id AS attempt_id
              FROM tc_communication c
              JOIN tc_communication_attempt a ON a.execution_created_at = c.execution_created_at
                   AND a.communication_id = c.id AND a.phase = c.phase AND a.finished_at IS NULL
                   AND a.locked_until IS NULL %2$s
             WHERE c.status = 'PENDING' AND c.next_attempt_at <= now() %1$s
               AND coalesce(c.source, c.destination) = ANY(:systems)
            """.formatted(RANGE.formatted("c"), RANGE.formatted("a"));

    private static final String CLOSE_EXPIRED = """
            UPDATE tc_communication_attempt
               SET status = 'LEASE_LOST', status_description = :reason, finished_at = now(), updated_at = now()
             WHERE execution_created_at = :executionCreatedAt AND id = :attemptId AND finished_at IS NULL
            """;

    private static final String MARK_RUNNING = """
            UPDATE tc_communication c
               SET status = 'RUNNING', contract_shape = coalesce(c.contract_shape, x.shape), updated_at = now()
              FROM unnest(CAST(:executionCreatedAt AS timestamptz[]), CAST(:id AS uuid[]), CAST(:shape AS text[]))
                   AS x(execution_created_at, id, shape)
             WHERE c.execution_created_at >= :activeFrom
               AND c.execution_created_at = x.execution_created_at AND c.id = x.id
            """;

    private static final String LEASE = """
            UPDATE tc_communication_attempt a
               SET locked_by = :lockedBy, locked_until = now() + make_interval(secs => x.lease),
                   lease_token = x.token, started_at = now(), updated_at = now()
              FROM unnest(CAST(:executionCreatedAt AS timestamptz[]), CAST(:id AS uuid[]),
                          CAST(:lease AS double precision[]), CAST(:token AS uuid[]))
                   AS x(execution_created_at, id, lease, token)
             WHERE a.execution_created_at >= :activeFrom
               AND a.execution_created_at = x.execution_created_at AND a.id = x.id
            """;

    /** Попытки той же фазы: в текущем цикле повторов и за всю историю, считая только что захваченную. */
    private static final String HISTORY = """
            SELECT x.id,
                   count(*) FILTER (WHERE a.started_at >= c.retry_cycle_started_at) AS in_cycle,
                   count(*) AS in_history
              FROM unnest(CAST(:executionCreatedAt AS timestamptz[]), CAST(:id AS uuid[]), CAST(:phase AS text[]))
                   AS x(execution_created_at, id, phase)
              JOIN tc_communication c ON c.execution_created_at >= :activeFrom
                   AND c.execution_created_at = x.execution_created_at AND c.id = x.id
              JOIN tc_communication_attempt a ON a.execution_created_at >= :activeFrom
                   AND a.execution_created_at = x.execution_created_at AND a.communication_id = x.id
                   AND a.phase = x.phase AND a.started_at IS NOT NULL
             GROUP BY x.id
            """;

    private static final String CANCEL_SHAPE = """
            UPDATE tc_communication_attempt
               SET status = 'SKIPPED', status_description = :reason, finished_at = now(), updated_at = now()
             WHERE execution_created_at = :executionCreatedAt AND id = :attemptId AND finished_at IS NULL
            """;

    private final JdbcClient jdbc;

    private final TransactionTemplate transactions;

    private final CommunicationSettings settings;

    private final CommunicationRetries retries;

    private final DoubleSupplier random;

    private final String instance;

    CommunicationClaimer(
            final DataSource dataSource,
            final PlatformTransactionManager transactionManager,
            final CommunicationSettings settings,
            final CommunicationRetries retries,
            final DoubleSupplier random,
            final String instance) {
        this.jdbc = JdbcClient.create(dataSource);
        this.transactions = CommunicationSql.transactions(transactionManager);
        this.settings = settings;
        this.retries = retries;
        this.random = random;
        this.instance = instance;
    }

    /**
     * Один такт захвата поллера: закрывает до {@code batch-size} брошенных попыток и захватывает
     * до {@code limit} готовых коммуникаций.
     *
     * @param poller поллер
     * @param systems системы, которые поллер вправе захватывать, с формами контрактов их процессоров
     * @param limit сколько захватить, не больше свободных воркеров
     * @return захваченная работа в порядке формы захвата
     */
    List<ClaimedCommunication> claim(
            final PollerSettings poller, final Map<String, ContractShape> systems, final int limit) {
        if (systems.isEmpty()) {
            return List.of();
        }
        final String order = this.order(poller);
        final List<ClaimedCommunication> claimed =
                this.transactions.execute(status -> this.tick(order, systems, limit, poller.batchSize()));
        return claimed == null ? List.of() : claimed;
    }

    private List<ClaimedCommunication> tick(
            final String order, final Map<String, ContractShape> systems, final int limit, final int expiredLimit) {
        CommunicationSql.forceCustomPlan(this.jdbc);
        final CommunicationPartitionHorizon.ActiveRange range = CommunicationPartitionHorizon.activeRange(this.jdbc);
        final String[] known = systems.keySet().toArray(String[]::new);
        this.jdbc
                .sql(EXPIRED)
                .param("activeFrom", CommunicationSql.parameter(range.from()))
                .param("activeTo", CommunicationSql.parameter(range.to()))
                .param("systems", known)
                .param("expiredLimit", expiredLimit)
                .query((rs, rowNum) -> expired(rs))
                .list()
                .forEach(this::closeExpired);
        final List<Candidate> candidates = this.jdbc
                .sql(CANDIDATES + " ORDER BY " + order + " LIMIT :limit FOR UPDATE OF c, a SKIP LOCKED")
                .param("activeFrom", CommunicationSql.parameter(range.from()))
                .param("activeTo", CommunicationSql.parameter(range.to()))
                .param("systems", known)
                .param("limit", limit)
                .query((rs, rowNum) -> candidate(rs))
                .list();
        final List<Candidate> matching = new ArrayList<>();
        for (final Candidate candidate : candidates) {
            final ContractShape current = systems.get(candidate.system());
            if (candidate.shape() == null || candidate.shape() == current) {
                matching.add(candidate.withShape(current));
            } else {
                this.cancelShapeMismatch(candidate, current);
            }
        }
        return this.open(range, matching);
    }

    private List<ClaimedCommunication> open(
            final CommunicationPartitionHorizon.ActiveRange range, final List<Candidate> candidates) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        final List<AttemptLease> leases = candidates.stream()
                .map(c -> AttemptLease.of(
                        c.attemptId(),
                        this.settings.attempt(c.system(), c.phase()).lease()))
                .toList();
        this.jdbc
                .sql(MARK_RUNNING)
                .param("activeFrom", CommunicationSql.parameter(range.from()))
                .param("executionCreatedAt", CommunicationSql.column(candidates, c -> moment(c.target())))
                .param("id", CommunicationSql.column(candidates, c -> c.target().id()))
                .param(
                        "shape",
                        CommunicationSql.column(candidates, c -> c.shape().name()))
                .update();
        this.jdbc
                .sql(LEASE)
                .param("activeFrom", CommunicationSql.parameter(range.from()))
                .param("lockedBy", this.instance)
                .param("executionCreatedAt", CommunicationSql.column(candidates, c -> moment(c.target())))
                .param("id", CommunicationSql.column(candidates, Candidate::attemptId))
                .param("lease", CommunicationSql.column(leases, l -> CommunicationSql.seconds(l.duration())))
                .param("token", CommunicationSql.column(leases, AttemptLease::token))
                .update();
        final Map<UUID, AttemptHistory> history = new HashMap<>();
        this.jdbc
                .sql(HISTORY)
                .param("activeFrom", CommunicationSql.parameter(range.from()))
                .param("executionCreatedAt", CommunicationSql.column(candidates, c -> moment(c.target())))
                .param("id", CommunicationSql.column(candidates, c -> c.target().id()))
                .param(
                        "phase",
                        CommunicationSql.column(candidates, c -> c.phase().name()))
                .query((rs, rowNum) -> Map.entry(
                        rs.getObject("id", UUID.class),
                        new AttemptHistory(rs.getInt("in_cycle"), rs.getInt("in_history") > 1)))
                .list()
                .forEach(entry -> history.put(entry.getKey(), entry.getValue()));
        final List<ClaimedCommunication> claimed = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            final Candidate c = candidates.get(i);
            claimed.add(new ClaimedCommunication(
                    leases.get(i),
                    c.target(),
                    c.system(),
                    c.direction(),
                    c.phase(),
                    history.getOrDefault(c.target().id(), AttemptHistory.FIRST),
                    c.snapshot()));
        }
        return claimed;
    }

    private void closeExpired(final Expired expired) {
        final LeaseSettings lease =
                this.settings.attempt(expired.system(), expired.phase()).lease();
        final String reason = expired.leaseRenewals() >= lease.maxRenewals()
                ? "превышен предел обработки " + lease.maxExecutionTime()
                : "аренда истекла: экземпляр " + expired.lockedBy() + " не продлил её";
        final int closed = this.jdbc
                .sql(CLOSE_EXPIRED)
                .param("reason", reason)
                .param("executionCreatedAt", moment(expired.target()))
                .param("attemptId", expired.attemptId())
                .update();
        if (closed == 1) {
            this.retries.afterUnsuccessful(
                    this.jdbc,
                    new CommunicationRetries.Closed(
                            expired.target(),
                            expired.attemptId(),
                            expired.system(),
                            expired.direction(),
                            expired.phase()),
                    true,
                    null);
        }
    }

    private void cancelShapeMismatch(final Candidate candidate, final ContractShape current) {
        final String reason = "форма контракта " + candidate.shape() + " не совпадает с текущей " + current;
        log.warn(
                "Коммуникация {} с '{}' отменена: {} — процессор системы сменил транспорт "
                        + "(docs/adr/0072-contract-shape-check-on-children.md).",
                candidate.target().id(),
                candidate.system(),
                reason);
        this.jdbc
                .sql(CANCEL_SHAPE)
                .param("reason", reason)
                .param("executionCreatedAt", moment(candidate.target()))
                .param("attemptId", candidate.attemptId())
                .update();
        this.jdbc
                .sql("""
                        UPDATE tc_communication
                           SET status = 'CANCELLED', status_description = :reason,
                               cancel_requested_at = coalesce(cancel_requested_at, now()), updated_at = now()
                         WHERE execution_created_at = :executionCreatedAt AND id = :id AND status = 'PENDING'
                        """)
                .param("reason", reason)
                .param("executionCreatedAt", moment(candidate.target()))
                .param("id", candidate.target().id())
                .update();
    }

    private String order(final PollerSettings poller) {
        return switch (poller.form()) {
            case DEADLINE -> "c.claim_order_at";
            case PRIORITY -> "c.priority, c.next_attempt_at";
            case QUOTA ->
                this.random.getAsDouble() < poller.fifoShare() ? "c.next_attempt_at" : "c.priority, c.next_attempt_at";
        };
    }

    private static OffsetDateTime moment(final CommunicationRef target) {
        return CommunicationSql.parameter(target.executionCreatedAt());
    }

    private static Expired expired(final ResultSet rs) throws SQLException {
        return new Expired(
                new CommunicationRef(
                        rs.getObject("communication_id", UUID.class),
                        rs.getObject("execution_created_at", OffsetDateTime.class)
                                .toInstant()),
                rs.getObject("attempt_id", UUID.class),
                rs.getString("system"),
                Direction.valueOf(rs.getString("direction")),
                CommunicationPhase.valueOf(rs.getString("phase")),
                rs.getString("locked_by"),
                rs.getInt("lease_renewals"));
    }

    private static Candidate candidate(final ResultSet rs) throws SQLException {
        final String shape = rs.getString("contract_shape");
        final String originType = rs.getString("origin_type");
        final Origin origin = originType == null
                ? null
                : new Origin(
                        OriginType.valueOf(originType),
                        rs.getObject("origin_id", UUID.class),
                        rs.getObject("origin_attempt_id", UUID.class),
                        OriginRelation.valueOf(rs.getString("origin_relation")));
        return new Candidate(
                new CommunicationRef(
                        rs.getObject("id", UUID.class),
                        rs.getObject("execution_created_at", OffsetDateTime.class)
                                .toInstant()),
                rs.getObject("attempt_id", UUID.class),
                rs.getString("system"),
                Direction.valueOf(rs.getString("direction")),
                CommunicationPhase.valueOf(rs.getString("phase")),
                shape == null ? null : ContractShape.valueOf(shape),
                new ClaimedCommunication.Snapshot(
                        rs.getObject("execution_id", UUID.class),
                        rs.getInt("priority"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        origin,
                        rs.getString("payload")));
    }

    /**
     * Брошенная попытка.
     *
     * @param target коммуникация
     * @param attemptId попытка
     * @param system внешняя система
     * @param direction направление
     * @param phase фаза
     * @param lockedBy экземпляр, не продливший аренду
     * @param leaseRenewals сколько раз аренда продлевалась
     */
    private record Expired(
            CommunicationRef target,
            UUID attemptId,
            String system,
            Direction direction,
            CommunicationPhase phase,
            String lockedBy,
            int leaseRenewals) {}

    /**
     * Готовая коммуникация с открытой незахваченной попыткой.
     *
     * @param target коммуникация
     * @param attemptId попытка
     * @param system внешняя система
     * @param direction направление
     * @param phase фаза
     * @param shape записанная форма контракта; у исходящей до первого захвата пусто
     * @param snapshot снимок для воркера
     */
    private record Candidate(
            CommunicationRef target,
            UUID attemptId,
            String system,
            Direction direction,
            CommunicationPhase phase,
            ContractShape shape,
            ClaimedCommunication.Snapshot snapshot) {

        Candidate withShape(final ContractShape value) {
            return new Candidate(
                    this.target, this.attemptId, this.system, this.direction, this.phase, value, this.snapshot);
        }
    }
}
