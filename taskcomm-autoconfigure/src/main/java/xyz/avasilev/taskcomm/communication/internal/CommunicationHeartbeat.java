package xyz.avasilev.taskcomm.communication.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Heartbeat коммуникаций экземпляра: продлевает аренды всех попыток, которые выполняют его воркеры
 * и вызывающие синхронного приёма, одним запросом на такт (docs/adr/0025-lease-mechanics.md). Своя
 * копия — общего кода с задачами нет.
 *
 * <p>Продление трогает только строки попыток, условие — токен и {@code finished_at IS NULL}. Предел
 * обработки — счётчик продлений. Попытка, которую продлить не удалось, считается потерянной:
 * {@code shouldStop()} её контекста отвечает {@code true}. Отметку отмены коммуникации heartbeat
 * приносит в память тем же тактом.
 */
class CommunicationHeartbeat {

    private static final String RENEW = """
            UPDATE tc_communication_attempt a
               SET locked_until = now() + make_interval(secs => l.lease),
                   lease_renewals = a.lease_renewals + 1
              FROM unnest(CAST(:executionCreatedAt AS timestamptz[]), CAST(:attemptId AS uuid[]),
                          CAST(:leaseToken AS uuid[]), CAST(:maxRenewals AS integer[]),
                          CAST(:lease AS double precision[]))
                   AS l(execution_created_at, id, lease_token, max_renewals, lease)
             WHERE a.execution_created_at >= :oldest
               AND a.execution_created_at = l.execution_created_at
               AND a.id = l.id
               AND a.lease_token = l.lease_token
               AND a.finished_at IS NULL
               AND a.lease_renewals < l.max_renewals
            RETURNING a.id
            """;

    private static final String CANCELLED = """
            SELECT x.attempt_id
              FROM unnest(CAST(:executionCreatedAt AS timestamptz[]), CAST(:attemptId AS uuid[]),
                          CAST(:communicationId AS uuid[]))
                   AS x(execution_created_at, attempt_id, communication_id)
              JOIN tc_communication c ON c.execution_created_at = x.execution_created_at
                   AND c.id = x.communication_id AND c.cancel_requested_at IS NOT NULL
            """;

    private final JdbcClient jdbc;

    private final TransactionTemplate transactions;

    private final Map<UUID, ClaimedCommunication> active = new ConcurrentHashMap<>();

    private final Set<UUID> lost = ConcurrentHashMap.newKeySet();

    private final Set<UUID> cancelled = ConcurrentHashMap.newKeySet();

    private final Map<UUID, Long> renewedAt = new ConcurrentHashMap<>();

    private final LongSupplier nanoClock;

    CommunicationHeartbeat(final DataSource dataSource, final PlatformTransactionManager transactionManager) {
        this(dataSource, transactionManager, System::nanoTime);
    }

    CommunicationHeartbeat(
            final DataSource dataSource,
            final PlatformTransactionManager transactionManager,
            final LongSupplier nanoClock) {
        this.nanoClock = nanoClock;
        this.jdbc = JdbcClient.create(dataSource);
        this.transactions = CommunicationSql.transactions(transactionManager);
    }

    /**
     * Берёт попытку под продление — сразу после захвата.
     *
     * @param attempt захваченная работа
     */
    void track(final ClaimedCommunication attempt) {
        this.lost.remove(attempt.lease().attemptId());
        this.renewedAt.put(attempt.lease().attemptId(), this.nanoClock.getAsLong());
        this.active.put(attempt.lease().attemptId(), attempt);
    }

    /**
     * Снимает попытку с продления — после её закрытия.
     *
     * @param attempt захваченная работа
     */
    void release(final ClaimedCommunication attempt) {
        this.active.remove(attempt.lease().attemptId());
        this.renewedAt.remove(attempt.lease().attemptId());
        this.cancelled.remove(attempt.lease().attemptId());
        this.lost.remove(attempt.lease().attemptId());
    }

    /**
     * Сигнал прекращения из памяти: аренда потеряна или коммуникация отменена.
     *
     * @param attempt захваченная работа
     * @return {@code true}, если работу надо прекратить
     */
    boolean shouldStop(final ClaimedCommunication attempt) {
        final UUID id = attempt.lease().attemptId();
        return this.lost.contains(id) || this.cancelled.contains(id);
    }

    /** Приносит в память отметки отмены коммуникаций всех выполняемых попыток. */
    void refreshCancellations() {
        final List<ClaimedCommunication> attempts = List.copyOf(this.active.values());
        if (!attempts.isEmpty()) {
            this.jdbc
                    .sql(CANCELLED)
                    .param(
                            "executionCreatedAt",
                            CommunicationSql.column(
                                    attempts,
                                    a -> CommunicationSql.parameter(a.target().executionCreatedAt())))
                    .param(
                            "attemptId",
                            CommunicationSql.column(attempts, a -> a.lease().attemptId()))
                    .param(
                            "communicationId",
                            CommunicationSql.column(attempts, a -> a.target().id()))
                    .query(UUID.class)
                    .list()
                    .forEach(this.cancelled::add);
        }
    }

    /**
     * Продлевает все попытки реестра одним запросом.
     *
     * @return попытки, которые продлить не удалось
     */
    Set<UUID> renew() {
        return this.renew(List.copyOf(this.active.values()));
    }

    /**
     * Один такт: продлевает попытки, чей интервал heartbeat истёк бы раньше следующего такта.
     *
     * @param tick период такта потока heartbeat
     * @return попытки, которые продлить не удалось
     */
    Set<UUID> renewDue(final Duration tick) {
        final long now = this.nanoClock.getAsLong();
        return this.renew(this.active.values().stream()
                .filter(attempt ->
                        now - this.renewedAt.getOrDefault(attempt.lease().attemptId(), now) + tick.toNanos()
                                > attempt.lease().heartbeatInterval().toNanos())
                .toList());
    }

    private Set<UUID> renew(final List<ClaimedCommunication> attempts) {
        if (attempts.isEmpty()) {
            return Set.of();
        }
        final Instant oldest = attempts.stream()
                .map(attempt -> attempt.target().executionCreatedAt())
                .min(Comparator.naturalOrder())
                .orElseThrow();
        final List<UUID> renewed = this.transactions.execute(status -> {
            CommunicationSql.forceCustomPlan(this.jdbc);
            return this.jdbc
                    .sql(RENEW)
                    .param("oldest", CommunicationSql.parameter(oldest))
                    .param(
                            "executionCreatedAt",
                            CommunicationSql.column(
                                    attempts,
                                    a -> CommunicationSql.parameter(a.target().executionCreatedAt())))
                    .param(
                            "attemptId",
                            CommunicationSql.column(attempts, a -> a.lease().attemptId()))
                    .param(
                            "leaseToken",
                            CommunicationSql.column(attempts, a -> a.lease().token()))
                    .param(
                            "maxRenewals",
                            CommunicationSql.column(attempts, a -> a.lease().maxRenewals()))
                    .param(
                            "lease",
                            CommunicationSql.column(
                                    attempts,
                                    a -> CommunicationSql.seconds(a.lease().duration())))
                    .query(UUID.class)
                    .list();
        });
        final Set<UUID> notRenewed = new HashSet<>();
        attempts.forEach(attempt -> notRenewed.add(attempt.lease().attemptId()));
        final long renewedNow = this.nanoClock.getAsLong();
        if (renewed != null) {
            renewed.forEach(notRenewed::remove);
            renewed.forEach(id -> this.renewedAt.computeIfPresent(id, (key, previous) -> renewedNow));
        }
        notRenewed.forEach(id -> {
            this.renewedAt.remove(id);
            if (this.active.remove(id) != null) {
                this.lost.add(id);
            }
        });
        return Set.copyOf(notRenewed);
    }
}
