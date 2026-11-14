package xyz.avasilev.taskcomm.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
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
import xyz.avasilev.taskcomm.rest.ManualInboundController;
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

    private static final String BASE = "/tc/api";

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
                            .param("notBefore", later())
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
            intoExecutionById(mvc, executionId, JsonPath.read(created, "$.execution.createdAt"));
            reads(mvc, id, executionId);
            actions(mvc, id);
        });
    }

    /** Постановка в дело одним идентификатором: время создания находит сервер, чужое дело — {@code 400}. */
    private static void intoExecutionById(final MockMvc mvc, final String executionId, final String executionCreatedAt)
            throws Exception {
        mvc.perform(post(BASE + "/tasks")
                        .param("type", "orders")
                        .param("executionId", executionId)
                        .param("notBefore", later())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"A-2\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.execution.id").value(executionId))
                .andExpect(jsonPath("$.execution.createdAt").value(executionCreatedAt));
        mvc.perform(post(BASE + "/tasks")
                        .param("type", "orders")
                        .param("executionId", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"A-3\"}"))
                .andExpect(status().isBadRequest());
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
                .andExpect(jsonPath("$[0].count").value(2));
        mvc.perform(get(BASE + "/executions/" + executionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks[0].id").value(id));
        mvc.perform(get(BASE + "/executions/" + executionId + "/outline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks[0].id").value(id))
                .andExpect(jsonPath("$.unitCounts", hasSize(0)))
                .andExpect(jsonPath("$.units").doesNotExist());
        mvc.perform(get(BASE + "/executions/" + UUID.randomUUID() + "/outline")).andExpect(status().isNotFound());
        // SQL для разбора (docs/adr/0165-read-sql.md) — с подставленными значениями
        mvc.perform(get(BASE + "/tasks/sql").param("field.orderId", "A-1").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sql", containsString("FROM tc_task e")))
                .andExpect(jsonPath("$.sql", containsString("= 'A-1'")))
                .andExpect(jsonPath("$.sql", containsString("LIMIT 20;")));
        mvc.perform(get(BASE + "/tasks/TASK/" + id + "/attempts/sql"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sql", containsString("'" + id + "'::uuid")));
        mvc.perform(get(BASE + "/tasks/TASK/" + UUID.randomUUID() + "/attempts/sql"))
                .andExpect(status().isNotFound());
        mvc.perform(get(BASE + "/executions/" + executionId + "/outline/sql"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sql", containsString("-- Задачи дела")))
                .andExpect(jsonPath("$.sql", containsString("-- Ключи корреляции дела")));
        mvc.perform(get(BASE + "/executions/" + UUID.randomUUID() + "/outline/sql"))
                .andExpect(status().isNotFound());
    }

    @Test
    void metaNamesProcessorsOfEnabledSubsystems() {
        this.contextRunner.withPropertyValues("taskcomm.rest.enabled=true").run(context -> {
            final MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
            mvc.perform(get(BASE + "/meta"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.searchWindow").value("PT720H"))
                    .andExpect(jsonPath("$.instance")
                            .value(ManagementFactory.getRuntimeMXBean().getName()))
                    .andExpect(jsonPath("$.tasks.types[0].type").value("orders"))
                    .andExpect(jsonPath("$.tasks.types[0].title").value("Заказы"))
                    .andExpect(jsonPath("$.tasks.types[0].description").value("Оплата заказа"))
                    .andExpect(jsonPath("$.tasks.types[0].titleTemplates.task").value("Заказ {orderId}"))
                    .andExpect(jsonPath("$.tasks.types[0].contract").value("FLAT"))
                    .andExpect(jsonPath("$.tasks.types[0].defaultPriority").value(100))
                    .andExpect(jsonPath("$.tasks.types[0].poller").value("main"))
                    .andExpect(jsonPath("$.communications.systems[?(@.system == 'bank')].transport")
                            .value("REST"))
                    .andExpect(jsonPath("$.communications.systems[?(@.system == 'bank')].title")
                            .value("bank"))
                    .andExpect(jsonPath("$.communications.systems[?(@.system == 'bank')].description")
                            .value(contains(nullValue())))
                    .andExpect(jsonPath("$.communications.systems[?(@.system == 'bank')].poller")
                            .value("main"))
                    .andExpect(jsonPath("$.communications.systems[?(@.system == 'bank')].defaultPriority")
                            .value(100))
                    .andExpect(jsonPath("$.communications.manualInbound").value(false));
        });
        this.contextRunner
                .withPropertyValues("taskcomm.rest.enabled=true", "taskcomm.task.enabled=false")
                .run(context -> MockMvcBuilders.webAppContextSetup(context)
                        .build()
                        .perform(get(BASE + "/meta"))
                        .andExpect(jsonPath("$.tasks").doesNotExist())
                        .andExpect(jsonPath("$.communications.systems").isArray()));
    }

    @Test
    void payloadShapesOfProcessors() {
        this.contextRunner.withPropertyValues("taskcomm.rest.enabled=true").run(context -> {
            final MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
            mvc.perform(get(BASE + "/tasks/payload-shape").param("type", "orders"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.kind").value("OBJECT"))
                    .andExpect(jsonPath("$.properties.orderId.kind").value("STRING"))
                    .andExpect(jsonPath("$.properties.orderId.properties").doesNotExist());
            mvc.perform(get(BASE + "/tasks/payload-shape").param("type", "missing"))
                    .andExpect(status().isNotFound());
            mvc.perform(get(BASE + "/communications/request-shape").param("system", "bank"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.kind").value("OBJECT"));
            mvc.perform(get(BASE + "/communications/request-shape").param("system", "missing"))
                    .andExpect(status().isNotFound());
        });
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
                            .param("notBefore", later())
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
            communicationSql(mvc, id);
            mvc.perform(get(BASE + "/communications/circuit-breakers"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));
            mvc.perform(post(BASE + "/communications/" + id + "/restart")).andExpect(status().isConflict());
            mvc.perform(post(BASE + "/communications/" + id + "/cancel")).andExpect(status().isOk());
            mvc.perform(get(BASE + "/communications/" + id))
                    .andExpect(jsonPath("$.state.status").value("CANCELLED"));
        });
    }

    /** SQL для разбора (docs/adr/0165-read-sql.md): списка и попыток коммуникации. */
    private static void communicationSql(final MockMvc mvc, final String id) throws Exception {
        mvc.perform(get(BASE + "/communications/sql").param("system", "bank"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sql", containsString("= 'bank'")));
        mvc.perform(get(BASE + "/communications/" + id + "/attempts/sql"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sql", containsString("FROM tc_communication_attempt")));
    }

    @Test
    void manualInboundIsOffByDefaultAndReceivesWhenOn() {
        this.contextRunner.withPropertyValues("taskcomm.rest.enabled=true").run(context -> {
            assertThat(context).doesNotHaveBean(ManualInboundController.class);
            MockMvcBuilders.webAppContextSetup(context)
                    .build()
                    .perform(post(BASE + "/communications/inbound/bank")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"id\":\"in-1\"}"))
                    .andExpect(status().is4xxClientError());
        });
        this.contextRunner
                .withPropertyValues("taskcomm.rest.enabled=true", "taskcomm.rest.manual-inbound=true")
                .run(context -> {
                    final MockMvc mvc =
                            MockMvcBuilders.webAppContextSetup(context).build();
                    mvc.perform(get(BASE + "/meta"))
                            .andExpect(
                                    jsonPath("$.communications.manualInbound").value(true));
                    final String received = mvc.perform(post(BASE + "/communications/inbound/bank")
                                    .param("requestId", "manual-1")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"id\":\"in-1\"}"))
                            .andExpect(status().isCreated())
                            .andExpect(jsonPath("$.reason").doesNotExist())
                            .andReturn()
                            .getResponse()
                            .getContentAsString();
                    mvc.perform(get(BASE + "/communications/" + JsonPath.read(received, "$.id")))
                            .andExpect(jsonPath("$.direction").value("INBOUND"))
                            .andExpect(jsonPath("$.requestPayload.id").value("in-1"));
                    mvc.perform(post(BASE + "/communications/inbound/bank")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("не JSON"))
                            .andExpect(status().isCreated())
                            .andExpect(jsonPath("$.status").value("FAILED"))
                            .andExpect(jsonPath("$.reason").exists());
                });
    }

    /**
     * Отсрочка для работы, которую тест отменяет и перезапускает. Первый такт поллера идёт сразу
     * при старте контекста, а {@code poll.interval} отодвигает только следующие: без отсрочки такт
     * мог захватить и выполнить работу раньше отмены, и та сохраняла настоящий исход вместо
     * {@code CANCELLED}.
     *
     * @return момент через час в ISO-8601
     */
    private static String later() {
        return Instant.now().plus(Duration.ofHours(1)).toString();
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
        public String title() {
            return "Заказы";
        }

        @Override
        public String description() {
            return "Оплата заказа";
        }

        @Override
        public String taskTitle() {
            return "Заказ {orderId}";
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
