package xyz.avasilev.taskcomm.autoconfigure;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.ThreadFactory;
import javax.sql.DataSource;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.task.TaskDecorator;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;
import xyz.avasilev.taskcomm.communication.api.CommunicationService;
import xyz.avasilev.taskcomm.communication.api.inspection.CommunicationInspection;
import xyz.avasilev.taskcomm.communication.api.processor.CommunicationProcessor;
import xyz.avasilev.taskcomm.communication.internal.CommunicationCodec;
import xyz.avasilev.taskcomm.communication.internal.creation.CommunicationCreator;
import xyz.avasilev.taskcomm.communication.internal.engine.CommunicationEngine;
import xyz.avasilev.taskcomm.communication.internal.inspection.DefaultCommunicationInspection;
import xyz.avasilev.taskcomm.communication.internal.observation.CommunicationMetrics;
import xyz.avasilev.taskcomm.communication.internal.observation.CommunicationObservation;
import xyz.avasilev.taskcomm.communication.internal.observation.MicrometerCommunicationMetrics;
import xyz.avasilev.taskcomm.communication.internal.processor.CommunicationProcessorRegistry;
import xyz.avasilev.taskcomm.communication.internal.processor.CommunicationStartup;
import xyz.avasilev.taskcomm.communication.internal.service.DefaultCommunicationService;
import xyz.avasilev.taskcomm.communication.internal.settings.CommunicationSettings;
import xyz.avasilev.taskcomm.execution.internal.ExecutionRepository;
import xyz.avasilev.taskcomm.execution.internal.UuidV7;

/**
 * Подсистема коммуникаций: реестр процессоров, итоговые настройки, {@link CommunicationService}
 * и движок — поллеры с пулами воркеров и поток heartbeat, который продлевает и попытки синхронного
 * приёма.
 *
 * <p>Стоит под составным условием «{@code taskcomm.enabled} и {@code taskcomm.communication.enabled}»
 * (docs/adr/0090-root-switch-disables-everything.md). Бины внутренних типов и
 * {@link CommunicationService} объявлены без {@code @ConditionalOnMissingBean}: точки замены — только
 * бины потребителя с квалификаторами стартера (docs/adr/0096-no-foreign-type-beans.md). Кодек, реестр
 * и настройки — свои, общих бинов с подсистемой задач нет (docs/adr/0010-separate-engines.md).
 *
 * <p>Движок стартует после проверки схемы при старте.
 */
@AutoConfiguration(
        after = {
            TaskcommAutoConfiguration.class,
            TaskcommSchemaAutoConfiguration.class,
            DataSourceAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class,
            TransactionAutoConfiguration.class
        })
@ConditionalOnTaskcommSubsystem("communication")
@ConditionalOnClass(JdbcClient.class)
public class TaskcommCommunicationAutoConfiguration {

    /**
     * Реестр процессоров коммуникаций из всех бинов {@link CommunicationProcessor}.
     *
     * @param processors процессоры потребителя
     * @return реестр
     */
    @Bean
    public CommunicationProcessorRegistry taskcommCommunicationProcessorRegistry(
            final ObjectProvider<CommunicationProcessor<?, ?, ?>> processors) {
        return new CommunicationProcessorRegistry(processors.orderedStream().toList());
    }

    /**
     * Итоговые настройки коммуникаций по пяти уровням; печатаются при старте.
     *
     * @param properties свойства стартера
     * @param registry реестр процессоров
     * @return настройки
     */
    @Bean
    public CommunicationSettings taskcommCommunicationSettings(
            final TaskcommProperties properties, final CommunicationProcessorRegistry registry) {
        final TaskcommProperties.Communication communication = properties.getCommunication();
        return CommunicationSettings.resolve(
                CommunicationPropertiesMapping.global(communication),
                CommunicationPropertiesMapping.systems(communication),
                registry,
                CommunicationPropertiesMapping.pollers(communication),
                CommunicationPropertiesMapping.timing(communication),
                CommunicationPropertiesMapping.breaker(communication));
    }

    /**
     * Кодек доменных форм: маппер потребителя под {@code @TaskcommPayloadMapper} или собственный.
     *
     * @param mappers маппер потребителя
     * @return кодек
     */
    @Bean
    public CommunicationCodec taskcommCommunicationCodec(
            @TaskcommPayloadMapper final ObjectProvider<JsonMapper> mappers) {
        final JsonMapper consumer =
                TaskcommBeanSelection.replacement(mappers, JsonMapper.class, "@TaskcommPayloadMapper");
        return new CommunicationCodec(
                consumer != null ? consumer : JsonMapper.builder().build());
    }

    /**
     * Создание коммуникаций.
     *
     * @param properties свойства стартера
     * @param qualified {@code DataSource} под {@code @TaskcommDataSource}
     * @param dataSources все {@code DataSource}
     * @param qualifiedManagers менеджер транзакций под {@code @TaskcommTransactionManager}
     * @param managers все менеджеры транзакций
     * @param beanFactory фабрика бинов
     * @param executions таблица дел
     * @param codec кодек
     * @param ids генератор идентификаторов
     * @return создание коммуникаций
     */
    @Bean
    public CommunicationCreator taskcommCommunicationCreator(
            final TaskcommProperties properties,
            @TaskcommDataSource final ObjectProvider<DataSource> qualified,
            final ObjectProvider<DataSource> dataSources,
            @TaskcommTransactionManager final ObjectProvider<PlatformTransactionManager> qualifiedManagers,
            final ObjectProvider<PlatformTransactionManager> managers,
            final ListableBeanFactory beanFactory,
            final ExecutionRepository executions,
            final CommunicationCodec codec,
            final UuidV7 ids) {
        final DataSource dataSource = TaskcommBeanSelection.dataSource(qualified, dataSources, beanFactory);
        return new CommunicationCreator(
                dataSource,
                TaskcommBeanSelection.transactionManager(qualifiedManagers, managers, beanFactory, dataSource),
                executions,
                codec,
                ids,
                CommunicationPropertiesMapping.truncUnit(
                        properties.getSchema().getPartitions().getPeriod()));
    }

    /**
     * Сервис коммуникаций.
     *
     * @param registry реестр — лениво
     * @param settings настройки — лениво
     * @param engine движок — лениво, его воркер обслуживает синхронный приём
     * @param creator создание коммуникаций
     * @param codec кодек
     * @param ids генератор идентификаторов
     * @param qualified {@code DataSource} под {@code @TaskcommDataSource}
     * @param dataSources все {@code DataSource}
     * @param beanFactory фабрика бинов
     * @param qualifiedManagers менеджеры транзакций под {@code @TaskcommTransactionManager}
     * @param managers все менеджеры транзакций
     * @return сервис
     */
    @Bean
    public CommunicationService taskcommCommunicationService(
            final ObjectProvider<CommunicationProcessorRegistry> registry,
            final ObjectProvider<CommunicationSettings> settings,
            final ObjectProvider<CommunicationEngine> engine,
            final CommunicationCreator creator,
            final CommunicationCodec codec,
            final UuidV7 ids,
            @TaskcommDataSource final ObjectProvider<DataSource> qualified,
            final ObjectProvider<DataSource> dataSources,
            final ListableBeanFactory beanFactory,
            @TaskcommTransactionManager final ObjectProvider<PlatformTransactionManager> qualifiedManagers,
            final ObjectProvider<PlatformTransactionManager> managers) {
        final DataSource dataSource = TaskcommBeanSelection.dataSource(qualified, dataSources, beanFactory);
        return new DefaultCommunicationService(
                registry::getObject,
                settings::getObject,
                engine::getObject,
                creator,
                codec,
                ids,
                dataSource,
                TaskcommBeanSelection.transactionManager(qualifiedManagers, managers, beanFactory, dataSource));
    }

    /**
     * Чтение коммуникаций для экранов и сопровождения (docs/adr/0138-inspection-api.md).
     *
     * @param registry реестр — лениво
     * @param engine движок — лениво, по нему размыкатели экземпляра
     * @param codec кодек
     * @param qualified {@code DataSource} под {@code @TaskcommDataSource}
     * @param dataSources все {@code DataSource}
     * @param beanFactory фабрика бинов
     * @return чтение коммуникаций
     */
    @Bean
    public CommunicationInspection taskcommCommunicationInspection(
            final ObjectProvider<CommunicationProcessorRegistry> registry,
            final ObjectProvider<CommunicationEngine> engine,
            final CommunicationCodec codec,
            @TaskcommDataSource final ObjectProvider<DataSource> qualified,
            final ObjectProvider<DataSource> dataSources,
            final ListableBeanFactory beanFactory) {
        return new DefaultCommunicationInspection(
                TaskcommBeanSelection.dataSource(qualified, dataSources, beanFactory),
                codec,
                registry::getObject,
                engine::getObject);
    }

    /**
     * Проверки при старте: предупреждение об осиротевших коммуникациях.
     *
     * @param qualified {@code DataSource} под {@code @TaskcommDataSource}
     * @param dataSources все {@code DataSource}
     * @param beanFactory фабрика бинов
     * @param registry реестр процессоров
     * @return проверки
     */
    @Bean
    @DependsOn("taskcommSchemaStartup")
    public CommunicationStartup taskcommCommunicationStartup(
            @TaskcommDataSource final ObjectProvider<DataSource> qualified,
            final ObjectProvider<DataSource> dataSources,
            final ListableBeanFactory beanFactory,
            final CommunicationProcessorRegistry registry) {
        return new CommunicationStartup(
                TaskcommBeanSelection.dataSource(qualified, dataSources, beanFactory), registry);
    }

    /**
     * Движок коммуникаций.
     *
     * @param qualified {@code DataSource} под {@code @TaskcommDataSource}
     * @param dataSources все {@code DataSource}
     * @param qualifiedManagers менеджер транзакций под {@code @TaskcommTransactionManager}
     * @param managers все менеджеры транзакций
     * @param beanFactory фабрика бинов; из неё же — наблюдаемость {@link CommunicationObservation}: параметров у метода
     *     и так предел
     * @param ids генератор идентификаторов
     * @param settings итоговые настройки
     * @param registry реестр процессоров
     * @param codec кодек
     * @param threadFactories фабрика потоков потребителя
     * @param decorators декоратор запусков потребителя
     * @param schedulers планировщик потребителя
     * @return движок
     */
    @Bean
    @DependsOn({"taskcommSchemaStartup", "taskcommCommunicationStartup"})
    public CommunicationEngine taskcommCommunicationEngine(
            @TaskcommDataSource final ObjectProvider<DataSource> qualified,
            final ObjectProvider<DataSource> dataSources,
            @TaskcommTransactionManager final ObjectProvider<PlatformTransactionManager> qualifiedManagers,
            final ObjectProvider<PlatformTransactionManager> managers,
            final ListableBeanFactory beanFactory,
            final UuidV7 ids,
            final CommunicationSettings settings,
            final CommunicationProcessorRegistry registry,
            final CommunicationCodec codec,
            @TaskcommWorkerThreadFactory final ObjectProvider<ThreadFactory> threadFactories,
            @TaskcommWorkerTaskDecorator final ObjectProvider<TaskDecorator> decorators,
            @TaskcommScheduler final ObjectProvider<TaskScheduler> schedulers) {
        final DataSource dataSource = TaskcommBeanSelection.dataSource(qualified, dataSources, beanFactory);
        return new CommunicationEngine(
                dataSource,
                TaskcommBeanSelection.transactionManager(qualifiedManagers, managers, beanFactory, dataSource),
                ids,
                settings,
                registry,
                codec,
                TaskcommBeanSelection.replacement(threadFactories, ThreadFactory.class, "@TaskcommWorkerThreadFactory"),
                TaskcommBeanSelection.replacement(decorators, TaskDecorator.class, "@TaskcommWorkerTaskDecorator"),
                TaskcommBeanSelection.scheduler(schedulers),
                beanFactory.getBean(CommunicationObservation.class));
    }

    /**
     * Наблюдаемость движка: префикс ключей MDC, метрики попыток и размыкателей. Без Micrometer или его
     * реестра — без метрик (docs/rules/observability.md).
     *
     * @param properties свойства стартера
     * @param metrics метрики, если Micrometer на classpath
     * @return наблюдаемость движка
     */
    @Bean
    public CommunicationObservation taskcommCommunicationObservation(
            final TaskcommProperties properties, final ObjectProvider<CommunicationMetrics> metrics) {
        return new CommunicationObservation(
                TaskcommObservability.mdcPrefix(properties),
                metrics.getIfAvailable(() -> CommunicationMetrics.NONE),
                TaskcommObservability.metricsInterval(properties));
    }

    /** Метрики — только при Micrometer на classpath (docs/adr/0015-observability.md). */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(MeterRegistry.class)
    static class Metrics {

        /**
         * Метрики в реестре потребителя; реестра нет — без метрик.
         *
         * @param registries реестры Micrometer
         * @return метрики коммуникаций
         */
        @Bean
        public CommunicationMetrics taskcommCommunicationMetrics(final ObjectProvider<MeterRegistry> registries) {
            final MeterRegistry registry = registries.getIfUnique();
            return registry == null ? CommunicationMetrics.NONE : new MicrometerCommunicationMetrics(registry);
        }
    }
}
