package xyz.avasilev.taskcomm.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import xyz.avasilev.taskcomm.TaskcommConfigurationException;
import xyz.avasilev.taskcomm.autoconfigure.TaskcommAutoConfiguration;
import xyz.avasilev.taskcomm.autoconfigure.TaskcommCommunicationAutoConfiguration;
import xyz.avasilev.taskcomm.autoconfigure.TaskcommRestAutoConfiguration;
import xyz.avasilev.taskcomm.autoconfigure.TaskcommSchemaAutoConfiguration;
import xyz.avasilev.taskcomm.autoconfigure.TaskcommTaskAutoConfiguration;
import xyz.avasilev.taskcomm.ui.internal.UiController;

/**
 * Автоконфигурация интерфейса (docs/adr/0149-web-interface-module.md, docs/adr/0151-default-paths-under-tc.md):
 * интерфейс есть при модуле и включённом REST; его нет без REST, при {@code taskcomm.ui.enabled=false},
 * при {@code taskcomm.enabled=false} и вне servlet-приложения; бинов чужих типов он не добавляет; пути
 * интерфейса закрывает {@code taskcommRequestMatcher}; путь REST внутри пути интерфейса остаётся за REST.
 */
class TaskcommUiAutoConfigurationIT {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    TaskcommAutoConfiguration.class,
                    TaskcommSchemaAutoConfiguration.class,
                    TaskcommTaskAutoConfiguration.class,
                    TaskcommCommunicationAutoConfiguration.class,
                    TaskcommRestAutoConfiguration.class,
                    TaskcommUiAutoConfiguration.class,
                    JacksonAutoConfiguration.class,
                    HttpMessageConvertersAutoConfiguration.class,
                    DispatcherServletAutoConfiguration.class,
                    WebMvcAutoConfiguration.class))
            .withUserConfiguration(UiPostgres.Database.class)
            .withBean(String.class, UiPostgres::newSchema)
            .withPropertyValues(
                    "taskcomm.schema.initialize=always",
                    "taskcomm.task.enabled=false",
                    "taskcomm.communication.enabled=false");

    @Test
    void uiComesWithRestAndIsCoveredByRequestMatcher() {
        this.contextRunner.withPropertyValues("taskcomm.rest.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(UiController.class);
            final RequestMatcher matcher = context.getBean("taskcommRequestMatcher", RequestMatcher.class);
            assertThat(matcher.matches(new MockHttpServletRequest("GET", "/tc")))
                    .isTrue();
            assertThat(matcher.matches(new MockHttpServletRequest("GET", "/tc/tasks")))
                    .isTrue();
            assertThat(matcher.matches(new MockHttpServletRequest("GET", "/tc/api/meta")))
                    .isTrue();
            assertThat(matcher.matches(new MockHttpServletRequest("GET", "/orders")))
                    .isFalse();
        });
    }

    @Test
    void noUiWithoutRest() {
        this.contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(UiController.class);
        });
    }

    @Test
    void ownSwitchDisablesUiAndItsPathsButKeepsRest() {
        this.contextRunner
                .withPropertyValues("taskcomm.rest.enabled=true", "taskcomm.ui.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(UiController.class);
                    final RequestMatcher matcher = context.getBean("taskcommRequestMatcher", RequestMatcher.class);
                    assertThat(matcher.matches(new MockHttpServletRequest("GET", "/tc/tasks")))
                            .isFalse();
                    assertThat(matcher.matches(new MockHttpServletRequest("GET", "/tc/api/meta")))
                            .isTrue();
                    MockMvcBuilders.webAppContextSetup(context)
                            .build()
                            .perform(get("/tc/api/meta"))
                            .andExpect(status().isOk());
                });
    }

    @Test
    void rootSwitchDisablesUi() {
        this.contextRunner
                .withPropertyValues("taskcomm.rest.enabled=true", "taskcomm.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(UiController.class));
    }

    @Test
    void notServletApplicationHasNoUi() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        TaskcommAutoConfiguration.class,
                        TaskcommRestAutoConfiguration.class,
                        TaskcommUiAutoConfiguration.class))
                .withUserConfiguration(UiPostgres.Database.class)
                .withBean(String.class, UiPostgres::newSchema)
                .withPropertyValues("taskcomm.rest.enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(UiController.class));
    }

    @Test
    void uiAddsOnlyItsController() {
        final AtomicReference<Set<String>> without = new AtomicReference<>();
        this.contextRunner
                .withPropertyValues("taskcomm.rest.enabled=true", "taskcomm.ui.enabled=false")
                .run(context -> without.set(Set.of(context.getBeanDefinitionNames())));
        this.contextRunner.withPropertyValues("taskcomm.rest.enabled=true").run(context -> {
            final Set<String> added = new HashSet<>(Set.of(context.getBeanDefinitionNames()));
            added.removeAll(without.get());
            assertThat(added)
                    .containsExactlyInAnyOrder(TaskcommUiAutoConfiguration.class.getName(), "taskcommUiController");
        });
    }

    @Test
    void restInsideUiPathStaysWithRest() {
        this.contextRunner.withPropertyValues("taskcomm.rest.enabled=true").run(context -> {
            final MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
            mvc.perform(get("/tc/api/meta"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.searchWindow").value("PT720H"));
            mvc.perform(get("/tc/api/unknown")).andExpect(status().isNotFound());
            mvc.perform(get("/tc/executions/0193"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(Matchers.containsString("content=\"/tc/api\"")));
        });
    }

    @Test
    void ownPathsFollowProperties() {
        this.contextRunner
                .withPropertyValues(
                        "taskcomm.rest.enabled=true",
                        "taskcomm.ui.base-path=/ops/taskcomm",
                        "taskcomm.rest.base-path=/api/taskcomm")
                .run(context -> MockMvcBuilders.webAppContextSetup(context)
                        .build()
                        .perform(get("/ops/taskcomm/tasks"))
                        .andExpect(status().isOk())
                        .andExpect(content().string(Matchers.containsString("<base href=\"/ops/taskcomm/\" />")))
                        .andExpect(content().string(Matchers.containsString("content=\"/api/taskcomm\""))));
    }

    @Test
    void badBasePathFailsStartup() {
        for (final String[] properties : new String[][] {
            {"taskcomm.ui.base-path=/"},
            {"taskcomm.ui.base-path=/tc/"},
            {"taskcomm.ui.base-path=/same", "taskcomm.rest.base-path=/same"}
        }) {
            this.contextRunner
                    .withPropertyValues("taskcomm.rest.enabled=true")
                    .withPropertyValues(properties)
                    .run(context -> assertThat(context)
                            .getFailure()
                            .rootCause()
                            .isInstanceOf(TaskcommConfigurationException.class)
                            .hasMessageContaining("taskcomm.ui.base-path"));
        }
    }
}
