package xyz.avasilev.taskcomm.communication.internal.inspection;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import xyz.avasilev.taskcomm.communication.api.Communication;
import xyz.avasilev.taskcomm.communication.api.CommunicationStatus;
import xyz.avasilev.taskcomm.communication.internal.CommunicationCodec;
import xyz.avasilev.taskcomm.communication.internal.CommunicationRef;
import xyz.avasilev.taskcomm.communication.internal.CommunicationSql;
import xyz.avasilev.taskcomm.communication.internal.Direction;
import xyz.avasilev.taskcomm.communication.internal.engine.CommunicationViews;
import xyz.avasilev.taskcomm.communication.internal.processor.RegisteredProcessor;
import xyz.avasilev.taskcomm.execution.api.ExecutionRef;
import xyz.avasilev.taskcomm.execution.api.Origin;
import xyz.avasilev.taskcomm.execution.api.OriginRelation;
import xyz.avasilev.taskcomm.execution.api.OriginType;

/**
 * Чтение коммуникаций для потребителя: одной по ключу строки и всех коммуникаций дела с одной
 * внешней системой (docs/adr/0110-execution-composition-reads-in-subsystems.md). Чтение идёт в партиции
 * дела; доменная форма запроса восстанавливается по дженерику процессора.
 */
public class CommunicationReader {

    private static final String SELECT = """
            SELECT id, execution_id, execution_created_at, direction, coalesce(source, destination) AS system,
                   request_payload::text AS payload, priority, status, status_description, created_at,
                   origin_type, origin_id, origin_attempt_id, origin_relation
              FROM tc_communication
            """;

    private static final String IN_EXECUTION = SELECT + """
             WHERE execution_created_at = :executionCreatedAt AND execution_id = :executionId
               AND coalesce(source, destination) = :system
             ORDER BY created_at, id
            """;

    private static final String ONE = SELECT + " WHERE execution_created_at = :executionCreatedAt AND id = :id";

    private final JdbcClient jdbc;

    private final CommunicationCodec codec;

    public CommunicationReader(final DataSource dataSource, final CommunicationCodec codec) {
        this.jdbc = JdbcClient.create(dataSource);
        this.codec = codec;
    }

    /**
     * Коммуникация по ключу строки.
     *
     * @param target ключ строки
     * @param processor процессор системы
     * @return снимок коммуникации
     */
    public Communication<?> one(final CommunicationRef target, final RegisteredProcessor processor) {
        return this.jdbc
                .sql(ONE)
                .param("executionCreatedAt", CommunicationSql.parameter(target.executionCreatedAt()))
                .param("id", target.id())
                .query(this.mapper(processor))
                .single();
    }

    /**
     * Коммуникации дела с системой процессора, в порядке создания.
     *
     * @param execution дело
     * @param processor процессор системы
     * @return снимки коммуникаций
     */
    public List<Communication<?>> inExecution(final ExecutionRef execution, final RegisteredProcessor processor) {
        return this.jdbc
                .sql(IN_EXECUTION)
                .param("executionCreatedAt", CommunicationSql.parameter(execution.createdAt()))
                .param("executionId", execution.id())
                .param("system", processor.system())
                .query(this.mapper(processor))
                .list();
    }

    private RowMapper<Communication<?>> mapper(final RegisteredProcessor processor) {
        return (rs, rowNum) -> CommunicationViews.of(
                Direction.valueOf(rs.getString("direction")),
                row(rs),
                this.codec.read(rs.getString("payload"), processor.types().request()));
    }

    private static CommunicationViews.Row row(final ResultSet rs) throws SQLException {
        final String originType = rs.getString("origin_type");
        return new CommunicationViews.Row(
                rs.getObject("id", UUID.class),
                new ExecutionRef(
                        rs.getObject("execution_id", UUID.class),
                        rs.getObject("execution_created_at", OffsetDateTime.class)
                                .toInstant()),
                rs.getString("system"),
                rs.getInt("priority"),
                CommunicationStatus.valueOf(rs.getString("status")),
                rs.getString("status_description"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                originType == null
                        ? null
                        : new Origin(
                                OriginType.valueOf(originType),
                                rs.getObject("origin_id", UUID.class),
                                rs.getObject("origin_attempt_id", UUID.class),
                                OriginRelation.valueOf(rs.getString("origin_relation"))));
    }
}
