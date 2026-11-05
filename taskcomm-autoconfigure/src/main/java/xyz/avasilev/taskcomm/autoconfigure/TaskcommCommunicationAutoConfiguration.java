package xyz.avasilev.taskcomm.autoconfigure;

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
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.task.TaskDecorator;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;
import xyz.avasilev.taskcomm.communication.api.CommunicationProcessor;
import xyz.avasilev.taskcomm.communication.api.CommunicationService;
import xyz.avasilev.taskcomm.communication.internal.CommunicationCodec;
import xyz.avasilev.taskcomm.communication.internal.CommunicationCreator;
import xyz.avasilev.taskcomm.communication.internal.CommunicationEngine;
import xyz.avasilev.taskcomm.communication.internal.CommunicationProcessorRegistry;
import xyz.avasilev.taskcomm.communication.internal.CommunicationSettings;
import xyz.avasilev.taskcomm.communication.internal.CommunicationStartup;
import xyz.avasilev.taskcomm.communication.internal.DefaultCommunicationService;
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
            final ListableBeanFactory beanFactory) {
        return new DefaultCommunicationService(
                registry::getObject,
                settings::getObject,
                engine::getObject,
                creator,
                codec,
                ids,
                TaskcommBeanSelection.dataSource(qualified, dataSources, beanFactory));
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
     * @param beanFactory фабрика бинов
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
                TaskcommBeanSelection.scheduler(schedulers));
    }
}
