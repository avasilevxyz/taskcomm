package xyz.avasilev.taskcomm.communication.internal;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Подстраховочный поллер коммуникаций (docs/adr/0086-async-response-in-outbound.md): находит исходящие
 * в {@code AWAITING_RESPONSE}, чей срок ожидания ответа — {@code next_attempt_at} — истёк, и отправляет
 * их повторно с backoff в пределах лимита попыток отправки; исчерпано — {@code FAILED} «ответ не получен
 * за срок». Повторная отправка даёт внешней системе второй запрос — её идемпотентность обязательна.
 *
 * <p>Такт — одна короткая транзакция; строки берутся {@code FOR UPDATE SKIP LOCKED}, поэтому поллеры
 * нескольких экземпляров друг другу не мешают. Системы без процессора не трогаются
 * (docs/adr/0105-orphan-communications.md). Ищет только в активных партициях.
 */
@Slf4j
class CommunicationSafetyPoller {

    /** Сколько истёкших ожиданий берёт один такт. */
    static final int LIMIT = 100;

    private static final String EXPIRED = """
            SELECT c.id, c.execution_created_at, coalesce(c.source, c.destination) AS system,
                   (SELECT a.id FROM tc_communication_attempt a
                     WHERE a.execution_created_at = c.execution_created_at AND a.communication_id = c.id
                       AND a.phase = 'PROCESS'
                     ORDER BY a.started_at DESC NULLS LAST
                     LIMIT 1) AS last_send
              FROM tc_communication c
             WHERE c.status = 'AWAITING_RESPONSE'
               AND c.next_attempt_at <= now()
               AND c.execution_created_at >= :activeFrom
               AND coalesce(c.source, c.destination) = ANY(:systems)
             ORDER BY c.next_attempt_at
             LIMIT :limit
               FOR UPDATE OF c SKIP LOCKED
            """;

    private final JdbcClient jdbc;

    private final TransactionTemplate transactions;

    private final CommunicationSettings settings;

    private final CommunicationRetries retries;

    private final Set<String> systems;

    CommunicationSafetyPoller(
            final DataSource dataSource,
            final PlatformTransactionManager transactionManager,
            final CommunicationSettings settings,
            final CommunicationRetries retries,
            final Set<String> systems) {
        this.jdbc = JdbcClient.create(dataSource);
        this.transactions = CommunicationSql.transactions(transactionManager);
        this.settings = settings;
        this.retries = retries;
        this.systems = Set.copyOf(systems);
    }

    /** Один такт: до {@link #LIMIT} истёкших ожиданий. */
    void tick() {
        if (this.systems.isEmpty()) {
            return;
        }
        this.transactions.executeWithoutResult(status -> {
            CommunicationSql.forceCustomPlan(this.jdbc);
            this.jdbc
                    .sql(EXPIRED)
                    .param(
                            "activeFrom",
                            CommunicationSql.parameter(CommunicationPartitionHorizon.activeFrom(this.jdbc)))
                    .param("systems", this.systems.toArray(String[]::new))
                    .param("limit", LIMIT)
                    .query((rs, rowNum) -> new CommunicationRetries.Closed(
                            new CommunicationRef(
                                    rs.getObject("id", UUID.class),
                                    rs.getObject("execution_created_at", OffsetDateTime.class)
                                            .toInstant()),
                            rs.getObject("last_send", UUID.class),
                            rs.getString("system"),
                            Direction.OUTBOUND,
                            CommunicationPhase.PROCESS))
                    .list()
                    .forEach(this::resend);
        });
    }

    private void resend(final CommunicationRetries.Closed lastSend) {
        log.info(
                "Ответ на исходящую {} в '{}' не получен за срок — повторная отправка или FAILED",
                lastSend.target().id(),
                lastSend.system());
        this.retries.retry(
                this.jdbc,
                lastSend,
                this.retries.attempts(this.jdbc, lastSend),
                "ответ не получен за " + this.settings.responseTimeout(lastSend.system()));
    }
}
