package xyz.avasilev.taskcomm.ui;

import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.DispatcherServlet;
import xyz.avasilev.taskcomm.TaskcommConfigurationException;
import xyz.avasilev.taskcomm.autoconfigure.TaskcommProperties;
import xyz.avasilev.taskcomm.autoconfigure.TaskcommRestAutoConfiguration;
import xyz.avasilev.taskcomm.rest.RestWindows;
import xyz.avasilev.taskcomm.ui.internal.UiController;

/**
 * Веб-интерфейс taskcomm (docs/adr/0149-web-interface-module.md, docs/adr/0151-default-paths-under-tc.md).
 *
 * <p>Появляется, только если в приложении есть REST стартера: бин {@link RestWindows} несёт с собой
 * и корневой выключатель {@code taskcomm.enabled}, и {@code taskcomm.rest.enabled}, и servlet-приложение.
 * Свой выключатель — {@code taskcomm.ui.enabled}, по умолчанию включён: подключить модуль — уже явный шаг.
 *
 * <p>Статику раздаёт внутренний контроллер, а не бин {@code WebMvcConfigurer}: бинов чужих типов стартер
 * не регистрирует (docs/adr/0096-no-foreign-type-beans.md). Пути интерфейса закрывает тот же
 * {@code taskcommRequestMatcher}, что и REST.
 */
@AutoConfiguration(after = TaskcommRestAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(DispatcherServlet.class)
@ConditionalOnBean(RestWindows.class)
@ConditionalOnBooleanProperty(name = "taskcomm.ui.enabled", matchIfMissing = true)
public class TaskcommUiAutoConfiguration {

    /** Путь интерфейса: от корня, без завершающей косой черты, не корень приложения. */
    private static final Pattern BASE_PATH = Pattern.compile("(/[A-Za-z0-9._-]+)+");

    /**
     * Раздача интерфейса.
     *
     * @param properties свойства стартера
     * @return контроллер
     * @throws TaskcommConfigurationException если путь интерфейса не разбирается или совпадает с путём REST
     */
    @Bean
    public UiController taskcommUiController(final TaskcommProperties properties) {
        final String ui = properties.getUi().getBasePath();
        final String rest = properties.getRest().getBasePath();
        if (ui == null || !BASE_PATH.matcher(ui).matches()) {
            throw new TaskcommConfigurationException("taskcomm.ui.base-path должен начинаться с /, не быть корнем"
                    + " и не заканчиваться /, а не '" + ui + "' (docs/adr/0149-web-interface-module.md)");
        }
        if (ui.equals(rest)) {
            throw new TaskcommConfigurationException("taskcomm.ui.base-path совпадает с taskcomm.rest.base-path: " + ui
                    + " (docs/adr/0151-default-paths-under-tc.md)");
        }
        return new UiController(
                ui, rest, properties.getUi().getTitle(), TaskcommUiAutoConfiguration.class.getClassLoader());
    }
}
