package xyz.avasilev.taskcomm.task.internal.inspection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Types;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import xyz.avasilev.taskcomm.PayloadShape;
import xyz.avasilev.taskcomm.SqlQuery;
import xyz.avasilev.taskcomm.TaskcommException;
import xyz.avasilev.taskcomm.execution.api.HistoryWindow;
import xyz.avasilev.taskcomm.execution.api.Page;
import xyz.avasilev.taskcomm.execution.api.PageRequest;
import xyz.avasilev.taskcomm.rest.SqlLiterals;
import xyz.avasilev.taskcomm.task.api.inspection.TaskAttemptSnapshot;
import xyz.avasilev.taskcomm.task.api.inspection.TaskQuery;
import xyz.avasilev.taskcomm.task.api.inspection.TaskSnapshot;
import xyz.avasilev.taskcomm.task.api.inspection.TaskTypeSummary;
import xyz.avasilev.taskcomm.task.api.inspection.TaskUnitCount;
import xyz.avasilev.taskcomm.task.api.inspection.UnknownSearchFieldException;
import xyz.avasilev.taskcomm.task.internal.ContractShape;
import xyz.avasilev.taskcomm.task.internal.TaskPayloadCodec;
import xyz.avasilev.taskcomm.task.internal.TaskSql;
import xyz.avasilev.taskcomm.task.internal.TaskTestDatabase;
import xyz.avasilev.taskcomm.task.internal.TaskTestSettings;
import xyz.avasilev.taskcomm.task.internal.creation.CreatedTask;
import xyz.avasilev.taskcomm.task.internal.creation.NewTask;
import xyz.avasilev.taskcomm.task.internal.processor.TaskProcessorRegistry;
import xyz.avasilev.taskcomm.task.internal.processor.TaskStartup;
import xyz.avasilev.taskcomm.task.internal.processor.TaskStartupIT;
import xyz.avasilev.taskcomm.task.internal.settings.ClaimForm;

/**
 * Чтение задач (docs/adr/0138-inspection-api.md): страницы по ключу без пропусков и повторов, окно
 * чтения истории, партиция дела, поиск по реестру полей, попытки, сводка, осиротевшие типы.
 */
class DefaultTaskInspectionIT {

    private static final TaskSnapshot.Level TASK = TaskSnapshot.Level.TASK;

    private TaskTestDatabase db;

    private DefaultTaskInspection inspection;

    @BeforeEach
    void createSchema() {
        this.db = TaskTestDatabase.create();
        final TaskStartupIT.Report report = new TaskStartupIT.Report("Номер заказа", "$.orderId");
        final TaskProcessorRegistry registry = new TaskProcessorRegistry(List.of(report));
        new TaskStartup(this.db.dataSource, this.db.transactionManager, this.db.ids, registry, false)
                .afterPropertiesSet();
        this.inspection = new DefaultTaskInspection(
                this.db.dataSource,
                () -> registry,
                () -> null,
                new TaskPayloadCodec(JsonMapper.builder().build()));
    }

    @Test
    void payloadShapeComesFromTheProcessorOfTheType() {
        assertThat(this.inspection.payloadShape(TaskTestDatabase.FLAT_TYPE))
                .map(PayloadShape::kind)
                .contains(PayloadShape.Kind.STRING);
        assertThat(this.inspection.payloadShape("no-such-type")).isEmpty();
    }

    @Test
    void pagesCoverEveryRowOnceNewestFirst() {
        final List<UUID> created = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            created.add(this.submit("A-" + i).id());
        }
        final List<UUID> seen = new ArrayList<>();
        Page<TaskSnapshot> page = this.inspection.list(TaskQuery.of(TASK), this.window(), PageRequest.first(2));
        int pages = 1;
        page.items().forEach(item -> seen.add(item.id()));
        while (page.next().isPresent()) {
            page = this.inspection.list(
                    TaskQuery.of(TASK),
                    this.window(),
                    PageRequest.after(page.next().get(), 2));
            page.items().forEach(item -> seen.add(item.id()));
            pages++;
        }

        assertThat(pages).isEqualTo(3);
        assertThat(seen).containsExactlyElementsOf(created.reversed());
    }

    @Test
    void windowAndExecutionLimitTheRead() {
        final CreatedTask first = this.submit("A-1");
        this.submit("A-2");
        final HistoryWindow past = HistoryWindow.between(
                this.db.currentMonth.minus(60, ChronoUnit.DAYS), this.db.currentMonth.minus(30, ChronoUnit.DAYS));

        assertThat(this.inspection
                        .list(TaskQuery.of(TASK), past, PageRequest.first(10))
                        .items())
                .isEmpty();
        assertThat(this.inspection
                        .list(TaskQuery.of(TASK).inExecution(first.execution()), past, PageRequest.first(10))
                        .items())
                .extracting(TaskSnapshot::id)
                .containsExactly(first.id());
        assertThat(this.inspection.find(TASK, first.id(), past)).isEmpty();
        assertThat(this.inspection.find(TASK, first.id(), this.window())).hasValueSatisfying(found -> {
            assertThat(found.payload()).contains("A-1");
            assertThat(found.status()).isEqualTo("PENDING");
            assertThat(found.orphan()).isFalse();
        });
    }

    @Test
    void searchGoesThroughDeclaredFieldsOnly() {
        final CreatedTask wanted = this.submit("A-7");
        this.submit("A-8");

        assertThat(this.inspection
                        .list(TaskQuery.of(TASK).withField("orderId", "A-7"), this.window(), PageRequest.first(10))
                        .items())
                .extracting(TaskSnapshot::id)
                .containsExactly(wanted.id());
        assertThatThrownBy(() -> this.inspection.list(
                        TaskQuery.of(TASK).withField("customer", "x"), this.window(), PageRequest.first(10)))
                .isInstanceOfSatisfying(
                        UnknownSearchFieldException.class,
                        unknown -> assertThat(unknown.knownFields()).containsExactly("orderId"));
    }

    @Test
    void statusFilterAndOrphanTypes() {
        this.submit("A-1");
        this.db.submit("forgotten", 100);

        final List<TaskSnapshot> items = this.inspection
                .list(TaskQuery.of(TASK).withStatuses(Set.of("PENDING")), this.window(), PageRequest.first(10))
                .items();

        assertThat(items).hasSize(2);
        assertThat(items)
                .filteredOn(TaskSnapshot::orphan)
                .extracting(TaskSnapshot::taskType)
                .containsExactly("forgotten");
        assertThat(this.inspection
                        .list(TaskQuery.of(TASK).withStatuses(Set.of("FAILED")), this.window(), PageRequest.first(10))
                        .items())
                .isEmpty();
    }

    @Test
    void attemptsShowTheOpenLease() {
        final CreatedTask task = this.submit("A-1");
        this.db
                .claimer("worker-a", TaskTestSettings.settings(3, Duration.ZERO, Duration.ofMinutes(1)), () -> 0.5)
                .claim(
                        TaskTestSettings.mainPoller(ClaimForm.DEADLINE),
                        Map.of(TaskTestDatabase.FLAT_TYPE, ContractShape.FLAT),
                        1);
        final TaskSnapshot snapshot =
                this.inspection.find(TASK, task.id(), this.window()).orElseThrow();

        final List<TaskAttemptSnapshot> attempts = this.inspection.attempts(snapshot);

        assertThat(attempts).singleElement().satisfies(attempt -> {
            assertThat(attempt.lockedBy()).isEqualTo("worker-a");
            assertThat(attempt.status()).isEmpty();
            assertThat(attempt.finishedAt()).isEmpty();
            assertThat(attempt.result()).isEmpty();
        });
    }

    @Test
    void summaryCountsByLevelTypeAndStatus() {
        this.submit("A-1");
        this.submit("A-2");
        this.db.submit("forgotten", 100);

        final List<TaskTypeSummary> summary = this.inspection.summary(HistoryWindow.everywhere());

        assertThat(summary)
                .containsExactlyInAnyOrder(
                        new TaskTypeSummary(TASK, TaskTestDatabase.FLAT_TYPE, "PENDING", 2, false),
                        new TaskTypeSummary(TASK, "forgotten", "PENDING", 1, true));
    }

    @Test
    void unitCountsGroupByParentAndStatusInsideTheExecution() {
        final CreatedTask task = this.submit("A-1");
        final CreatedTask other = this.submit("A-2");
        final UUID batch = this.db.ids.next();
        this.unit(task, null, "PENDING");
        this.unit(task, null, "PENDING");
        this.unit(task, null, "SUCCEEDED");
        this.unit(task, batch, "FAILED");
        this.unit(other, null, "PENDING");

        assertThat(this.inspection.unitCounts(task.execution()))
                .containsExactly(
                        new TaskUnitCount(task.id(), null, "PENDING", 2),
                        new TaskUnitCount(task.id(), null, "SUCCEEDED", 1),
                        new TaskUnitCount(task.id(), batch, "FAILED", 1));
    }

    @Test
    void childrenOfOneExpansionAttemptOnly() {
        final CreatedTask task = this.submit("A-1");
        final UUID first = this.db.ids.next();
        final UUID second = this.db.ids.next();
        this.unit(task, null, "ABANDONED", first);
        final UUID current = this.unit(task, null, "PENDING", second);

        final Page<TaskSnapshot> units = this.inspection.list(
                TaskQuery.of(TaskSnapshot.Level.UNIT)
                        .inExecution(task.execution())
                        .withParent(task.id())
                        .withParentAttempt(second),
                this.window(),
                PageRequest.first(10));
        final Page<TaskSnapshot> tasks = this.inspection.list(
                TaskQuery.of(TASK).inExecution(task.execution()).withParentAttempt(second),
                this.window(),
                PageRequest.first(10));

        assertThat(units.items()).extracting(TaskSnapshot::id).containsExactly(current);
        assertThat(tasks.items()).isEmpty();
    }

    @Test
    void unitsOfTaskAttemptGoThroughItsBatchesAndAreCounted() {
        final CreatedTask task = this.submit("A-1");
        final UUID taskAttempt = this.db.ids.next();
        final UUID batch = this.batch(task, taskAttempt);
        final UUID stale = this.batch(task, this.db.ids.next());
        final UUID batchAttempt = this.db.ids.next();
        final UUID first = this.unit(task, batch, "PENDING", batchAttempt);
        final UUID second = this.unit(task, batch, "SUCCEEDED", batchAttempt);
        this.unit(task, stale, "ABANDONED", this.db.ids.next());

        final TaskQuery units = TaskQuery.of(TaskSnapshot.Level.UNIT)
                .inExecution(task.execution())
                .withParent(task.id())
                .withParentAttempt(taskAttempt);
        final TaskQuery batches = TaskQuery.of(TaskSnapshot.Level.BATCH)
                .inExecution(task.execution())
                .withParentAttempt(taskAttempt);

        assertThat(this.inspection
                        .list(units, this.window(), PageRequest.first(10))
                        .items())
                .extracting(TaskSnapshot::id)
                .containsExactlyInAnyOrder(first, second);
        assertThat(this.inspection.count(units, this.window())).isEqualTo(2);
        assertThat(this.inspection.count(batches, this.window())).isEqualTo(1);
        assertThat(this.inspection.count(
                        TaskQuery.of(TaskSnapshot.Level.UNIT).withParentAttempt(batchAttempt), this.window()))
                .isEqualTo(2);
    }

    /**
     * SQL для разбора (docs/adr/0165-read-sql.md) с подставленными значениями, выполненный как есть, даёт
     * те же строки, что чтение: поиск по нагрузке, статусы, окно, курсор, подзапрос детей попытки.
     */
    @Test
    void readSqlWithLiteralsReturnsTheSameRows() {
        final CreatedTask wanted = this.submit("A-7");
        this.submit("A-8");
        this.submit("O'Brien");
        final TaskQuery search =
                TaskQuery.of(TASK).withField("orderId", "A-7").withStatuses(Set.of("PENDING", "FAILED"));
        assertThat(this.ids(this.inspection.listSql(search, this.window(), PageRequest.first(10))))
                .containsExactly(wanted.id());
        final TaskQuery quoted = TaskQuery.of(TASK).withField("orderId", "O'Brien");
        assertThat(this.ids(this.inspection.listSql(quoted, this.window(), PageRequest.first(10))))
                .containsExactlyElementsOf(this.listed(quoted, PageRequest.first(10)));
        final Page<TaskSnapshot> first = this.inspection.list(TaskQuery.of(TASK), this.window(), PageRequest.first(1));
        final PageRequest second = PageRequest.after(first.next().orElseThrow(), 1);
        assertThat(this.ids(this.inspection.listSql(TaskQuery.of(TASK), this.window(), second)))
                .containsExactlyElementsOf(this.listed(TaskQuery.of(TASK), second));

        final UUID taskAttempt = this.db.ids.next();
        final UUID batch = this.batch(wanted, taskAttempt);
        this.unit(wanted, batch, "PENDING", this.db.ids.next());
        this.unit(wanted, null, "SUCCEEDED", taskAttempt);
        final TaskQuery units = TaskQuery.of(TaskSnapshot.Level.UNIT)
                .inExecution(wanted.execution())
                .withParent(wanted.id())
                .withParentAttempt(taskAttempt);
        assertThat(this.ids(this.inspection.listSql(units, this.window(), PageRequest.first(10))))
                .hasSize(2)
                .containsExactlyElementsOf(this.listed(units, PageRequest.first(10)));
        assertThat(this.db
                        .jdbc
                        .sql(SqlLiterals.statement(this.inspection.unitCountsSql(wanted.execution())))
                        .query((rs, rowNum) -> new TaskUnitCount(
                                rs.getObject("task_id", UUID.class),
                                rs.getObject("batch_id", UUID.class),
                                rs.getString("status"),
                                rs.getLong("n")))
                        .list())
                .containsExactlyElementsOf(this.inspection.unitCounts(wanted.execution()));
    }

    @Test
    void attemptsSqlWithLiteralsReturnsTheSameRows() {
        final CreatedTask task = this.submit("A-1");
        this.db
                .claimer("worker-a", TaskTestSettings.settings(3, Duration.ZERO, Duration.ofMinutes(1)), () -> 0.5)
                .claim(
                        TaskTestSettings.mainPoller(ClaimForm.DEADLINE),
                        Map.of(TaskTestDatabase.FLAT_TYPE, ContractShape.FLAT),
                        1);
        final TaskSnapshot snapshot =
                this.inspection.find(TASK, task.id(), this.window()).orElseThrow();

        assertThat(this.ids(this.inspection.attemptsSql(snapshot)))
                .hasSize(1)
                .containsExactlyElementsOf(this.inspection.attempts(snapshot).stream()
                        .map(TaskAttemptSnapshot::id)
                        .toList());
    }

    private List<UUID> ids(final SqlQuery query) {
        return this.db
                .jdbc
                .sql(SqlLiterals.statement(query))
                .query((rs, rowNum) -> rs.getObject("id", UUID.class))
                .list();
    }

    private List<UUID> listed(final TaskQuery query, final PageRequest page) {
        return this.inspection.list(query, this.window(), page).items().stream()
                .map(TaskSnapshot::id)
                .toList();
    }

    @Test
    void foreignCursorIsRejected() {
        assertThatThrownBy(() ->
                        this.inspection.list(TaskQuery.of(TASK), this.window(), PageRequest.after("не курсор", 10)))
                .isInstanceOf(TaskcommException.class);
    }

    private CreatedTask submit(final String orderId) {
        return this.db
                .creator()
                .create(new NewTask(
                        TaskTestDatabase.FLAT_TYPE,
                        "{\"orderId\":\"" + orderId + "\"}",
                        100,
                        Duration.ofMinutes(1),
                        null,
                        null,
                        null));
    }

    private void unit(final CreatedTask task, final UUID batchId, final String status) {
        this.unit(task, batchId, status, this.db.ids.next());
    }

    private UUID unit(final CreatedTask task, final UUID batchId, final String status, final UUID parentAttemptId) {
        final UUID id = this.db.ids.next();
        this.db
                .jdbc
                .sql("""
                        INSERT INTO tc_task_unit (id, created_at, updated_at, execution_id, execution_created_at,
                                                  task_id, batch_id, parent_attempt_id, task_type, payload, status,
                                                  priority, next_attempt_at, claim_order_at, retry_cycle_started_at)
                        VALUES (:id, now(), now(), :executionId, :executionCreatedAt, :taskId, :batchId,
                                :parentAttemptId, :taskType, '{}', :status, 100, now(), now(), now())
                        """)
                .param("id", id)
                .param("parentAttemptId", parentAttemptId)
                .param("executionId", task.execution().id())
                .param("executionCreatedAt", TaskSql.parameter(task.execution().createdAt()))
                .param("taskId", task.id())
                .param("batchId", batchId, Types.OTHER)
                .param("taskType", TaskTestDatabase.FLAT_TYPE)
                .param("status", status)
                .update();
        return id;
    }

    private UUID batch(final CreatedTask task, final UUID parentAttemptId) {
        final UUID id = this.db.ids.next();
        this.db
                .jdbc
                .sql("""
                        INSERT INTO tc_task_batch (id, created_at, updated_at, execution_id, execution_created_at,
                                                   task_id, parent_attempt_id, task_type, payload, status, phase,
                                                   priority, next_attempt_at, claim_order_at, retry_cycle_started_at)
                        VALUES (:id, now(), now(), :executionId, :executionCreatedAt, :taskId, :parentAttemptId,
                                :taskType, '{}', 'PENDING', 'EXPAND', 100, now(), now(), now())
                        """)
                .param("id", id)
                .param("executionId", task.execution().id())
                .param("executionCreatedAt", TaskSql.parameter(task.execution().createdAt()))
                .param("taskId", task.id())
                .param("parentAttemptId", parentAttemptId)
                .param("taskType", TaskTestDatabase.FLAT_TYPE)
                .update();
        return id;
    }

    private HistoryWindow window() {
        return HistoryWindow.between(this.db.currentMonth, this.db.currentMonth.plus(62, ChronoUnit.DAYS));
    }
}
