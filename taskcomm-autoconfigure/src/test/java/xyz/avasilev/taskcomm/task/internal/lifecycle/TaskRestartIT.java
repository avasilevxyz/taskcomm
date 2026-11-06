package xyz.avasilev.taskcomm.task.internal.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import xyz.avasilev.taskcomm.task.api.TaskRestartResult;
import xyz.avasilev.taskcomm.task.internal.AttemptHistory;
import xyz.avasilev.taskcomm.task.internal.ClaimedAttempt;
import xyz.avasilev.taskcomm.task.internal.ContractShape;
import xyz.avasilev.taskcomm.task.internal.TaskEntityRef;
import xyz.avasilev.taskcomm.task.internal.TaskLevel;
import xyz.avasilev.taskcomm.task.internal.TaskPayloadCodec;
import xyz.avasilev.taskcomm.task.internal.TaskPhase;
import xyz.avasilev.taskcomm.task.internal.TaskTestDatabase;
import xyz.avasilev.taskcomm.task.internal.TaskTestSettings;
import xyz.avasilev.taskcomm.task.internal.claim.TaskClaimer;
import xyz.avasilev.taskcomm.task.internal.creation.CreatedTask;
import xyz.avasilev.taskcomm.task.internal.engine.TaskAttemptCloser;
import xyz.avasilev.taskcomm.task.internal.engine.TaskEmitter;
import xyz.avasilev.taskcomm.task.internal.settings.AttemptSettings;
import xyz.avasilev.taskcomm.task.internal.settings.ClaimForm;
import xyz.avasilev.taskcomm.task.internal.settings.PollerSettings;

/**
 * Ручной перезапуск (docs/adr/0049-restart-semantics.md, docs/adr/0071-no-restart-after-cancel.md,
 * docs/adr/0097-no-restart-during-collect.md, docs/adr/0098-restart-only-latest-expansion.md,
 * docs/adr/0139-restart-api.md): что перезапускается, что отвергается и с какой причиной, гонки с захватом
 * сборки и с перезапуском предка.
 */
class TaskRestartIT {

    private static final String TYPE = "orders";

    private static final Map<String, ContractShape> UNIT = Map.of(TYPE, ContractShape.UNIT);

    private static final PollerSettings DEADLINE = TaskTestSettings.mainPoller(ClaimForm.DEADLINE);

    private static final AttemptSettings SETTINGS = TaskTestSettings.settings(3, Duration.ZERO, Duration.ofMinutes(1));

    private TaskTestDatabase db;

    private TaskClaimer claimer;

    private TaskAttemptCloser closer;

    private TaskRestart restart;

    private final TaskPayloadCodec codec =
            new TaskPayloadCodec(JsonMapper.builder().build());

    @BeforeEach
    void createSchema() {
        this.db = TaskTestDatabase.create();
        this.claimer = this.db.claimer("worker", SETTINGS, () -> 0.5);
        this.closer = this.db.closer(SETTINGS);
        this.restart = new TaskRestart(this.db.dataSource, this.db.transactionManager, TaskTestSettings.layout());
    }

    @Test
    void failedUnitRestartsAndParentWaitsForItAgain() {
        final Family family = this.readyForCollect();

        final TaskRestartResult result = this.restart.restart(family.unit(family.failed()));

        assertThat(result.accepted()).as(result.reason()).isTrue();
        assertThat(this.db.status(TaskLevel.UNIT, family.failed())).isEqualTo("PENDING");
        assertThat(this.db.status(TaskLevel.TASK, family.task().id())).isEqualTo("AWAITING_CHILDREN");
        final ClaimedAttempt again = this.claimer.claim(DEADLINE, UNIT, 1).getFirst();
        assertThat(again.target().id()).isEqualTo(family.failed());
        assertThat(again.history()).isEqualTo(new AttemptHistory(1, true));
        assertThat(this.closer.succeed(again, "20")).isTrue();
        assertThat(this.db.status(TaskLevel.TASK, family.task().id())).isEqualTo("PENDING");
    }

    @Test
    void onlyFailedUnitAndNoRestartUnderCollect() {
        final Family family = this.readyForCollect();
        assertThat(this.restart.restart(family.unit(family.succeeded())).reason())
                .contains("только упавшая подзадача");

        final ClaimedAttempt collect = this.claimer.claim(DEADLINE, UNIT, 1).getFirst();
        assertThat(collect.phase()).isEqualTo(TaskPhase.COLLECT);

        assertThat(this.restart.restart(family.unit(family.failed())).reason())
                .contains("идёт сборка")
                .contains(family.task().id().toString());
        assertThat(this.db.status(TaskLevel.UNIT, family.failed())).isEqualTo("FAILED");
    }

    @Test
    void cancelledAncestorBlocksRestart() {
        final Family family = this.readyForCollect();
        this.db
                .jdbc
                .sql("UPDATE tc_task SET cancel_requested_at = now() WHERE id = :id")
                .param("id", family.task().id())
                .update();

        assertThat(this.restart.restart(family.unit(family.failed())).reason())
                .contains("отмены")
                .contains(family.task().id().toString());
        assertThat(this.db.status(TaskLevel.TASK, family.task().id())).isEqualTo("PENDING");
    }

    @Test
    void taskRestartStartsNewExpansionAndOrphansPreviousChildren() {
        final Family family = this.collected();

        final TaskRestartResult task = this.restart.restart(family.taskRef());

        assertThat(task.accepted()).as(task.reason()).isTrue();
        assertThat(this.db.status(TaskLevel.TASK, family.task().id())).isEqualTo("PENDING");
        assertThat(this.restart.restart(family.unit(family.failed())).reason()).contains("прежней попытке раскрытия");

        final ClaimedAttempt expansion = this.claimer.claim(DEADLINE, UNIT, 1).getFirst();
        assertThat(expansion.phase()).isEqualTo(TaskPhase.EXPAND);
        this.expand(expansion, 1);

        assertThat(this.restart.restart(family.unit(family.failed())).reason()).contains("прежней попытке раскрытия");
        assertThat(this.restart.restart(family.taskRef()).reason()).contains("только завершённая");
    }

    @Test
    void discardedResultsBlockUnitRestartAndMarkIsDroppedByParentRestart() {
        final Family family = this.collected();
        final TaskResultDiscard discard = this.db.discard(TaskTestSettings.KEEP_RESULTS);

        final List<TaskEntityRef> marked = discard.markDue(Duration.ZERO);
        assertThat(marked).containsExactly(family.taskRef());
        assertThat(this.db.count("SELECT count(*) FROM tc_task_result WHERE target_id = :id", family.succeeded()))
                .isEqualTo(1);
        discard.deleteMarked(family.taskRef());
        assertThat(this.db.count("SELECT count(*) FROM tc_task_result WHERE target_id = :id", family.succeeded()))
                .isZero();

        assertThat(this.restart.restart(family.unit(family.failed())).reason()).contains("результаты детей");
        assertThat(this.restart.restart(family.taskRef()).accepted()).isTrue();
        assertThat(this.db.count(
                        "SELECT count(*) FROM tc_task WHERE id = :id AND child_results_discarded_at IS NULL",
                        family.task().id()))
                .isEqualTo(1);
    }

    @Test
    void collectClaimAndChildRestartNeverBothWin() throws Exception {
        for (int round = 0; round < 10; round++) {
            this.db = TaskTestDatabase.create();
            this.claimer = this.db.claimer("worker", SETTINGS, () -> 0.5);
            this.closer = this.db.closer(SETTINGS);
            this.restart = new TaskRestart(this.db.dataSource, this.db.transactionManager, TaskTestSettings.layout());
            final Family family = this.readyForCollect();

            final List<Object> outcomes = race(
                    () -> this.claimer.claim(DEADLINE, UNIT, 1),
                    () -> this.restart.restart(family.unit(family.failed())));

            final boolean collectClaimed = ((List<?>) outcomes.get(0))
                    .stream().anyMatch(claimed -> ((ClaimedAttempt) claimed).phase() == TaskPhase.COLLECT);
            final boolean restarted = ((TaskRestartResult) outcomes.get(1)).accepted();
            assertThat(collectClaimed).as("раунд %d", round).isNotEqualTo(restarted);
        }
    }

    @Test
    void parentRestartAndChildRestartNeverBothWin() throws Exception {
        for (int round = 0; round < 10; round++) {
            this.db = TaskTestDatabase.create();
            this.claimer = this.db.claimer("worker", SETTINGS, () -> 0.5);
            this.closer = this.db.closer(SETTINGS);
            this.restart = new TaskRestart(this.db.dataSource, this.db.transactionManager, TaskTestSettings.layout());
            final Family family = this.collected();

            final List<Object> outcomes = race(
                    () -> this.restart.restart(family.taskRef()),
                    () -> this.restart.restart(family.unit(family.failed())));

            assertThat(outcomes)
                    .as("раунд %d", round)
                    .filteredOn(outcome -> ((TaskRestartResult) outcome).accepted())
                    .hasSize(1);
        }
    }

    /** Задача раскрыта в две подзадачи: одна успешна, другая упала без повтора; задача ждёт сборки. */
    private Family readyForCollect() {
        final CreatedTask task = this.db.submit(TYPE, 100);
        final ClaimedAttempt expansion = this.claimer.claim(DEADLINE, UNIT, 1).getFirst();
        this.expand(expansion, 2);
        final List<ClaimedAttempt> units = this.claimer.claim(DEADLINE, UNIT, 10);
        assertThat(this.closer.succeed(units.get(0), "10")).isTrue();
        assertThat(this.closer.fail(units.get(1), "упал", "битая")).isTrue();
        assertThat(this.db.status(TaskLevel.TASK, task.id())).isEqualTo("PENDING");
        return new Family(
                task,
                expansion.lease().attemptId(),
                units.get(0).target().id(),
                units.get(1).target().id());
    }

    /** То же, и сборка прошла — задача {@code WARNING}. */
    private Family collected() {
        final Family family = this.readyForCollect();
        final ClaimedAttempt collect = this.claimer.claim(DEADLINE, UNIT, 1).getFirst();
        assertThat(this.closer.collected(collect, "\"свод\"", TaskLevel.UNIT, family.expansion()))
                .isTrue();
        assertThat(this.db.status(TaskLevel.TASK, family.task().id())).isEqualTo("WARNING");
        return family;
    }

    private void expand(final ClaimedAttempt expansion, final int children) {
        final TaskEmitter emitter =
                new TaskEmitter(expansion, TaskLevel.UNIT, this.db.children(), this.codec, this.db.ids);
        for (int i = 0; i < children; i++) {
            emitter.emit(i);
        }
        assertThat(emitter.finish()).isTrue();
        assertThat(this.closer.expanded(expansion, emitter.flushed())).isTrue();
    }

    private static List<Object> race(final Callable<?> first, final Callable<?> second) throws Exception {
        final CountDownLatch start = new CountDownLatch(1);
        final ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            final Future<?> a = threads.submit(() -> {
                start.await();
                return first.call();
            });
            final Future<?> b = threads.submit(() -> {
                start.await();
                return second.call();
            });
            start.countDown();
            return List.of(a.get(), b.get());
        } finally {
            threads.shutdownNow();
        }
    }

    /**
     * Задача с двумя подзадачами одного раскрытия.
     *
     * @param task задача
     * @param expansion попытка раскрытия
     * @param succeeded успешная подзадача
     * @param failed упавшая подзадача
     */
    private record Family(CreatedTask task, UUID expansion, UUID succeeded, UUID failed) {

        TaskEntityRef taskRef() {
            return new TaskEntityRef(
                    TaskLevel.TASK, this.task.id(), this.task.execution().createdAt());
        }

        TaskEntityRef unit(final UUID id) {
            return new TaskEntityRef(TaskLevel.UNIT, id, this.task.execution().createdAt());
        }
    }
}
