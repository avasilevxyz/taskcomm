package xyz.avasilev.taskcomm.task.internal;

import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.DoubleSupplier;
import javax.sql.DataSource;
import org.springframework.boot.sql.init.DatabaseInitializationMode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.avasilev.taskcomm.TestPostgres;
import xyz.avasilev.taskcomm.execution.api.ExecutionRef;
import xyz.avasilev.taskcomm.execution.internal.ExecutionRepository;
import xyz.avasilev.taskcomm.execution.internal.UuidV7;
import xyz.avasilev.taskcomm.schema.internal.PartitionMaintenance;
import xyz.avasilev.taskcomm.schema.internal.PartitionSettings;
import xyz.avasilev.taskcomm.schema.internal.RangeCalendar;
import xyz.avasilev.taskcomm.schema.internal.TaskcommSchemaInitializer;
import xyz.avasilev.taskcomm.task.internal.claim.TaskClaimer;
import xyz.avasilev.taskcomm.task.internal.creation.CreatedTask;
import xyz.avasilev.taskcomm.task.internal.creation.NewTask;
import xyz.avasilev.taskcomm.task.internal.creation.TaskCreator;
import xyz.avasilev.taskcomm.task.internal.engine.TaskAttemptCloser;
import xyz.avasilev.taskcomm.task.internal.lifecycle.TaskChildren;
import xyz.avasilev.taskcomm.task.internal.lifecycle.TaskResultDiscard;
import xyz.avasilev.taskcomm.task.internal.settings.AttemptSettings;
import xyz.avasilev.taskcomm.task.internal.settings.AttemptSettingsSource;
import xyz.avasilev.taskcomm.task.internal.settings.ResultDiscard;

/**
 * Схема taskcomm в отдельной схеме общего контейнера PostgreSQL, с партициями на текущий месяц
 * и запасом, и сборка компонентов подсистемы задач поверх неё.
 */
public final class TaskTestDatabase {

    /** Девять партиционированных таблиц — для заведения старых диапазонов. */
    public static final List<String> PARTITIONED = List.of(
            "tc_execution",
            "tc_task",
            "tc_task_batch",
            "tc_task_unit",
            "tc_communication",
            "tc_task_attempt",
            "tc_communication_attempt",
            "tc_task_result",
            "tc_communication_dedup");

    /** Тип плоской задачи по умолчанию в тестах. */
    public static final String FLAT_TYPE = "report";

    public final DataSource dataSource;

    public final JdbcClient jdbc;

    public final PlatformTransactionManager transactionManager;

    public final UuidV7 ids = new UuidV7(java.time.Clock.systemUTC());

    public final Instant currentMonth;

    private final PartitionMaintenance maintenance;

    private TaskTestDatabase(final DataSource dataSource) {
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
        this.currentMonth = RangeCalendar.MONTH.floor(this.now());
    }

    /**
     * Новая схема с партициями текущего месяца и запасом.
     *
     * @return база для одного теста
     */
    public static TaskTestDatabase create() {
        final TaskTestDatabase database = new TaskTestDatabase(TestPostgres.dataSource(TestPostgres.newSchema()));
        database.maintenance.startup();
        return database;
    }

    /**
     * Новая схема, как у {@link #create()}, но на пуле соединений — как у потребителя: соединения
     * переживают транзакцию, кеши каталога PostgreSQL прогреты (docs/adr/0132-load-scenario-on-pool.md).
     * Без пула каждая транзакция открывает новое соединение и платит за холодный каталог.
     *
     * @return база для нагрузочного сценария
     */
    public static TaskTestDatabase createPooled() {
        final HikariDataSource pool = new HikariDataSource();
        pool.setJdbcUrl(TestPostgres.jdbcUrl(TestPostgres.newSchema()));
        pool.setUsername(TestPostgres.username());
        pool.setPassword(TestPostgres.password());
        pool.setMaximumPoolSize(4);
        final TaskTestDatabase database = new TaskTestDatabase(pool);
        database.maintenance.startup();
        return database;
    }

    /**
     * Заводит партиции всех девяти таблиц на месяц, начинающийся с {@code lower}, и пересчитывает
     * границу активных партиций: пустой старый месяц она сразу закрывает.
     *
     * @param lower начало месяца
     */
    public void createMonth(final Instant lower) {
        for (final String table : PARTITIONED) {
            this.jdbc
                    .sql("CREATE TABLE " + RangeCalendar.partitionName(table, lower) + " PARTITION OF " + table
                            + " FOR VALUES FROM ('" + lower.atOffset(ZoneOffset.UTC) + "') TO ('"
                            + RangeCalendar.MONTH.next(lower).atOffset(ZoneOffset.UTC) + "')")
                    .update();
        }
        this.maintenance.startup();
    }

    /** Такт обслуживания: сдвигает границу вперёд по свободным диапазонам. */
    public void advanceHorizon() {
        this.maintenance.startup();
    }

    public Instant horizon() {
        return TaskPartitionHorizon.activeFrom(this.jdbc);
    }

    public Instant now() {
        return this.jdbc
                .sql("SELECT now()")
                .query(OffsetDateTime.class)
                .single()
                .toInstant();
    }

    public TaskCreator creator() {
        return new TaskCreator(
                this.dataSource,
                this.transactionManager,
                new ExecutionRepository(this.jdbc, this.ids),
                this.ids,
                PartitionRange.MONTH);
    }

    public TaskClaimer claimer(final String instance, final AttemptSettings settings, final DoubleSupplier random) {
        return new TaskClaimer(
                this.dataSource,
                this.transactionManager,
                this.ids,
                AttemptSettingsSource.uniform(settings),
                TaskTestSettings.layout(),
                random,
                instance);
    }

    public TaskAttemptCloser closer(final AttemptSettings settings) {
        return this.closer(settings, TaskTestSettings.KEEP_RESULTS);
    }

    public TaskAttemptCloser closer(final AttemptSettings settings, final ResultDiscard discard) {
        return new TaskAttemptCloser(
                this.dataSource,
                this.transactionManager,
                this.ids,
                AttemptSettingsSource.uniform(settings),
                TaskTestSettings.layout(),
                () -> 0.5,
                this.children(),
                this.discard(discard));
    }

    public TaskChildren children() {
        return new TaskChildren(this.dataSource, this.transactionManager, TaskTestSettings.layout());
    }

    public TaskResultDiscard discard(final ResultDiscard discard) {
        return new TaskResultDiscard(this.dataSource, this.transactionManager, discard);
    }

    /**
     * Ставит задачу в новое дело.
     *
     * @param type тип задачи
     * @param priority приоритет
     * @return созданная задача
     */
    public CreatedTask submit(final String type, final int priority) {
        return this.creator().create(new NewTask(type, "{\"n\":1}", priority, Duration.ofMinutes(1), null, null, null));
    }

    /**
     * Ставит задачу в существующее дело.
     *
     * @param execution дело
     * @return созданная задача
     */
    CreatedTask submitInto(final ExecutionRef execution) {
        return this.creator()
                .create(new NewTask(FLAT_TYPE, "{\"n\":1}", 100, Duration.ofMinutes(1), null, execution, null));
    }

    public String status(final TaskLevel level, final UUID id) {
        return this.jdbc
                .sql("SELECT status FROM " + level.table() + " WHERE id = :id")
                .param("id", id)
                .query(String.class)
                .single();
    }

    public int count(final String sql, final UUID id) {
        return this.jdbc.sql(sql).param("id", id).query(Integer.class).single();
    }
}
