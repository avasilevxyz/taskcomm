package xyz.avasilev.taskcomm.communication.internal;

import java.time.Clock;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.boot.sql.init.DatabaseInitializationMode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import xyz.avasilev.taskcomm.TestPostgres;
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
final class CommunicationTestDatabase {

    final DataSource dataSource;

    final JdbcClient jdbc;

    final PlatformTransactionManager transactionManager;

    final UuidV7 ids = new UuidV7(Clock.systemUTC());

    final CommunicationCodec codec = new CommunicationCodec(JsonMapper.builder().build());

    private CommunicationTestDatabase(final DataSource dataSource) {
        this.dataSource = dataSource;
        this.jdbc = JdbcClient.create(dataSource);
        this.transactionManager = new DataSourceTransactionManager(dataSource);
        new TaskcommSchemaInitializer(dataSource, DatabaseInitializationMode.ALWAYS).initializeDatabase();
        final TransactionTemplate transactions = new TransactionTemplate(this.transactionManager);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        new PartitionMaintenance(
                        this.jdbc,
                        transactions,
                        new PartitionSettings(
                                true, RangeCalendar.MONTH, 3, Duration.ofHours(1), false, Duration.ofDays(180)))
                .startup();
    }

    /**
     * Новая схема с партициями.
     *
     * @return база для одного теста
     */
    static CommunicationTestDatabase create() {
        return new CommunicationTestDatabase(TestPostgres.dataSource(TestPostgres.newSchema()));
    }

    /**
     * Создание коммуникаций на этой базе.
     *
     * @return создание
     */
    CommunicationCreator creator() {
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
    CommunicationClaimer claimer(final CommunicationSettings settings) {
        return new CommunicationClaimer(
                this.dataSource,
                this.transactionManager,
                settings,
                new CommunicationRetries(settings, () -> 0.5, this.ids),
                () -> 0.5,
                "test-instance");
    }
}
