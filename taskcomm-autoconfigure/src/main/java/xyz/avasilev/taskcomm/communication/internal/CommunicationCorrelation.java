package xyz.avasilev.taskcomm.communication.internal;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import xyz.avasilev.taskcomm.communication.api.CorrelationKey;
import xyz.avasilev.taskcomm.execution.api.ExecutionRef;
import xyz.avasilev.taskcomm.execution.internal.UuidV7;

/**
 * Реестр корреляции {@code tc_communication_correlation} (docs/adr/0040-communication-correlation.md,
 * docs/adr/0075-correlation-key-scoped-by-system.md): ключ уникален в пределах внешней системы, от которой
 * ждут колбэк, и указывает на якорь — коммуникацию с её делом. Реестр накапливает: новый ключ прежние
 * не снимает. Тот же ключ той же системы у другой коммуникации — якорь не меняется, WARN.
 *
 * <p>Таблица непартиционирована: один индексный доступ даёт и якорь, и ключ партиционирования его дела.
 * Методы работают в транзакции вызывающего.
 */
@Slf4j
class CommunicationCorrelation {

    private static final String REGISTER = """
            INSERT INTO tc_communication_correlation (
                id, created_at, updated_at, external_system, correlation_id, communication_id, execution_id,
                execution_created_at)
            VALUES (:id, now(), now(), :system, :correlationId, :communicationId, :executionId, :executionCreatedAt)
            ON CONFLICT ON CONSTRAINT tc_communication_correlation_key_uq DO NOTHING
            """;

    private static final String FIND = """
            SELECT communication_id, execution_id, execution_created_at FROM tc_communication_correlation
             WHERE external_system = :system AND correlation_id = :correlationId
            """;

    private static final String WAITS = """
            SELECT count(*) FROM tc_communication_correlation
             WHERE communication_id = :id AND external_system = :system
            """;

    private final UuidV7 ids;

    CommunicationCorrelation(final UuidV7 ids) {
        this.ids = ids;
    }

    /**
     * Регистрирует ключ за коммуникацией.
     *
     * @param jdbc клиент в транзакции
     * @param anchor коммуникация-якорь
     * @param execution её дело
     * @param ownSystem внешняя сторона якоря — система ключа, если не названа явно
     * @param key ключ
     */
    void register(
            final JdbcClient jdbc,
            final CommunicationRef anchor,
            final ExecutionRef execution,
            final String ownSystem,
            final CorrelationKey key) {
        final String system = key.externalSystem().orElse(ownSystem);
        final int inserted = jdbc.sql(REGISTER)
                .param("id", this.ids.next())
                .param("system", system)
                .param("correlationId", key.correlationId())
                .param("communicationId", anchor.id())
                .param("executionId", execution.id())
                .param("executionCreatedAt", CommunicationSql.parameter(execution.createdAt()))
                .update();
        if (inserted == 0) {
            this.find(jdbc, system, key.correlationId())
                    .filter(found -> !found.communicationId().equals(anchor.id()))
                    .ifPresent(found -> log.warn(
                            "Ключ корреляции '{}' системы '{}' уже занят коммуникацией {}; якорь не меняется, "
                                    + "коммуникация {} колбэков по нему не получит "
                                    + "(docs/adr/0040-communication-correlation.md).",
                            key.correlationId(),
                            system,
                            found.communicationId(),
                            anchor.id()));
        }
    }

    /**
     * Якорь по ключу.
     *
     * @param jdbc клиент в транзакции
     * @param system система, от которой пришёл колбэк
     * @param correlationId ключ
     * @return якорь или пусто
     */
    Optional<Anchor> find(final JdbcClient jdbc, final String system, final String correlationId) {
        return jdbc.sql(FIND)
                .param("system", system)
                .param("correlationId", correlationId)
                .query((rs, rowNum) -> new Anchor(
                        rs.getObject("communication_id", UUID.class),
                        new ExecutionRef(
                                rs.getObject("execution_id", UUID.class),
                                rs.getObject("execution_created_at", OffsetDateTime.class)
                                        .toInstant())))
                .optional();
    }

    /**
     * Ждёт ли исходящая ответа: зарегистрирован хотя бы один ключ её системы
     * (docs/adr/0104-wait-defined-by-correlation-key.md).
     *
     * @param jdbc клиент в транзакции
     * @param target исходящая
     * @param destination её система
     * @return {@code true}, если ключ есть
     */
    static boolean awaits(final JdbcClient jdbc, final CommunicationRef target, final String destination) {
        return jdbc.sql(WAITS)
                        .param("id", target.id())
                        .param("system", destination)
                        .query(Integer.class)
                        .single()
                > 0;
    }

    /**
     * Якорь ключа.
     *
     * @param communicationId коммуникация
     * @param execution её дело
     */
    record Anchor(UUID communicationId, ExecutionRef execution) {

        /**
         * Ключ строки якоря.
         *
         * @return ключ строки
         */
        CommunicationRef target() {
            return new CommunicationRef(this.communicationId, this.execution.createdAt());
        }
    }
}
