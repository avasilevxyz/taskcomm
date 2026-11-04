package xyz.avasilev.taskcomm.communication.internal;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.ResourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.avasilev.taskcomm.TaskcommException;
import xyz.avasilev.taskcomm.communication.api.ExchangeMessage;
import xyz.avasilev.taskcomm.execution.api.ExecutionRef;
import xyz.avasilev.taskcomm.execution.api.Origin;
import xyz.avasilev.taskcomm.execution.internal.ExecutionRepository;
import xyz.avasilev.taskcomm.execution.internal.UuidV7;

/**
 * Создание коммуникации вместе с её первой попыткой — одной транзакцией
 * (docs/adr/0054-communication-attempt-lifecycle.md). Попытка открыта и не захвачена; у входящей в неё
 * сразу пишется присланный запрос (docs/adr/0055-communication-exchange-storage.md).
 *
 * <ul>
 *   <li><b>Обычное создание</b> — в транзакции потребителя, если она есть, иначе в своей короткой
 *       (docs/adr/0094-creation-always-in-transaction.md). Транзакция потребителя на другом
 *       {@code DataSource} — исключение: атомарность пропала бы молча.
 *   <li><b>Неразобранное тело</b> — коммуникация сразу {@code FAILED}, попытка закрыта {@code FAILED}
 *       с причиной разбора; повторов нет.
 *   <li><b>Синхронный приём</b> — всегда своя короткая транзакция, попытка сразу под арендой этого
 *       экземпляра, коммуникация {@code RUNNING}: поллер её не возьмёт
 *       (docs/adr/0133-synchronous-inbound-reception.md).
 * </ul>
 */
public class CommunicationCreator {

    private static final String INSERT_COMMUNICATION = """
            WITH ready AS (SELECT greatest(coalesce(CAST(:notBefore AS timestamptz), now()), now()) AS at)
            INSERT INTO tc_communication (
                id, created_at, updated_at, execution_id, execution_created_at, direction, source, destination,
                request_payload, contract_shape, phase, status, status_description, priority, next_attempt_at,
                claim_order_at, retry_cycle_started_at, origin_type, origin_id, origin_attempt_id, origin_relation)
            SELECT :id, now(), now(), :executionId, :executionCreatedAt, :direction, CAST(:source AS text),
                   CAST(:destination AS text), CAST(:payload AS jsonb), CAST(:shape AS text), 'PROCESS', :status,
                   CAST(:reason AS text), :priority, ready.at,
                   ready.at + make_interval(secs => :priority * :deadlineStep), now(),
                   CAST(:originType AS text), CAST(:originId AS uuid), CAST(:originAttemptId AS uuid),
                   CAST(:originRelation AS text)
              FROM ready
            RETURNING created_at
            """;

    private static final String INSERT_ATTEMPT = """
            INSERT INTO tc_communication_attempt (
                id, created_at, updated_at, execution_created_at, communication_id, phase, locked_by, locked_until,
                lease_token, lease_renewals, started_at, finished_at, status, status_description,
                request_id, request_at, request_headers, request_body)
            VALUES (:id, now(), now(), :executionCreatedAt, :communicationId, 'PROCESS', CAST(:lockedBy AS text),
                    now() + make_interval(secs => CAST(:lease AS double precision)), CAST(:leaseToken AS uuid), 0,
                    CASE WHEN :started THEN now() END, CASE WHEN :failed THEN now() END, CAST(:status AS text),
                    CAST(:reason AS text), CAST(:requestId AS text), CAST(:requestAt AS timestamptz),
                    CAST(:requestHeaders AS jsonb), CAST(:requestBody AS text))
            """;

    private final JdbcClient jdbc;

    private final DataSource dataSource;

    private final boolean bindingVisible;

    private final TransactionTemplate joining;

    private final TransactionTemplate own;

    private final ExecutionRepository executions;

    private final CommunicationPartitionHorizon horizon;

    private final CommunicationCodec codec;

    private final UuidV7 ids;

    /**
     * Собирает создание коммуникаций.
     *
     * @param dataSource {@code DataSource} стартера
     * @param transactionManager менеджер транзакций стартера
     * @param executions таблица дел
     * @param codec кодек — заголовки ложатся в {@code jsonb}
     * @param ids генератор идентификаторов
     * @param truncUnit детализация партиций для {@code date_trunc} — для оживления старой партиции
     */
    public CommunicationCreator(
            final DataSource dataSource,
            final PlatformTransactionManager transactionManager,
            final ExecutionRepository executions,
            final CommunicationCodec codec,
            final UuidV7 ids,
            final String truncUnit) {
        this.jdbc = JdbcClient.create(dataSource);
        this.dataSource = dataSource;
        this.bindingVisible = transactionManager instanceof ResourceTransactionManager;
        this.joining = new TransactionTemplate(transactionManager);
        this.joining.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        this.joining.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.own = CommunicationSql.transactions(transactionManager);
        this.executions = executions;
        this.horizon = new CommunicationPartitionHorizon(this.jdbc, truncUnit);
        this.codec = codec;
        this.ids = ids;
    }

    /**
     * Создаёт коммуникацию и первую попытку.
     *
     * @param communication что создать
     * @return созданная коммуникация
     * @throws TaskcommException если транзакция потребителя активна на другом {@code DataSource}
     */
    CreatedCommunication create(final NewCommunication communication) {
        final boolean sync = communication.start().lease() != null;
        if (!sync) {
            this.requireBoundToConsumerTransaction();
        }
        return (sync ? this.own : this.joining).execute(status -> this.insert(communication));
    }

    private CreatedCommunication insert(final NewCommunication communication) {
        final ExecutionRef execution = this.execution(communication.placement());
        final UUID id = this.ids.next();
        final NewCommunication.Start start = communication.start();
        final Optional<Origin> origin =
                Optional.ofNullable(communication.placement().origin());
        final boolean inbound = communication.direction() == Direction.INBOUND;
        final OffsetDateTime createdAt = this.jdbc
                .sql(INSERT_COMMUNICATION)
                .param("id", id)
                .param("executionId", execution.id())
                .param("executionCreatedAt", CommunicationSql.parameter(execution.createdAt()))
                .param("direction", communication.direction().name())
                .param("source", inbound ? communication.system() : null)
                .param("destination", inbound ? null : communication.system())
                .param("payload", communication.payload())
                .param(
                        "shape",
                        Optional.ofNullable(communication.shape())
                                .map(Enum::name)
                                .orElse(null))
                .param("status", status(start))
                .param("reason", start.failure())
                .param("priority", communication.scheduling().priority())
                .param(
                        "deadlineStep",
                        CommunicationSql.seconds(communication.scheduling().deadlineStep()))
                .param(
                        "notBefore",
                        Optional.ofNullable(communication.scheduling().notBefore())
                                .map(CommunicationSql::parameter)
                                .orElse(null))
                .param("originType", origin.map(o -> o.type().name()).orElse(null))
                .param("originId", origin.map(Origin::id).orElse(null))
                .param("originAttemptId", origin.map(Origin::attemptId).orElse(null))
                .param("originRelation", origin.map(o -> o.relation().name()).orElse(null))
                .query(OffsetDateTime.class)
                .single();
        final UUID attemptId =
                Optional.ofNullable(start.lease()).map(AttemptLease::attemptId).orElseGet(this.ids::next);
        this.insertAttempt(attemptId, id, execution, start);
        return new CreatedCommunication(id, execution, createdAt.toInstant(), attemptId);
    }

    private ExecutionRef execution(final NewCommunication.Placement placement) {
        if (placement.execution() == null) {
            return this.executions.create();
        }
        this.horizon.revive(placement.execution().createdAt());
        return placement.execution();
    }

    private void insertAttempt(
            final UUID attemptId,
            final UUID communicationId,
            final ExecutionRef execution,
            final NewCommunication.Start start) {
        final Optional<ExchangeMessage> request = Optional.ofNullable(start.request());
        final Optional<AttemptLease> lease = Optional.ofNullable(start.lease());
        final boolean failed = start.failure() != null;
        this.jdbc
                .sql(INSERT_ATTEMPT)
                .param("id", attemptId)
                .param("executionCreatedAt", CommunicationSql.parameter(execution.createdAt()))
                .param("communicationId", communicationId)
                .param("lockedBy", lease.map(l -> start.lockedBy()).orElse(null))
                .param(
                        "lease",
                        lease.map(l -> CommunicationSql.seconds(l.duration())).orElse(null))
                .param("leaseToken", lease.map(AttemptLease::token).orElse(null))
                .param("started", lease.isPresent() || failed)
                .param("failed", failed)
                .param("status", failed ? "FAILED" : null)
                .param("reason", start.failure())
                .param("requestId", request.flatMap(ExchangeMessage::id).orElse(null))
                .param(
                        "requestAt",
                        request.flatMap(ExchangeMessage::at)
                                .map(CommunicationSql::parameter)
                                .orElse(null))
                .param(
                        "requestHeaders",
                        request.map(r -> this.codec.write(r.headers().asMap())).orElse(null))
                .param("requestBody", request.map(ExchangeMessage::body).orElse(null))
                .update();
    }

    private static String status(final NewCommunication.Start start) {
        if (start.failure() != null) {
            return "FAILED";
        }
        return start.lease() != null ? "RUNNING" : "PENDING";
    }

    private void requireBoundToConsumerTransaction() {
        if (this.bindingVisible
                && TransactionSynchronizationManager.isActualTransactionActive()
                && !TransactionSynchronizationManager.hasResource(this.dataSource)) {
            throw new TaskcommException("Коммуникация создаётся внутри транзакции, которая не держит DataSource "
                    + "taskcomm: бизнес-данные, по-видимому, в другой базе. Создать её атомарно с ними нельзя, а молча "
                    + "открыть отдельную транзакцию значило бы потерять атомарность незаметно. Создавайте "
                    + "коммуникацию вне этой транзакции или пометьте нужный DataSource @TaskcommDataSource "
                    + "(docs/adr/0094-creation-always-in-transaction.md).");
        }
    }
}
