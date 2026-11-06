package xyz.avasilev.taskcomm.communication.internal.processor;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.simple.JdbcClient;
import xyz.avasilev.taskcomm.communication.internal.CommunicationPartitionHorizon;
import xyz.avasilev.taskcomm.communication.internal.CommunicationSql;

/**
 * Проверки подсистемы коммуникаций при старте: <b>громкое предупреждение об осиротевших
 * коммуникациях</b> — незавершённых коммуникациях внешних систем без процессора, с числом по системе
 * (docs/adr/0105-orphan-communications.md). Считаются в активных партициях. Ошибка чтения старт
 * не роняет — это только предупреждение.
 */
@Slf4j
public class CommunicationStartup implements InitializingBean {

    private static final String ORPHANS = """
            SELECT coalesce(source, destination) AS system, count(*) AS unfinished
              FROM tc_communication
             WHERE status IN ('PENDING', 'RUNNING', 'AWAITING_RESPONSE')
               AND execution_created_at >= :activeFrom
               AND NOT (coalesce(source, destination) = ANY(:systems))
             GROUP BY coalesce(source, destination)
             ORDER BY 1
            """;

    private final JdbcClient jdbc;

    private final CommunicationProcessorRegistry registry;

    /**
     * Собирает проверки.
     *
     * @param dataSource {@code DataSource} стартера
     * @param registry реестр процессоров
     */
    public CommunicationStartup(final DataSource dataSource, final CommunicationProcessorRegistry registry) {
        this.jdbc = JdbcClient.create(dataSource);
        this.registry = registry;
    }

    @Override
    public void afterPropertiesSet() {
        this.warnAboutOrphans();
    }

    /** Предупреждает об осиротевших коммуникациях. */
    void warnAboutOrphans() {
        try {
            final Instant activeFrom = CommunicationPartitionHorizon.activeFrom(this.jdbc);
            final Map<String, Long> orphans = new LinkedHashMap<>();
            this.jdbc
                    .sql(ORPHANS)
                    .param("activeFrom", CommunicationSql.parameter(activeFrom))
                    .param("systems", this.registry.systems().toArray(String[]::new))
                    .query((rs, rowNum) -> Map.entry(rs.getString("system"), rs.getLong("unfinished")))
                    .list()
                    .forEach(entry -> orphans.put(entry.getKey(), entry.getValue()));
            if (!orphans.isEmpty()) {
                log.warn(
                        "Осиротевшие коммуникации — незавершённые коммуникации внешних систем без процессора: {}. "
                                + "Их никто не захватывает, состояние они не меняют и ждут, пока процессор вернут. "
                                + "Процессор системы не удаляется никогда (docs/adr/0105-orphan-communications.md).",
                        orphans);
            }
        } catch (final RuntimeException failure) {
            log.warn("Не удалось сосчитать осиротевшие коммуникации при старте", failure);
        }
    }
}
