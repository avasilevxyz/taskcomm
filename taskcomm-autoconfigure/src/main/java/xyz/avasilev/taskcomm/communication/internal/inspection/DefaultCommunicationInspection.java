package xyz.avasilev.taskcomm.communication.internal.inspection;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.util.function.SingletonSupplier;
import xyz.avasilev.taskcomm.communication.api.CommunicationStatus;
import xyz.avasilev.taskcomm.communication.api.inspection.CircuitBreakerSnapshot;
import xyz.avasilev.taskcomm.communication.api.inspection.CommunicationAttemptSnapshot;
import xyz.avasilev.taskcomm.communication.api.inspection.CommunicationInspection;
import xyz.avasilev.taskcomm.communication.api.inspection.CommunicationQuery;
import xyz.avasilev.taskcomm.communication.api.inspection.CommunicationSnapshot;
import xyz.avasilev.taskcomm.communication.api.inspection.CommunicationSummary;
import xyz.avasilev.taskcomm.communication.api.inspection.CorrelationKeySnapshot;
import xyz.avasilev.taskcomm.communication.api.inspection.DedupKeySnapshot;
import xyz.avasilev.taskcomm.communication.internal.CommunicationCodec;
import xyz.avasilev.taskcomm.communication.internal.CommunicationSql;
import xyz.avasilev.taskcomm.communication.internal.engine.CommunicationEngine;
import xyz.avasilev.taskcomm.communication.internal.processor.CommunicationProcessorRegistry;
import xyz.avasilev.taskcomm.communication.internal.settings.CommunicationSettings;
import xyz.avasilev.taskcomm.execution.api.ExecutionRef;
import xyz.avasilev.taskcomm.execution.api.HistoryWindow;
import xyz.avasilev.taskcomm.execution.api.Page;
import xyz.avasilev.taskcomm.execution.api.PageRequest;

/**
 * Реализация {@link CommunicationInspection} (docs/adr/0138-inspection-api.md): списки по ключу
 * {@code (execution_created_at, created_at, id)} от новых к старым, в окне чтения истории или в партиции
 * дела, лукап по {@code request_id} попытки, журнал попыток с обменом, реестр корреляции дела, размыкатели
 * этого экземпляра, сводка по системам.
 */
public class DefaultCommunicationInspection implements CommunicationInspection {

    private static final String SELECT = """
            SELECT c.id, c.execution_id, c.execution_created_at, c.direction,
                   coalesce(c.source, c.destination) AS system, c.status, c.status_description, c.phase,
                   c.contract_shape, c.priority, c.created_at, c.updated_at, c.origin_type, c.origin_id,
                   c.origin_attempt_id, c.origin_relation, c.request_payload::text AS request_payload
              FROM tc_communication c
            """;

    private static final String ATTEMPTS = """
            SELECT id, phase, status, status_description, started_at, finished_at, locked_by,
                   request_id, request_at, request_headers::text AS request_headers, request_body,
                   request_payload::text AS request_payload,
                   response_id, response_at, response_status, response_headers::text AS response_headers,
                   response_body, response_payload::text AS response_payload
              FROM tc_communication_attempt
             WHERE execution_created_at = :executionCreatedAt AND communication_id = :id
             ORDER BY created_at, id
            """;

    private static final String CORRELATION = """
            SELECT external_system, correlation_id, communication_id, created_at
              FROM tc_communication_correlation
             WHERE execution_created_at = :executionCreatedAt AND execution_id = :executionId
             ORDER BY created_at, id
            """;

    private static final String COMMUNICATION_CORRELATION = """
            SELECT external_system, correlation_id, communication_id, created_at
              FROM tc_communication_correlation
             WHERE execution_created_at = :executionCreatedAt AND communication_id = :id
             ORDER BY created_at, id
            """;

    private static final String DEDUP = """
            SELECT subject, dedup_key, created_at
              FROM tc_communication_dedup
             WHERE execution_created_at = :executionCreatedAt AND communication_id = :id
             ORDER BY created_at, id
            """;

    // Пространство ключа — направление и система; заданные в условиях, они ведут по индексу ключей.
    private static final String DEDUP_KEY = """
            (c.execution_created_at, c.id) IN (
                SELECT d.execution_created_at, d.communication_id FROM tc_communication_dedup d
                 WHERE d.dedup_key = :dedupKey AND d.subject IS NOT DISTINCT FROM CAST(:dedupSubject AS text)            """;

    private static final String CORRELATION_KEY = """
            (c.execution_created_at, c.id) IN (
                SELECT k.execution_created_at, k.communication_id FROM tc_communication_correlation k
                 WHERE k.external_system = :correlationSystem AND k.correlation_id = :correlationId)
            """;

    private static final String SUMMARY = """
            SELECT c.direction, coalesce(c.source, c.destination) AS system, c.status, count(*) AS n
              FROM tc_communication c
            """;

    private static final String SUMMARY_GROUP = " GROUP BY 1, 2, 3 ORDER BY 1, 2, 3";

    private static final String REQUEST_ID = """
            EXISTS (SELECT 1 FROM tc_communication_attempt a
                     WHERE a.execution_created_at = c.execution_created_at AND a.communication_id = c.id
                       AND a.request_id = :requestId)
            """;

    private final JdbcClient jdbc;

    private final CommunicationCodec codec;

    private final Supplier<CommunicationProcessorRegistry> registry;

    private final Supplier<CommunicationEngine> engine;

    private final Supplier<CommunicationSettings> settings;

    /**
     * Собирает чтение.
     *
     * @param dataSource {@code DataSource} стартера
     * @param codec кодек — для заголовков обмена
     * @param registry реестр процессоров — лениво; по нему — признак осиротевшей системы
     * @param engine движок — лениво; по нему — размыкатели этого экземпляра
     * @param settings итоговые настройки — лениво; по ним — приоритет по умолчанию
     */
    public DefaultCommunicationInspection(
            final DataSource dataSource,
            final CommunicationCodec codec,
            final Supplier<CommunicationProcessorRegistry> registry,
            final Supplier<CommunicationEngine> engine,
            final Supplier<CommunicationSettings> settings) {
        this.jdbc = JdbcClient.create(dataSource);
        this.codec = codec;
        this.registry = SingletonSupplier.of(registry);
        this.engine = SingletonSupplier.of(engine);
        this.settings = SingletonSupplier.of(settings);
    }

    @Override
    public Map<String, Integer> defaultPriorities() {
        final Map<String, Integer> priorities = new TreeMap<>();
        for (final String system : this.registry.get().systems()) {
            priorities.put(system, this.settings.get().priority(system));
        }
        return Collections.unmodifiableMap(priorities);
    }

    @Override
    public Map<String, String> pollers() {
        final Map<String, String> pollers = new TreeMap<>();
        for (final String system : this.registry.get().systems()) {
            pollers.put(system, this.settings.get().pollers().pollerOf(system).name());
        }
        return Collections.unmodifiableMap(pollers);
    }

    @Override
    public List<CorrelationKeySnapshot> correlationKeys(final CommunicationSnapshot communication) {
        return this.jdbc
                .sql(COMMUNICATION_CORRELATION)
                .param(
                        "executionCreatedAt",
                        CommunicationSql.parameter(communication.executionRef().createdAt()))
                .param("id", communication.id())
                .query((rs, rowNum) -> new CorrelationKeySnapshot(
                        rs.getString("external_system"),
                        rs.getString("correlation_id"),
                        rs.getObject("communication_id", UUID.class),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    @Override
    public List<DedupKeySnapshot> dedupKeys(final CommunicationSnapshot communication) {
        return this.jdbc
                .sql(DEDUP)
                .param(
                        "executionCreatedAt",
                        CommunicationSql.parameter(communication.executionRef().createdAt()))
                .param("id", communication.id())
                .query((rs, rowNum) -> new DedupKeySnapshot(
                        rs.getString("subject"),
                        rs.getString("dedup_key"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    @Override
    public Page<CommunicationSnapshot> list(
            final CommunicationQuery query, final HistoryWindow window, final PageRequest page) {
        final List<String> where = new ArrayList<>();
        final Map<String, Object> params = new LinkedHashMap<>();
        query.execution()
                .ifPresentOrElse(
                        execution -> {
                            where.add("c.execution_created_at = :executionCreatedAt AND c.execution_id = :executionId");
                            params.put("executionCreatedAt", CommunicationSql.parameter(execution.createdAt()));
                            params.put("executionId", execution.id());
                        },
                        () -> window(window, where, params));
        filters(query, where, params);
        page.cursor().map(CommunicationCursor::decode).ifPresent(cursor -> {
            where.add("(c.execution_created_at, c.created_at, c.id) < (:cursorExecution, :cursorCreated, :cursorId)");
            params.put("cursorExecution", CommunicationSql.parameter(cursor.executionCreatedAt()));
            params.put("cursorCreated", CommunicationSql.parameter(cursor.createdAt()));
            params.put("cursorId", cursor.id());
        });
        params.put("limit", page.size() + 1);
        final Set<String> known = this.registry.get().systems();
        final List<CommunicationSnapshot> rows = this.jdbc
                .sql(SELECT + where(where)
                        + " ORDER BY c.execution_created_at DESC, c.created_at DESC, c.id DESC LIMIT :limit")
                .params(params)
                .query((rs, rowNum) ->
                        CommunicationSnapshots.communication(rs, !known.contains(rs.getString("system"))))
                .list();
        if (rows.size() <= page.size()) {
            return Page.of(rows, null, window);
        }
        final CommunicationSnapshot last = rows.get(page.size() - 1);
        return Page.of(
                rows.subList(0, page.size()),
                new CommunicationCursor(last.executionRef().createdAt(), last.createdAt(), last.id()).encode(),
                window);
    }

    @Override
    public Optional<CommunicationSnapshot> find(final UUID id, final HistoryWindow window) {
        final List<String> where = new ArrayList<>(List.of("c.id = :id"));
        final Map<String, Object> params = new LinkedHashMap<>(Map.of("id", id));
        window(window, where, params);
        final Set<String> known = this.registry.get().systems();
        return this.jdbc
                .sql(SELECT + where(where))
                .params(params)
                .query((rs, rowNum) ->
                        CommunicationSnapshots.communication(rs, !known.contains(rs.getString("system"))))
                .optional();
    }

    @Override
    public List<CommunicationAttemptSnapshot> attempts(final CommunicationSnapshot communication) {
        return this.jdbc
                .sql(ATTEMPTS)
                .param(
                        "executionCreatedAt",
                        CommunicationSql.parameter(communication.executionRef().createdAt()))
                .param("id", communication.id())
                .query((rs, rowNum) -> CommunicationSnapshots.attempt(rs, this.codec))
                .list();
    }

    @Override
    public List<CorrelationKeySnapshot> correlationKeys(final ExecutionRef execution) {
        return this.jdbc
                .sql(CORRELATION)
                .param("executionCreatedAt", CommunicationSql.parameter(execution.createdAt()))
                .param("executionId", execution.id())
                .query((rs, rowNum) -> new CorrelationKeySnapshot(
                        rs.getString("external_system"),
                        rs.getString("correlation_id"),
                        rs.getObject("communication_id", UUID.class),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    @Override
    public List<CircuitBreakerSnapshot> circuitBreakers() {
        final CommunicationEngine running = this.engine.get();
        return running.breakers().snapshots(running.instance());
    }

    @Override
    public List<CommunicationSummary> summary(final HistoryWindow window) {
        final List<String> where = new ArrayList<>();
        final Map<String, Object> params = new LinkedHashMap<>();
        window(window, where, params);
        final Set<String> known = this.registry.get().systems();
        return this.jdbc
                .sql(SUMMARY + where(where) + SUMMARY_GROUP)
                .params(params)
                .query((rs, rowNum) -> new CommunicationSummary(
                        CommunicationSnapshot.Direction.valueOf(rs.getString("direction")),
                        rs.getString("system"),
                        CommunicationStatus.valueOf(rs.getString("status")),
                        rs.getLong("n"),
                        !known.contains(rs.getString("system"))))
                .list();
    }

    private static void filters(
            final CommunicationQuery query, final List<String> where, final Map<String, Object> params) {
        query.direction().ifPresent(direction -> {
            where.add("c.direction = :direction");
            params.put("direction", direction.name());
        });
        query.externalSystem().ifPresent(system -> {
            where.add("coalesce(c.source, c.destination) = :system");
            params.put("system", system);
        });
        if (!query.statuses().isEmpty()) {
            where.add("c.status IN (:statuses)");
            params.put(
                    "statuses",
                    query.statuses().stream().map(CommunicationStatus::name).toList());
        }
        query.requestId().ifPresent(requestId -> {
            where.add(REQUEST_ID);
            params.put("requestId", requestId);
        });
        query.dedupKey().ifPresent(key -> {
            where.add(DEDUP_KEY
                    + query.direction()
                            .map(direction -> " AND d.direction = :direction")
                            .orElse("")
                    + query.externalSystem()
                            .map(system -> " AND d.external_system = :system")
                            .orElse("")
                    + ")");
            params.put("dedupKey", key);
            params.put("dedupSubject", query.dedupSubject().orElse(null));
        });
        query.correlationId().ifPresent(value -> {
            where.add(CORRELATION_KEY);
            params.put("correlationSystem", query.correlationSystem().orElseThrow());
            params.put("correlationId", value);
        });
    }

    private static String where(final List<String> where) {
        return where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where);
    }

    private static void window(final HistoryWindow window, final List<String> where, final Map<String, Object> params) {
        window.from().ifPresent(from -> {
            where.add("c.execution_created_at >= :windowFrom");
            params.put("windowFrom", CommunicationSql.parameter(from));
        });
        window.to().ifPresent(to -> {
            where.add("c.execution_created_at < :windowTo");
            params.put("windowTo", CommunicationSql.parameter(to));
        });
    }
}
