package xyz.avasilev.taskcomm.autoconfigure;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.autoconfigure.contributor.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import xyz.avasilev.taskcomm.communication.internal.CommunicationEngine;
import xyz.avasilev.taskcomm.task.internal.TaskEngine;

/**
 * Наблюдаемость экземпляра поверх обеих подсистем: health indicator {@code taskcomm} и предупреждение
 * при старте, если не зарегистрировано ни одного процессора (docs/adr/0051-engine-health.md,
 * docs/adr/0141-observability-instance.md).
 *
 * <p>Стоит под корневым {@code taskcomm.enabled}; движки выключенных подсистем просто отсутствуют.
 * Health indicator — только при Actuator на classpath, и выключается штатно —
 * {@code management.health.taskcomm.enabled=false}. Это бин чужого типа, который стартер отдаёт, а не
 * потребляет, как {@code taskcommRequestMatcher}.
 */
@AutoConfiguration(after = {TaskcommTaskAutoConfiguration.class, TaskcommCommunicationAutoConfiguration.class})
@ConditionalOnProperty(prefix = "taskcomm", name = "enabled", havingValue = "true", matchIfMissing = true)
public class TaskcommObservabilityAutoConfiguration {

    /**
     * Громкое предупреждение, если во включённых подсистемах нет ни одного процессора: почти наверняка
     * ошибка сборки. Не {@code DOWN} и не падение — движок исправен, ему нечего делать.
     *
     * @param beanFactory фабрика бинов — процессоры считаются по именам, без создания
     * @return отметка проверки
     */
    @Bean
    TaskcommProcessorCheck taskcommProcessorCheck(final ListableBeanFactory beanFactory) {
        return TaskcommProcessorCheck.run(beanFactory);
    }

    /** Health indicator — только при Actuator (docs/adr/0051-engine-health.md). */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(HealthIndicator.class)
    @ConditionalOnEnabledHealthIndicator("taskcomm")
    static class Health {

        /**
         * Живость потоков поллеров и heartbeat обеих подсистем. Имя бина даёт имя индикатора — {@code taskcomm}.
         *
         * @param properties свойства стартера
         * @param tasks движок задач, если подсистема включена
         * @param communications движок коммуникаций, если подсистема включена
         * @return индикатор
         */
        @Bean
        public HealthIndicator taskcommHealthIndicator(
                final TaskcommProperties properties,
                final ObjectProvider<TaskEngine> tasks,
                final ObjectProvider<CommunicationEngine> communications) {
            final Map<String, Supplier<List<TaskcommHealthIndicator.Mechanism>>> engines = new LinkedHashMap<>();
            final TaskEngine taskEngine = tasks.getIfAvailable();
            if (taskEngine != null) {
                engines.put(
                        "task",
                        () -> taskEngine.liveness().stream()
                                .map(live -> new TaskcommHealthIndicator.Mechanism(
                                        live.name(), live.interval(), live.silence()))
                                .toList());
            }
            final CommunicationEngine communicationEngine = communications.getIfAvailable();
            if (communicationEngine != null) {
                engines.put(
                        "communication",
                        () -> communicationEngine.liveness().stream()
                                .map(live -> new TaskcommHealthIndicator.Mechanism(
                                        live.name(), live.interval(), live.silence()))
                                .toList());
            }
            return new TaskcommHealthIndicator(engines, TaskcommObservability.missedIntervals(properties));
        }
    }
}
