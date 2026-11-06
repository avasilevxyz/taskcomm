package xyz.avasilev.taskcomm.autoconfigure;

import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.servlet.DispatcherServlet;
import xyz.avasilev.taskcomm.TaskcommConfigurationException;
import xyz.avasilev.taskcomm.communication.api.CommunicationService;
import xyz.avasilev.taskcomm.communication.api.inspection.CommunicationInspection;
import xyz.avasilev.taskcomm.execution.api.ExecutionService;
import xyz.avasilev.taskcomm.rest.CommunicationController;
import xyz.avasilev.taskcomm.rest.ExecutionController;
import xyz.avasilev.taskcomm.rest.RestMapper;
import xyz.avasilev.taskcomm.rest.RestMapperImpl;
import xyz.avasilev.taskcomm.rest.RestWindows;
import xyz.avasilev.taskcomm.rest.TaskController;
import xyz.avasilev.taskcomm.task.api.TaskService;
import xyz.avasilev.taskcomm.task.api.inspection.TaskInspection;

/**
 * REST API стартера (docs/adr/0014-rest-api.md, docs/adr/0091-rest-disabled-by-default.md,
 * docs/adr/0140-rest-controllers.md).
 *
 * <p>Выключен по умолчанию: включается {@code taskcomm.rest.enabled: true} и подчиняется корневому
 * выключателю. Работает только в servlet-приложении со Spring MVC; контроллер подсистемы появляется,
 * только если включена сама подсистема. Обработчики ошибок — в контроллерах, глобального совета нет.
 * Spring Security стартер не трогает: при нём на classpath отдаётся бин {@code taskcommRequestMatcher}
 * с путями стартера — закрыть их потребитель обязан сам.
 */
@AutoConfiguration(
        after = {
            TaskcommAutoConfiguration.class,
            TaskcommTaskAutoConfiguration.class,
            TaskcommCommunicationAutoConfiguration.class
        })
@ConditionalOnTaskcommSubsystem(value = "rest", enabledByDefault = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(DispatcherServlet.class)
public class TaskcommRestAutoConfiguration {

    /**
     * Окна чтения истории с умолчанием {@code taskcomm.rest.search-window}.
     *
     * @param properties свойства стартера
     * @return окна
     * @throws TaskcommConfigurationException если окно не положительное
     */
    @Bean
    public RestWindows taskcommRestWindows(final TaskcommProperties properties) {
        final Duration window = properties.getRest().getSearchWindow();
        if (window == null || window.isNegative() || window.isZero()) {
            throw new TaskcommConfigurationException("taskcomm.rest.search-window должно быть положительным, а не "
                    + window + " (docs/adr/0101-active-partition-horizon.md)");
        }
        return new RestWindows(window);
    }

    /**
     * Формы ответов — класс, сгенерированный MapStruct; рантайм MapStruct потребителю не нужен.
     *
     * @return маппер
     */
    @Bean
    public RestMapper taskcommRestMapper() {
        return new RestMapperImpl();
    }

    /**
     * Дело одной выдачей; подсистемы, которых нет, дают пустые списки.
     *
     * @param executions дела
     * @param tasks чтение задач, если подсистема включена
     * @param communications чтение коммуникаций, если подсистема включена
     * @param mapper формы ответов
     * @return контроллер
     */
    @Bean
    public ExecutionController taskcommExecutionController(
            final ExecutionService executions,
            final ObjectProvider<TaskInspection> tasks,
            final ObjectProvider<CommunicationInspection> communications,
            final RestMapper mapper) {
        return new ExecutionController(executions, tasks.getIfAvailable(), communications.getIfAvailable(), mapper);
    }

    /**
     * REST задач — вместе с подсистемой задач.
     *
     * @param tasks сервис задач
     * @param inspection чтение задач
     * @param windows окна
     * @param mapper формы ответов
     * @return контроллер
     */
    @Bean
    @ConditionalOnBean(TaskInspection.class)
    public TaskController taskcommTaskController(
            final TaskService tasks,
            final TaskInspection inspection,
            final RestWindows windows,
            final RestMapper mapper) {
        return new TaskController(tasks, inspection, windows, mapper);
    }

    /**
     * REST коммуникаций — вместе с подсистемой коммуникаций.
     *
     * @param communications сервис коммуникаций
     * @param inspection чтение коммуникаций
     * @param windows окна
     * @param mapper формы ответов
     * @return контроллер
     */
    @Bean
    @ConditionalOnBean(CommunicationInspection.class)
    public CommunicationController taskcommCommunicationController(
            final CommunicationService communications,
            final CommunicationInspection inspection,
            final RestWindows windows,
            final RestMapper mapper) {
        return new CommunicationController(communications, inspection, windows, mapper);
    }

    /** Пути стартера для конфигурации безопасности потребителя — только при Spring Security на classpath. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.security.web.util.matcher.RequestMatcher")
    static class Security {

        /**
         * Все пути REST стартера. Отдаётся, а не потребляется: без условий, точкой замены не является
         * (docs/adr/0096-no-foreign-type-beans.md).
         *
         * @param properties свойства стартера
         * @return сопоставитель путей
         */
        @Bean
        RequestMatcher taskcommRequestMatcher(final TaskcommProperties properties) {
            return PathPatternRequestMatcher.withDefaults()
                    .matcher(properties.getRest().getBasePath() + "/**");
        }
    }
}
