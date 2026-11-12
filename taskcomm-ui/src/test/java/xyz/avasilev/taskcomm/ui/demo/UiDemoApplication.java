package xyz.avasilev.taskcomm.ui.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Демо-приложение для ручной проверки интерфейса (docs/roadmap.md, этап 11d): PostgreSQL в Testcontainers,
 * стартер с REST и интерфейсом, процессоры трёх форм задач и банк с колбэком; при старте ставится немного
 * работы. Запуск — {@code gradlew.bat :taskcomm-ui:uiDemo}, нужен Docker; интерфейс — http://localhost:8088/tc.
 *
 * <p>Это код потребителя, а не стартера: процессоры здесь — обычные бины приложения.
 */
@SpringBootApplication(proxyBeanMethods = false)
public class UiDemoApplication {

    /** Собирается Spring Boot. */
    protected UiDemoApplication() {
        // конструктор для Spring
    }

    /**
     * Поднимает базу и приложение.
     *
     * @param args аргументы Spring Boot
     */
    public static void main(final String[] args) {
        final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");
        postgres.start();
        System.setProperty("spring.datasource.url", postgres.getJdbcUrl());
        System.setProperty("spring.datasource.username", postgres.getUsername());
        System.setProperty("spring.datasource.password", postgres.getPassword());
        System.setProperty("server.port", System.getProperty("server.port", "8088"));
        System.setProperty("taskcomm.rest.enabled", "true");
        System.setProperty("taskcomm.schema.initialize", "always");
        System.setProperty("taskcomm.task.poll.interval", "1s");
        System.setProperty("taskcomm.communication.poll.interval", "1s");
        SpringApplication.run(UiDemoApplication.class, args);
    }
}
