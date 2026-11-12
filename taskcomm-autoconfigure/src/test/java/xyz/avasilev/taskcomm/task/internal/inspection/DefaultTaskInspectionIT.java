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
import xyz.avasilev.taskcomm.TaskcommException;
import xyz.avasilev.taskcomm.execution.api.HistoryWindow;
import xyz.avasilev.taskcomm.execution.api.Page;
import xyz.avasilev.taskcomm.execution.api.PageRequest;
import xyz.avasilev.taskcomm.task.api.inspection.TaskAttemptSnapshot;
import xyz.avasilev.taskcomm.task.api.inspection.TaskQuery;
import xyz.avasilev.taskcomm.task.api.inspection.TaskSnapshot;
import xyz.avasilev.taskcomm.task.api.inspection.TaskTypeSummary;
import xyz.avasilev.taskcomm.task.api.inspection.TaskUnitCount;
import xyz.avasilev.taskcomm.task.api.inspection.UnknownSearchFieldException;
import xyz.avasilev.taskcomm.task.internal.ContractShape;
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
        this.inspection = new DefaultTaskInspection(this.db.dataSource, () -> registry);
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
        this.db
                .jdbc
                .sql("""
                        INSERT INTO tc_task_unit (id, created_at, updated_at, execution_id, execution_created_at,
                                                  task_id, batch_id, parent_attempt_id, task_type, payload, status,
                                                  priority, next_attempt_at, claim_order_at, retry_cycle_started_at)
                        VALUES (:id, now(), now(), :executionId, :executionCreatedAt, :taskId, :batchId,
                                gen_random_uuid(), :taskType, '{}', :status, 100, now(), now(), now())
                        """)
                .param("id", this.db.ids.next())
                .param("executionId", task.execution().id())
                .param("executionCreatedAt", TaskSql.parameter(task.execution().createdAt()))
                .param("taskId", task.id())
                .param("batchId", batchId, Types.OTHER)
                .param("taskType", TaskTestDatabase.FLAT_TYPE)
                .param("status", status)
                .update();
    }

    private HistoryWindow window() {
        return HistoryWindow.between(this.db.currentMonth, this.db.currentMonth.plus(62, ChronoUnit.DAYS));
    }
}
