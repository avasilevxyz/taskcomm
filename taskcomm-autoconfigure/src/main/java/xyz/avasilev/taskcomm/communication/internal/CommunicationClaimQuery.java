package xyz.avasilev.taskcomm.communication.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Текст запроса кандидатов захвата коммуникаций — по пробе на каждую систему поллера
 * (docs/adr/0137-rate-limit-and-circuit-breaker.md, по образцу docs/adr/0129-claim-by-type-share-locking.md).
 * Индексы захвата — с внешней стороной {@code coalesce(source, destination)} на втором месте; системы
 * вне набора — осиротевшие, разомкнутые, выделенных поллеров — не читаются вовсе.
 *
 * <ul>
 *   <li>Одна система — её первые {@code limit} строк под блокировкой.
 *   <li>Несколько — голова: у каждой системы первые {@code limit} строк без блокировок, слитые в порядке
 *       захвата и обрезанные до {@code limit}; доля каждой системы в голове; каждая система со
 *       {@code SKIP LOCKED} блокирует свою долю.
 * </ul>
 *
 * <p>Текст зависит от порядка и числа систем и собирается один раз на пару.
 */
final class CommunicationClaimQuery {

    /** Порядок захвата формы {@code deadline}; на месте {@code %1$s} — псевдоним выборки. */
    static final String DEADLINE = "%1$s.claim_order_at";

    /** Порядок захвата формы {@code priority}. */
    static final String PRIORITY = "%1$s.priority, %1$s.next_attempt_at";

    /** Проход формы {@code quota} по чистому FIFO. */
    static final String FIFO = "%1$s.next_attempt_at";

    private static final String SELECT = """
            SELECT c.id, c.execution_created_at, c.execution_id, c.direction,
                   coalesce(c.source, c.destination) AS system, c.phase, c.contract_shape, c.priority,
                   c.next_attempt_at, c.claim_order_at, c.created_at, c.request_payload::text AS payload,
                   c.origin_type, c.origin_id, c.origin_attempt_id, c.origin_relation,
                   a.id AS attempt_id, a.request_payload::text AS callback
              FROM tc_communication c
              JOIN tc_communication_attempt a ON a.execution_created_at = c.execution_created_at
                   AND a.communication_id = c.id AND a.phase = c.phase AND a.finished_at IS NULL
                   AND a.locked_until IS NULL
                   AND a.execution_created_at >= :activeFrom AND a.execution_created_at < :activeTo
            """;

    private final Map<Key, String> texts = new ConcurrentHashMap<>();

    /**
     * Текст запроса — из кеша.
     *
     * @param order порядок захвата — одна из констант класса
     * @param systems число систем; параметры — {@code :system0}, {@code :system1}, …
     * @return текст запроса
     */
    String text(final String order, final int systems) {
        return this.texts.computeIfAbsent(new Key(order, systems), key -> candidates(order, systems));
    }

    /**
     * Запрос кандидатов: параметры {@code :activeFrom}, {@code :activeTo}, {@code :limit} и системы.
     *
     * @param order порядок захвата
     * @param systems число систем
     * @return текст запроса
     */
    static String candidates(final String order, final int systems) {
        if (systems == 1) {
            return SELECT + ready(0) + " ORDER BY " + order.formatted("c")
                    + " LIMIT :limit FOR UPDATE OF c, a SKIP LOCKED";
        }
        final List<String> ctes = new ArrayList<>();
        ctes.add("head AS (SELECT h.system FROM ("
                + IntStream.range(0, systems)
                        .mapToObj(n -> "(SELECT coalesce(c.source, c.destination) AS system, c.priority, "
                                + "c.next_attempt_at, c.claim_order_at FROM tc_communication c" + ready(n)
                                + " ORDER BY " + order.formatted("c") + " LIMIT :limit)")
                        .collect(Collectors.joining("\n UNION ALL "))
                + ") h ORDER BY " + order.formatted("h") + " LIMIT :limit)");
        ctes.add("share AS (SELECT system, count(*) AS n FROM head GROUP BY system)");
        for (int n = 0; n < systems; n++) {
            ctes.add("system" + n + " AS (" + SELECT + ready(n) + " ORDER BY " + order.formatted("c")
                    + " LIMIT (SELECT coalesce(max(s.n), 0) FROM share s WHERE s.system = :system" + n
                    + ") FOR UPDATE OF c, a SKIP LOCKED)");
        }
        return "WITH " + String.join(",\n", ctes) + "\nSELECT * FROM ("
                + IntStream.range(0, systems)
                        .mapToObj(n -> "SELECT * FROM system" + n)
                        .collect(Collectors.joining(" UNION ALL "))
                + ") x ORDER BY " + order.formatted("x");
    }

    /** Готовая строка системы с этим номером параметра; граница — на таблице. */
    private static String ready(final int system) {
        return """

                 WHERE c.status = 'PENDING'
                   AND coalesce(c.source, c.destination) = :system%d
                   AND c.next_attempt_at <= now()
                   AND c.execution_created_at >= :activeFrom
                   AND c.execution_created_at < :activeTo""".formatted(system);
    }

    /**
     * Ключ текста.
     *
     * @param order порядок
     * @param systems число систем
     */
    private record Key(String order, int systems) {}
}
