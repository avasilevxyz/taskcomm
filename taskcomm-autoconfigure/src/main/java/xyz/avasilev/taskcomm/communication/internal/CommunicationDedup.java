package xyz.avasilev.taskcomm.communication.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import xyz.avasilev.taskcomm.TaskcommException;
import xyz.avasilev.taskcomm.communication.api.DedupKey;
import xyz.avasilev.taskcomm.execution.internal.UuidV7;

/**
 * Дедупликация коммуникаций (docs/adr/0076-communication-deduplication.md). Ключи лежат
 * в партиционированной {@code tc_communication_dedup}; глобальность держит не уникальный индекс,
 * а поиск <b>по всем партициям</b> под {@code pg_advisory_xact_lock}. Ключ блокировки —
 * {@code (0x74630003, hashtext(направление, система, ключ))} у ключа по всей истории и тот же с предметом
 * вместо ключа у ключа по последней (docs/adr/0136-communication-dedup-and-callbacks.md); несколько
 * ключей — блокировки в порядке хешей.
 *
 * <ul>
 *   <li>По всей истории — дубликат, если ключ есть у <b>любой</b> другой коммуникации.
 *   <li>По последней — дубликат, если ключ совпал с ключом последней <b>успешной</b> с тем же
 *       предметом или любой <b>незавершённой</b> с тем же предметом и ключом.
 * </ul>
 *
 * <p>Ключи дубликата не хранятся. Свой ключ коммуникация повторно не проверяет. Методы работают
 * в транзакции вызывающего; ключ при создании — только при {@code READ COMMITTED}
 * (docs/adr/0095-dedup-requires-read-committed.md).
 */
class CommunicationDedup {

    /** Первая часть ключа advisory-блокировки дедупликации. */
    static final int LOCK = 0x74630003;

    private static final String HASH = "SELECT hashtext(:text)";

    private static final String WHOLE_HISTORY = """
            SELECT communication_id FROM tc_communication_dedup
             WHERE direction = :direction AND external_system = :system AND subject IS NULL
               AND dedup_key = :key AND communication_id <> :self
             LIMIT 1
            """;

    private static final String LAST_SUCCEEDED = """
            SELECT communication_id, dedup_key FROM tc_communication_dedup
             WHERE direction = :direction AND external_system = :system AND subject = :subject
               AND succeeded_at IS NOT NULL AND communication_id <> :self
             ORDER BY succeeded_at DESC
             LIMIT 1
            """;

    private static final String UNFINISHED = """
            SELECT communication_id FROM tc_communication_dedup
             WHERE direction = :direction AND external_system = :system AND subject = :subject
               AND dedup_key = :key AND finished_at IS NULL AND communication_id <> :self
             LIMIT 1
            """;

    private static final String OWN = """
            SELECT count(*) FROM tc_communication_dedup
             WHERE execution_created_at = :executionCreatedAt AND communication_id = :self
               AND direction = :direction AND external_system = :system
               AND subject IS NOT DISTINCT FROM CAST(:subject AS text) AND dedup_key = :key
            """;

    private static final String INSERT = """
            INSERT INTO tc_communication_dedup (
                id, created_at, updated_at, execution_created_at, communication_id, direction, external_system,
                subject, dedup_key, finished_at)
            VALUES (:id, now(), now(), :executionCreatedAt, :self, :direction, :system, CAST(:subject AS text), :key,
                    CASE WHEN :finished THEN now() END)
            """;

    private static final String FINISH = """
            UPDATE tc_communication_dedup
               SET finished_at = now(), succeeded_at = CASE WHEN :succeeded THEN now() END, updated_at = now()
             WHERE execution_created_at = :executionCreatedAt AND communication_id = :id AND finished_at IS NULL
            """;

    private final UuidV7 ids;

    CommunicationDedup(final UuidV7 ids) {
        this.ids = ids;
    }

    /**
     * Проверяет, что транзакция — {@code READ COMMITTED} по факту, в базе: при более строгом уровне
     * снимок берётся до ожидания блокировки, и поиск не увидел бы ключ, закоммиченный за время ожидания.
     *
     * @param jdbc клиент в транзакции создания
     * @throws TaskcommException если уровень выше
     */
    static void requireReadCommitted(final JdbcClient jdbc) {
        final String level = jdbc.sql("SELECT current_setting('transaction_isolation')")
                .query(String.class)
                .single();
        if (!"read committed".equals(level)) {
            throw new TaskcommException("Коммуникация с ключом дедупликации создаётся в транзакции с уровнем «"
                    + level + "»: при уровне выше READ COMMITTED проверка не увидела бы ключ, закоммиченный за время "
                    + "ожидания блокировки, и дубликат прошёл бы молча. Задайте ключ методом процессора "
                    + "beforeProcess или создавайте коммуникацию вне своей транзакции "
                    + "(docs/adr/0095-dedup-requires-read-committed.md).");
        }
    }

    /**
     * Берёт блокировки ключей и ищет совпадение. Свой уже записанный ключ пропускается.
     *
     * @param jdbc клиент в транзакции
     * @param owner коммуникация-владелец ключей и пространство ключей
     * @param keys ключи
     * @return совпадение или пусто
     */
    Optional<Match> check(final JdbcClient jdbc, final Owner owner, final List<DedupKey> keys) {
        final List<Locked> locked = new ArrayList<>();
        for (final DedupKey key : keys) {
            final String scope = key instanceof final DedupKey.LastSucceeded last ? last.subject() : key.key();
            final int hash = jdbc.sql(HASH)
                    .param("text", owner.direction().name() + '|' + owner.system() + '|' + scope)
                    .query(Integer.class)
                    .single();
            locked.add(new Locked(hash, key));
        }
        locked.sort(Comparator.comparingInt(Locked::hash));
        locked.stream()
                .map(Locked::hash)
                .distinct()
                .forEach(hash -> jdbc.sql("SELECT pg_advisory_xact_lock(:lock, :hash)::text")
                        .param("lock", LOCK)
                        .param("hash", hash)
                        .query(String.class)
                        .single());
        for (final DedupKey key : keys) {
            if (!this.own(jdbc, owner, key)) {
                final Optional<UUID> found = find(jdbc, owner, key);
                if (found.isPresent()) {
                    return Optional.of(new Match(found.get(), key.key()));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Записывает ключи коммуникации, кроме уже записанных.
     *
     * @param jdbc клиент в транзакции, где проверены ключи
     * @param owner коммуникация-владелец
     * @param keys ключи
     * @param finished коммуникация уже терминальна — создана сразу {@code FAILED}
     */
    void insert(final JdbcClient jdbc, final Owner owner, final List<DedupKey> keys, final boolean finished) {
        for (final DedupKey key : keys) {
            if (!this.own(jdbc, owner, key)) {
                jdbc.sql(INSERT)
                        .param("id", this.ids.next())
                        .param(
                                "executionCreatedAt",
                                CommunicationSql.parameter(owner.target().executionCreatedAt()))
                        .param("self", owner.target().id())
                        .param("direction", owner.direction().name())
                        .param("system", owner.system())
                        .param("subject", subject(key))
                        .param("key", key.key())
                        .param("finished", finished)
                        .update();
            }
        }
    }

    /**
     * Отмечает ключи коммуникации терминальными — в транзакции, делающей её терминальной.
     *
     * @param jdbc клиент в этой транзакции
     * @param target коммуникация
     * @param succeeded успешна ли она
     */
    static void finish(final JdbcClient jdbc, final CommunicationRef target, final boolean succeeded) {
        jdbc.sql(FINISH)
                .param("succeeded", succeeded)
                .param("executionCreatedAt", CommunicationSql.parameter(target.executionCreatedAt()))
                .param("id", target.id())
                .update();
    }

    /**
     * Причина {@code SKIPPED} дубликата.
     *
     * @param match совпадение
     * @return причина
     */
    static String reason(final Match match) {
        return "дубликат коммуникации " + match.duplicateOf() + " по ключу " + match.key();
    }

    private boolean own(final JdbcClient jdbc, final Owner owner, final DedupKey key) {
        return jdbc.sql(OWN)
                        .param(
                                "executionCreatedAt",
                                CommunicationSql.parameter(owner.target().executionCreatedAt()))
                        .param("self", owner.target().id())
                        .param("direction", owner.direction().name())
                        .param("system", owner.system())
                        .param("subject", subject(key))
                        .param("key", key.key())
                        .query(Integer.class)
                        .single()
                > 0;
    }

    private static Optional<UUID> find(final JdbcClient jdbc, final Owner owner, final DedupKey key) {
        return switch (key) {
            case DedupKey.WholeHistory history ->
                jdbc.sql(WHOLE_HISTORY)
                        .param("direction", owner.direction().name())
                        .param("system", owner.system())
                        .param("key", history.key())
                        .param("self", owner.target().id())
                        .query(UUID.class)
                        .optional();
            case DedupKey.LastSucceeded last -> {
                final Optional<UUID> unfinished = jdbc.sql(UNFINISHED)
                        .param("direction", owner.direction().name())
                        .param("system", owner.system())
                        .param("subject", last.subject())
                        .param("key", last.key())
                        .param("self", owner.target().id())
                        .query(UUID.class)
                        .optional();
                yield unfinished.isPresent()
                        ? unfinished
                        : jdbc.sql(LAST_SUCCEEDED)
                                .param("direction", owner.direction().name())
                                .param("system", owner.system())
                                .param("subject", last.subject())
                                .param("self", owner.target().id())
                                .query((rs, rowNum) -> Map.entry(
                                        rs.getObject("communication_id", UUID.class), rs.getString("dedup_key")))
                                .optional()
                                .filter(latest -> last.key().equals(latest.getValue()))
                                .map(Map.Entry::getKey);
            }
        };
    }

    private static String subject(final DedupKey key) {
        return key instanceof final DedupKey.LastSucceeded last ? last.subject() : null;
    }

    /**
     * Владелец ключей и их пространство.
     *
     * @param target коммуникация
     * @param direction направление
     * @param system внешняя система
     */
    record Owner(CommunicationRef target, Direction direction, String system) {

        /**
         * Владелец по ключу строки.
         *
         * @param id идентификатор
         * @param executionCreatedAt ключ партиционирования
         * @param direction направление
         * @param system внешняя система
         * @return владелец
         */
        static Owner of(
                final UUID id, final Instant executionCreatedAt, final Direction direction, final String system) {
            return new Owner(new CommunicationRef(id, executionCreatedAt), direction, system);
        }
    }

    /**
     * Совпадение.
     *
     * @param duplicateOf коммуникация, с которой совпал ключ
     * @param key совпавший ключ
     */
    record Match(UUID duplicateOf, String key) {}

    /**
     * Ключ с хешем его блокировки.
     *
     * @param hash вторая часть ключа блокировки
     * @param key ключ
     */
    private record Locked(int hash, DedupKey key) {}
}
