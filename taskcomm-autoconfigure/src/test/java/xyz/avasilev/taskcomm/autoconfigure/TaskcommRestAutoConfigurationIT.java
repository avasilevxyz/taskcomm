package xyz.avasilev.taskcomm.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import xyz.avasilev.taskcomm.TaskcommConfigurationException;
import xyz.avasilev.taskcomm.TestPostgres;
import xyz.avasilev.taskcomm.rest.CommunicationController;
import xyz.avasilev.taskcomm.rest.ExecutionController;
import xyz.avasilev.taskcomm.rest.TaskController;
import xyz.avasilev.taskcomm.task.api.Task;
import xyz.avasilev.taskcomm.task.api.processor.FlatTaskProcessor;
import xyz.avasilev.taskcomm.task.api.processor.ProcessingContext;
import xyz.avasilev.taskcomm.task.api.processor.SearchableField;

/**
 * REST (docs/adr/0091-rest-disabled-by-default.md, docs/adr/0140-rest-controllers.md): выключен по умолчанию,
 * подчиняется корню и подсистемам, работает только в servlet-приложении, отдаёт {@code taskcommRequestMatcher};
 * и сквозной путь через MockMvc — постановка из JSON, списки с окном, поиск и отказ по необъявленному полю,
 * детали, отмена, перезапуск, дело целиком, коммуникации.
 */
class TaskcommRestAutoConfigurationIT {

    private static final String BASE = "/api/taskcomm";

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    TaskcommAutoConfiguration.class,
                    TaskcommSchemaAutoConfiguration.class,
                    TaskcommTaskAutoConfiguration.class,
                    TaskcommCommunicationAutoConfiguration.class,
                    TaskcommRestAutoConfiguration.class,
                    JacksonAutoConfiguration.class,
                    HttpMessageConvertersAutoConfiguration.class,
                    DispatcherServletAutoConfiguration.class,
                    WebMvcAutoConfiguration.class))
            .withUserConfiguration(
                    TaskcommTaskAutoConfigurationIT.Database.class,
                    Orders.class,
                    CommunicationResponseIT.Processors.class)
            .withBean(String.class, TestPostgres::newSchema)
            .withPropertyValues(
                    "taskcomm.schema.initialize=always",
                    "taskcomm.task.poll.interval=1h",
                    "taskcomm.communication.poll.interval=1h");

    @Test
    void disabledByDefault() {
        this.contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(TaskController.class);
            assertThat(context).doesNotHaveBean(ExecutionController.class);
            assertThat(context).doesNotHaveBean(RequestMatcher.class);
        });
    }

    @Test
    void enabledGivesControllersAndRequestMatcher() {
        this.contextRunner.withPropertyValues("taskcomm.rest.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(TaskController.class);
            assertThat(context).hasSingleBean(CommunicationController.class);
            assertThat(context).hasSingleBean(ExecutionController.class);
            final RequestMatcher matcher = context.getBean("taskcommRequestMatcher", RequestMatcher.class);
            assertThat(matcher.matches(new MockHttpServletRequest("GET", BASE + "/tasks")))
                    .isTrue();
            assertThat(matcher.matches(new MockHttpServletRequest("GET", "/orders")))
                    .isFalse();
        });
    }

    @Test
    void rootSwitchDisablesRest() {
        this.contextRunner
                .withPropertyValues("taskcomm.rest.enabled=true", "taskcomm.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(ExecutionController.class);
                    assertThat(context).doesNotHaveBean(RequestMatcher.class);
                });
    }

    @Test
    void ownSwitchDisablesRestAndDisabledSubsystemHasNoController() {
        this.contextRunner
                .withPropertyValues("taskcomm.rest.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(ExecutionController.class));
        this.contextRunner
                .withPropertyValues("taskcomm.rest.enabled=true", "taskcomm.task.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(TaskController.class);
                    assertThat(context).hasSingleBean(CommunicationController.class);
                    assertThat(context).hasSingleBean(ExecutionController.class);
                });
    }

    @Test
    void notServletApplicationHasNoRest() {
        new ApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(TaskcommAutoConfiguration.class, TaskcommRestAutoConfiguration.class))
                .withUserConfiguration(TaskcommTaskAutoConfigurationIT.Database.class)
                .withBean(String.class, TestPostgres::newSchema)
                .withPropertyValues("taskcomm.rest.enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(ExecutionController.class));
    }

    @Test
    void nonPositiveSearchWindowFailsStartup() {
        this.contextRunner
                .withPropertyValues("taskcomm.rest.enabled=true", "taskcomm.rest.search-window=0s")
                .run(context -> assertThat(context)
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(TaskcommConfigurationException.class)
                        .hasMessageContaining("search-window"));
    }

    @Test
    void tasksThroughRest() {
        this.contextRunner.withPropertyValues("taskcomm.rest.enabled=true").run(context -> {
            final MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
            final String created = mvc.perform(post(BASE + "/tasks")
                            .param("type", "orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"orderId\":\"A-1\"}"))
                    .andExpect(status().isCreated())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            final String id = JsonPath.read(created, "$.id");
            final String executionId = JsonPath.read(created, "$.execution.id");
            mvc.perform(post(BASE + "/tasks")
                            .param("type", "orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("[1, 2]"))
                    .andExpect(status().isBadRequest());
            reads(mvc, id, executionId);
            actions(mvc, id);
        });
    }

    private static void reads(final MockMvc mvc, final String id, final String executionId) throws Exception {
        mvc.perform(get(BASE + "/tasks").param("field.orderId", "A-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].payload.orderId").value("A-1"))
                .andExpect(jsonPath("$.items[0].state.status").value("PENDING"))
                .andExpect(jsonPath("$.window.from", notNullValue()));
        mvc.perform(get(BASE + "/tasks").param("field.customer", "x"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.knownFields[0]").value("orderId"));
        mvc.perform(get(BASE + "/tasks/TASK/" + id)).andExpect(status().isOk());
        mvc.perform(get(BASE + "/tasks/TASK/" + UUID.randomUUID())).andExpect(status().isNotFound());
        mvc.perform(get(BASE + "/tasks/search-fields"))
                .andExpect(jsonPath("$[0].name").value("orderId"));
        mvc.perform(get(BASE + "/tasks/summary"))
                .andExpect(jsonPath("$[0].count").value(1));
        mvc.perform(get(BASE + "/executions/" + executionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks[0].id").value(id));
    }

    private static void actions(final MockMvc mvc, final String id) throws Exception {
        mvc.perform(post(BASE + "/tasks/TASK/" + id + "/restart"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.accepted").value(false));
        mvc.perform(post(BASE + "/tasks/TASK/" + id + "/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true));
        mvc.perform(post(BASE + "/tasks/TASK/" + id + "/cancel")).andExpect(status().isConflict());
        mvc.perform(post(BASE + "/tasks/discard-child-results"))
                .andExpect(jsonPath("$.parents").value(0));
    }

    @Test
    void communicationsThroughRest() {
        this.contextRunner.withPropertyValues("taskcomm.rest.enabled=true").run(context -> {
            final MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
            final String sent = mvc.perform(post(BASE + "/communications/outbound/bank")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"id\":\"nowait-1\"}"))
                    .andExpect(status().isCreated())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            final String id = JsonPath.read(sent, "$.id");

            mvc.perform(get(BASE + "/communications").param("system", "bank"))
                    .andExpect(jsonPath("$.items", hasSize(1)))
                    .andExpect(jsonPath("$.items[0].requestPayload.id").value("nowait-1"));
            mvc.perform(get(BASE + "/communications/" + id + "/attempts"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)));
            mvc.perform(get(BASE + "/communications/circuit-breakers"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));
            mvc.perform(post(BASE + "/communications/" + id + "/restart")).andExpect(status().isConflict());
            mvc.perform(post(BASE + "/communications/" + id + "/cancel")).andExpect(status().isOk());
            mvc.perform(get(BASE + "/communications/" + id))
                    .andExpect(jsonPath("$.state.status").value("CANCELLED"));
        });
    }

    /**
     * Заказ.
     *
     * @param orderId номер заказа
     */
    record Order(String orderId) {}

    /** Процессор заказов с полем поиска. */
    static class OrderProcessor implements FlatTaskProcessor<Order, Void> {

        @Override
        public String taskType() {
            return "orders";
        }

        @Override
        public List<SearchableField> searchableTaskFields() {
            return List.of(SearchableField.string("orderId", "$.orderId", "Номер заказа"));
        }

        @Override
        public Void process(final Task<Order> task, final ProcessingContext context) {
            return null;
        }
    }

    /** Процессор теста. */
    @Configuration(proxyBeanMethods = false)
    static class Orders {

        @Bean
        OrderProcessor orderProcessor() {
            return new OrderProcessor();
        }
    }
}
