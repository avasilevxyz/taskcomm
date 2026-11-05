package xyz.avasilev.taskcomm.communication.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import xyz.avasilev.taskcomm.communication.api.CorrelationKey;
import xyz.avasilev.taskcomm.execution.api.ExecutionRef;
import xyz.avasilev.taskcomm.execution.api.Origin;
import xyz.avasilev.taskcomm.execution.api.OriginRelation;
import xyz.avasilev.taskcomm.execution.api.OriginType;
import xyz.avasilev.taskcomm.execution.internal.UuidV7;

/**
 * Ключи дедупликации и корреляции при создании коммуникации — в транзакции создания.
 *
 * <ul>
 *   <li><b>Дедупликация</b> (docs/adr/0076-communication-deduplication.md): ключи проверяются под
 *       блокировкой, удерживаемой до коммита; совпал — коммуникация создаётся сразу {@code SKIPPED},
 *       её ключи не хранятся.
 *   <li><b>Корреляция входящей</b> (docs/adr/0040-communication-correlation.md): известный ключ — входящая
 *       попадает в дело якоря с указателем «ответ на» него; неизвестный — регистрируется за ней. Якорь,
 *       чья партиция удалена, — новое дело и WARN.
 *   <li><b>Корреляция исходящей</b> — все ключи регистрируются за ней; ключ её системы включает ожидание
 *       ответа (docs/adr/0104-wait-defined-by-correlation-key.md).
 * </ul>
 */
@Slf4j
class CreationKeys {

    private static final String EXISTS = """
            SELECT count(*) FROM tc_communication WHERE execution_created_at = :executionCreatedAt AND id = :id
            """;

    private final CommunicationDedup dedup;

    private final CommunicationCorrelation correlation;

    CreationKeys(final UuidV7 ids) {
        this.dedup = new CommunicationDedup(ids);
        this.correlation = new CommunicationCorrelation(ids);
    }

    /**
     * Проверки до записи: ключ дедупликации — только при {@code READ COMMITTED}.
     *
     * @param jdbc клиент в транзакции создания
     * @param communication что создаётся
     */
    static void beforeWrite(final JdbcClient jdbc, final NewCommunication communication) {
        if (!communication.keys().dedup().isEmpty()) {
            CommunicationDedup.requireReadCommitted(jdbc);
        }
    }

    /**
     * Дело входящей по известному ключу корреляции.
     *
     * @param jdbc клиент в транзакции создания
     * @param communication что создаётся
     * @return дело якоря и указатель «ответ на» или пусто — дело по обычным правилам
     */
    Optional<NewCommunication.Placement> anchorPlacement(final JdbcClient jdbc, final NewCommunication communication) {
        if (communication.direction() != Direction.INBOUND) {
            return Optional.empty();
        }
        for (final CorrelationKey key : communication.keys().correlation()) {
            final Optional<CommunicationCorrelation.Anchor> anchor = this.correlation.find(
                    jdbc, key.externalSystem().orElse(communication.system()), key.correlationId());
            if (anchor.isPresent()) {
                if (exists(jdbc, anchor.get().target())) {
                    return Optional.of(new NewCommunication.Placement(
                            anchor.get().execution(),
                            new Origin(
                                    OriginType.COMMUNICATION,
                                    anchor.get().communicationId(),
                                    null,
                                    OriginRelation.RESPONSE)));
                }
                log.warn(
                        "Якорь {} ключа корреляции '{}' не найден — его партиция, по-видимому, удалена; входящая "
                                + "заводит новое дело (docs/adr/0040-communication-correlation.md).",
                        anchor.get().communicationId(),
                        key.correlationId());
            }
        }
        return Optional.empty();
    }

    /**
     * Ищет дубликат.
     *
     * @param jdbc клиент в транзакции создания
     * @param owner коммуникация и пространство ключей
     * @param communication что создаётся
     * @return совпадение или пусто
     */
    Optional<CommunicationDedup.Match> duplicate(
            final JdbcClient jdbc, final CommunicationDedup.Owner owner, final NewCommunication communication) {
        return communication.keys().dedup().isEmpty()
                ? Optional.empty()
                : this.dedup.check(jdbc, owner, communication.keys().dedup());
    }

    /**
     * Записывает ключи созданной коммуникации, не оказавшейся дубликатом.
     *
     * @param jdbc клиент в транзакции создания
     * @param owner коммуникация и пространство ключей
     * @param execution дело коммуникации
     * @param communication что создано
     */
    void store(
            final JdbcClient jdbc,
            final CommunicationDedup.Owner owner,
            final ExecutionRef execution,
            final NewCommunication communication) {
        this.dedup.insert(
                jdbc, owner, communication.keys().dedup(), communication.start().failure() != null);
        final List<CorrelationKey> unknown = new ArrayList<>();
        for (final CorrelationKey key : communication.keys().correlation()) {
            if (communication.direction() == Direction.OUTBOUND
                    || this.correlation
                            .find(jdbc, key.externalSystem().orElse(communication.system()), key.correlationId())
                            .isEmpty()) {
                unknown.add(key);
            }
        }
        unknown.forEach(key -> this.correlation.register(jdbc, owner.target(), execution, communication.system(), key));
    }

    private static boolean exists(final JdbcClient jdbc, final CommunicationRef target) {
        return jdbc.sql(EXISTS)
                        .param("executionCreatedAt", CommunicationSql.parameter(target.executionCreatedAt()))
                        .param("id", target.id())
                        .query(Integer.class)
                        .single()
                > 0;
    }
}
