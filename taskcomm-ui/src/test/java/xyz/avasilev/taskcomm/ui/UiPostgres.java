package xyz.avasilev.taskcomm.ui;

import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * PostgreSQL в Testcontainers для интеграционных тестов модуля (docs/adr/0085-tests-on-real-postgres.md):
 * один контейнер на прогон, у каждого контекста — своя пустая схема. Помощники стартера живут в его
 * тестовых исходниках и сюда не видны, поэтому здесь — самое необходимое.
 */
final class UiPostgres {

    private static final PostgreSQLContainer CONTAINER = start();

    private static final AtomicInteger SCHEMAS = new AtomicInteger();

    private UiPostgres() {}

    /**
     * Заводит новую пустую схему.
     *
     * @return имя схемы
     */
    static String newSchema() {
        final String schema = "ui_" + SCHEMAS.incrementAndGet();
        JdbcClient.create(dataSource("public")).sql("CREATE SCHEMA " + schema).update();
        return schema;
    }

    private static DataSource dataSource(final String schema) {
        final String url = CONTAINER.getJdbcUrl();
        return new DriverManagerDataSource(
                url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                CONTAINER.getUsername(),
                CONTAINER.getPassword());
    }

    private static PostgreSQLContainer start() {
        final PostgreSQLContainer container = new PostgreSQLContainer("postgres:17-alpine");
        container.start();
        return container;
    }

    /** {@code DataSource} в схеме теста и менеджер транзакций над ним. */
    @Configuration(proxyBeanMethods = false)
    static class Database {

        @Bean
        @Primary
        DataSource dataSource(final String schema) {
            return UiPostgres.dataSource(schema);
        }

        @Bean
        DataSourceTransactionManager transactionManager(final DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
    }
}
