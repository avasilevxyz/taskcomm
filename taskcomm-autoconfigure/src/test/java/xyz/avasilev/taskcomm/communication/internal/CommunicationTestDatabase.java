package xyz.avasilev.taskcomm.communication.internal;

import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.boot.sql.init.DatabaseInitializationMode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import xyz.avasilev.taskcomm.TestPostgres;
import xyz.avasilev.taskcomm.communication.internal.claim.CommunicationClaimer;
import xyz.avasilev.taskcomm.communication.internal.creation.CommunicationCreator;
import xyz.avasilev.taskcomm.communication.internal.lifecycle.CommunicationRetries;
import xyz.avasilev.taskcomm.communication.internal.settings.CommunicationSettings;
import xyz.avasilev.taskcomm.execution.internal.ExecutionRepository;
import xyz.avasilev.taskcomm.execution.internal.UuidV7;
import xyz.avasilev.taskcomm.schema.internal.PartitionMaintenance;
import xyz.avasilev.taskcomm.schema.internal.PartitionSettings;
import xyz.avasilev.taskcomm.schema.internal.RangeCalendar;
import xyz.avasilev.taskcomm.schema.internal.TaskcommSchemaInitializer;

/**
 * Схема taskcomm в отдельной схеме общего контейнера PostgreSQL, с партициями на текущий месяц
 * и запасом, и сборка компонентов подсистемы коммуникаций поверх неё.
 */
public final class CommunicationTestDatabase {

    /** Партиционированные таблицы — для заведения старых диапазонов. */
    static final List<String> PARTITIONED = List.of(
            "tc_execution",
            "tc_task",
            "tc_task_batch",
            "tc_task_unit",
            "tc_communication",
            "tc_task_attempt",
            "tc_communication_attempt",
            "tc_task_result",
            "tc_communication_dedup");

    public final DataSource dataSource;

    public final JdbcClient jdbc;

    public final PlatformTransactionManager transactionManager;

    public final UuidV7 ids = new UuidV7(Clock.systemUTC());

    public final CommunicationCodec codec =
            new CommunicationCodec(JsonMapper.builder().build());

    private final PartitionMaintenance maintenance;

    private CommunicationTestDatabase(final DataSource dataSource) {
        this.dataSource = dataSource;
        this.jdbc = JdbcClient.create(dataSource);
        this.transactionManager = new DataSourceTransactionManager(dataSource);
        new TaskcommSchemaInitializer(dataSource, DatabaseInitializationMode.ALWAYS).initializeDatabase();
        final TransactionTemplate transactions = new TransactionTemplate(this.transactionManager);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.maintenance = new PartitionMaintenance(
                this.jdbc,
                transactions,
                new PartitionSettings(true, RangeCalendar.MONTH, 3, Duration.ofHours(1), false, Duration.ofDays(180)));
        this.maintenance.startup();
    }

    /**
     * Новая схема с партициями.
     *
     * @return база для одного теста
     */
    public static CommunicationTestDatabase create() {
        return new CommunicationTestDatabase(TestPostgres.dataSource(TestPostgres.newSchema()));
    }

    /**
     * Новая схема на пуле соединений — как у потребителя (docs/adr/0132-load-scenario-on-pool.md).
     *
     * @return база для нагрузочного сценария
     */
    public static CommunicationTestDatabase createPooled() {
        final HikariDataSource pool = new HikariDataSource();
        pool.setJdbcUrl(TestPostgres.jdbcUrl(TestPostgres.newSchema()));
        pool.setUsername(TestPostgres.username());
        pool.setPassword(TestPostgres.password());
        pool.setMaximumPoolSize(4);
        return new CommunicationTestDatabase(pool);
    }

    /**
     * Заводит месячные партиции всех таблиц, начиная с {@code lower}.
     *
     * @param lower нижняя граница месяца
     */
    public void createMonth(final Instant lower) {
        for (final String table : PARTITIONED) {
            this.jdbc
                    .sql("CREATE TABLE " + RangeCalendar.partitionName(table, lower) + " PARTITION OF " + table
                            + " FOR VALUES FROM ('" + lower.atOffset(ZoneOffset.UTC) + "') TO ('"
                            + RangeCalendar.MONTH.next(lower).atOffset(ZoneOffset.UTC) + "')")
                    .update();
        }
    }

    /** Такт обслуживания партиций: сдвигает границу активных партиций. */
    public void advanceHorizon() {
        this.maintenance.startup();
    }

    /**
     * Текущий месяц по часам базы.
     *
     * @return нижняя граница текущего месяца
     */
    public Instant currentMonth() {
        return RangeCalendar.MONTH.floor(this.now());
    }

    /**
     * Время базы.
     *
     * @return {@code now()}
     */
    public Instant now() {
        return this.jdbc
                .sql("SELECT now()")
                .query(OffsetDateTime.class)
                .single()
                .toInstant();
    }

    /**
     * Создание коммуникаций на этой базе.
     *
     * @return создание
     */
    public CommunicationCreator creator() {
        return new CommunicationCreator(
                this.dataSource,
                this.transactionManager,
                new ExecutionRepository(this.jdbc, this.ids),
                this.codec,
                this.ids,
                "month");
    }

    /**
     * Захват на этой базе.
     *
     * @param settings итоговые настройки
     * @return захват с экземпляром {@code test-instance}
     */
    public CommunicationClaimer claimer(final CommunicationSettings settings) {
        return new CommunicationClaimer(
                this.dataSource,
                this.transactionManager,
                settings,
                new CommunicationRetries(settings, () -> 0.5, this.ids),
                () -> 0.5,
                "test-instance");
    }
}
