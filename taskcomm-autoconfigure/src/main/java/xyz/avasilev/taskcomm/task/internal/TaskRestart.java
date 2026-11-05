package xyz.avasilev.taskcomm.task.internal;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.avasilev.taskcomm.task.api.TaskRestartResult;

/**
 * Ручной перезапуск задачи, группы и подзадачи (docs/adr/0049-restart-semantics.md,
 * docs/adr/0043-manual-restart-resets-attempts.md, docs/adr/0139-restart-api.md).
 *
 * <p>Одна транзакция: блокировка самой сущности, затем перевод каждого предка снизу вверх условным
 * обновлением в {@code AWAITING_CHILDREN} с фазой {@code COLLECT}, затем сама сущность в {@code PENDING}
 * с новым циклом повторов. Условие перевода предка и есть все проверки: на нём нет отметки отмены
 * (docs/adr/0071-no-restart-after-cancel.md), результаты его детей не удалены, он не собирается
 * (docs/adr/0097-no-restart-during-collect.md), ребёнок принадлежит его последней и успешной попытке
 * раскрытия (docs/adr/0098-restart-only-latest-expansion.md). Предок не обновился — откат, а причину
 * называет чтение его строки в той же транзакции. Захват сборки, перезапуск предка и перезапуск
 * ребёнка сериализуются на строке предка; порядок блокировок — от ребёнка к родителю, как у воркера.
 */
@Slf4j
class TaskRestart {

    /** Из каких статусов перезапускаются группа и задача — подзадача только из {@code FAILED}. */
    private static final Set<String> PARENT_RESTARTABLE = Set.of("SUCCEEDED", "WARNING", "FAILED", "SKIPPED");

    private static final String LOCK = """
            SELECT status, cancel_requested_at IS NOT NULL AS cancelled, task_type, {link}
              FROM {table}
             WHERE execution_created_at = :executionCreatedAt AND id = :id
               FOR UPDATE
            """;

    /** Перевод предка; возвращает его собственную попытку-родителя — для проверки уровнем выше. */
    private static final String ANCESTOR = """
            UPDATE {table} p
               SET status = 'AWAITING_CHILDREN', phase = 'COLLECT', status_description = NULL,
                   retry_cycle_started_at = now(), updated_at = now()
             WHERE p.execution_created_at = :executionCreatedAt AND p.id = :id
               AND p.cancel_requested_at IS NULL
               AND p.child_results_discarded_at IS NULL
               AND (p.status IN ('SUCCEEDED', 'WARNING', 'FAILED', 'SKIPPED', 'AWAITING_CHILDREN')
                    OR (p.status = 'PENDING' AND p.phase = 'COLLECT'))
               AND EXISTS (SELECT 1 FROM tc_task_attempt x
                            WHERE x.execution_created_at = :executionCreatedAt AND x.id = :childAttemptId
                              AND x.status = 'SUCCEEDED')
               AND NOT EXISTS (SELECT 1 FROM tc_task_attempt n
                                WHERE n.execution_created_at = :executionCreatedAt AND n.level = :level
                                  AND n.target_id = p.id AND n.phase = 'EXPAND'
                                  AND n.started_at > (SELECT x.started_at FROM tc_task_attempt x
                                                       WHERE x.execution_created_at = :executionCreatedAt
                                                         AND x.id = :childAttemptId))
            RETURNING {parentAttempt}
            """;

    /** Почему предок не перевёлся — то же условие по частям. */
    private static final String EXPLAIN = """
            SELECT p.status, p.phase, p.cancel_requested_at IS NOT NULL AS cancelled,
                   p.child_results_discarded_at IS NOT NULL AS discarded,
                   EXISTS (SELECT 1 FROM tc_task_attempt n
                            WHERE n.execution_created_at = :executionCreatedAt AND n.level = :level
                              AND n.target_id = p.id AND n.phase = 'EXPAND'
                              AND n.started_at > (SELECT x.started_at FROM tc_task_attempt x
                                                   WHERE x.execution_created_at = :executionCreatedAt
                                                     AND x.id = :childAttemptId)) AS newer
              FROM {table} p
             WHERE p.execution_created_at = :executionCreatedAt AND p.id = :id
            """;

    private static final String RESTART_UNIT = """
            UPDATE tc_task_unit
               SET status = 'PENDING', status_description = NULL, next_attempt_at = now(),
                   retry_cycle_started_at = now(),
                   claim_order_at = now() + make_interval(secs => priority * :deadlineStep), updated_at = now()
             WHERE execution_created_at = :executionCreatedAt AND id = :id
            """;

    /** Новое раскрытие: отметка об удалённых результатах снимается — у новых детей результаты будут. */
    private static final String RESTART_PARENT = """
            UPDATE {table}
               SET status = 'PENDING', phase = 'EXPAND', status_description = NULL, next_attempt_at = now(),
                   retry_cycle_started_at = now(), child_results_discarded_at = NULL,
                   claim_order_at = now() + make_interval(secs => priority * :deadlineStep), updated_at = now()
             WHERE execution_created_at = :executionCreatedAt AND id = :id
            """;

    private final JdbcClient jdbc;

    private final TransactionTemplate transactions;

    private final PollerLayout pollers;

    /**
     * Собирает перезапуск.
     *
     * @param dataSource {@code DataSource} стартера
     * @param transactionManager менеджер транзакций стартера
     * @param pollers раскладка поллеров — шаг дедлайна по типу
     */
    TaskRestart(
            final DataSource dataSource,
            final PlatformTransactionManager transactionManager,
            final PollerLayout pollers) {
        this.jdbc = JdbcClient.create(dataSource);
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.pollers = pollers;
    }

    /**
     * Перезапускает сущность.
     *
     * @param target сущность
     * @return принят перезапуск или отвергнут
     */
    TaskRestartResult restart(final TaskEntityRef target) {
        final TaskRestartResult result = this.transactions.execute(status -> this.inTransaction(target, status));
        return result == null ? new TaskRestartResult.Rejected("перезапуск не выполнен") : result;
    }

    private TaskRestartResult inTransaction(final TaskEntityRef target, final TransactionStatus status) {
        final Optional<Locked> locked = this.lock(target);
        final String rejection = locked.isEmpty() ? "не найдена: " + name(target) : rejection(target, locked.get());
        if (rejection != null) {
            return new TaskRestartResult.Rejected(rejection);
        }
        final String ancestors = this.ancestors(target, locked.get().parent());
        if (ancestors != null) {
            status.setRollbackOnly();
            return new TaskRestartResult.Rejected(ancestors);
        }
        this.jdbc
                .sql(
                        target.level() == TaskLevel.UNIT
                                ? RESTART_UNIT
                                : RESTART_PARENT.replace(
                                        TaskRetries.TABLE, target.level().table()))
                .param(
                        "deadlineStep",
                        TaskSql.seconds(this.pollers.deadlineStep(locked.get().taskType())))
                .param("executionCreatedAt", TaskSql.parameter(target.executionCreatedAt()))
                .param("id", target.id())
                .update();
        log.info("{} перезапущена вручную", name(target));
        return new TaskRestartResult.Accepted(
                target.level() == TaskLevel.UNIT
                        ? "подзадача снова в очереди: обработка заново, новый цикл попыток"
                        : "снова в очереди: новое раскрытие, прежние дети остаются историей");
    }

    private Optional<Locked> lock(final TaskEntityRef target) {
        return this.jdbc
                .sql(LOCK.replace(TaskRetries.TABLE, target.level().table()).replace("{link}", link(target.level())))
                .param("executionCreatedAt", TaskSql.parameter(target.executionCreatedAt()))
                .param("id", target.id())
                .query((rs, rowNum) -> new Locked(
                        rs.getString("status"),
                        rs.getBoolean("cancelled"),
                        rs.getString("task_type"),
                        ClaimRows.parent(rs)))
                .optional();
    }

    /**
     * Переводит предков снизу вверх.
     *
     * @return причина отказа или {@code null}
     */
    private String ancestors(final TaskEntityRef target, final ClaimedEntity.Parent parent) {
        if (parent == null) {
            return null;
        }
        TaskEntityRef ancestor = parent.entity(target.executionCreatedAt());
        UUID childAttempt = parent.attemptId();
        while (ancestor != null) {
            final UUID current = childAttempt;
            final TaskEntityRef updating = ancestor;
            final Optional<Optional<UUID>> moved = this.jdbc
                    .sql(ANCESTOR.replace(TaskRetries.TABLE, updating.level().table())
                            .replace("{parentAttempt}", parentAttempt(updating.level())))
                    .param("executionCreatedAt", TaskSql.parameter(target.executionCreatedAt()))
                    .param("id", updating.id())
                    .param("level", updating.level().name())
                    .param("childAttemptId", current)
                    .query((rs, rowNum) -> Optional.ofNullable(rs.getObject("parent_attempt_id", UUID.class)))
                    .optional();
            if (moved.isEmpty()) {
                return this.explain(updating, current);
            }
            childAttempt = moved.get().orElse(null);
            ancestor = updating.level() == TaskLevel.BATCH
                    ? new TaskEntityRef(TaskLevel.TASK, parent.taskId(), target.executionCreatedAt())
                    : null;
        }
        return null;
    }

    private String explain(final TaskEntityRef ancestor, final UUID childAttempt) {
        return this.jdbc
                .sql(EXPLAIN.replace(TaskRetries.TABLE, ancestor.level().table()))
                .param("executionCreatedAt", TaskSql.parameter(ancestor.executionCreatedAt()))
                .param("id", ancestor.id())
                .param("level", ancestor.level().name())
                .param("childAttemptId", childAttempt)
                .query((rs, rowNum) -> explanation(
                        ancestor,
                        new AncestorState(
                                rs.getString("status"),
                                rs.getString("phase"),
                                rs.getBoolean("cancelled"),
                                rs.getBoolean("discarded"),
                                rs.getBoolean("newer"))))
                .optional()
                .orElse("не найден предок " + name(ancestor));
    }

    private static String explanation(final TaskEntityRef ancestor, final AncestorState state) {
        final String reason;
        if (state.cancelled()) {
            reason = "на предке " + name(ancestor) + " стоит отметка отмены: отменённое не перезапускается, "
                    + "поставьте новую задачу";
        } else if (state.discarded()) {
            reason = "результаты детей предка " + name(ancestor) + " удалены: пересборке не хватит результатов "
                    + "соседей, перезапускайте " + name(ancestor) + " целиком";
        } else if ("RUNNING".equals(state.status()) && "COLLECT".equals(state.phase())) {
            reason = "у предка " + name(ancestor) + " идёт сборка: повторите после её окончания";
        } else if (state.newer() || "EXPAND".equals(state.phase())) {
            reason = "принадлежит прежней попытке раскрытия предка " + name(ancestor)
                    + ": чинится перезапуском этого предка";
        } else {
            reason = "предок " + name(ancestor) + " в статусе " + state.status() + " не принимает перезапуск детей";
        }
        return reason;
    }

    private static String rejection(final TaskEntityRef target, final Locked locked) {
        final String reason;
        if (locked.cancelled() || "CANCELLED".equals(locked.status())) {
            reason = name(target) + " отменена: отменённое не перезапускается, поставьте новую задачу";
        } else if ("ABANDONED".equals(locked.status())) {
            reason = name(target) + " брошена неуспешным раскрытием родителя: перезапускайте родителя";
        } else if (target.level() == TaskLevel.UNIT && !"FAILED".equals(locked.status())) {
            reason = "перезапускается только упавшая подзадача, а " + name(target) + " в статусе " + locked.status();
        } else if (target.level() != TaskLevel.UNIT && !PARENT_RESTARTABLE.contains(locked.status())) {
            reason = "перезапускается только завершённая сущность, а " + name(target) + " в статусе " + locked.status();
        } else {
            reason = null;
        }
        return reason;
    }

    private static String name(final TaskEntityRef entity) {
        return switch (entity.level()) {
            case TASK -> "задача " + entity.id();
            case BATCH -> "группа " + entity.id();
            case UNIT -> "подзадача " + entity.id();
        };
    }

    /** Колонки родства в {@link #LOCK}: у задачи их нет. */
    private static String link(final TaskLevel level) {
        return switch (level) {
            case TASK -> "NULL::uuid AS parent_task_id, NULL::uuid AS parent_batch_id, NULL::uuid AS parent_attempt_id";
            case BATCH -> "task_id AS parent_task_id, NULL::uuid AS parent_batch_id, parent_attempt_id";
            case UNIT -> "task_id AS parent_task_id, batch_id AS parent_batch_id, parent_attempt_id";
        };
    }

    /** Попытка-родитель переведённого предка — у группы своя, у задачи её нет. */
    private static String parentAttempt(final TaskLevel level) {
        return level == TaskLevel.BATCH ? "p.parent_attempt_id" : "NULL::uuid AS parent_attempt_id";
    }

    /**
     * Сущность под блокировкой.
     *
     * @param status статус
     * @param cancelled стоит ли отметка отмены
     * @param taskType тип задачи
     * @param parent родство или {@code null} у задачи
     */
    private record Locked(String status, boolean cancelled, String taskType, ClaimedEntity.Parent parent) {}

    /**
     * Строка предка, который не перевёлся.
     *
     * @param status статус
     * @param phase фаза
     * @param cancelled стоит ли отметка отмены
     * @param discarded удалены ли результаты детей
     * @param newer есть ли попытка раскрытия новее той, которой принадлежит ребёнок
     */
    private record AncestorState(String status, String phase, boolean cancelled, boolean discarded, boolean newer) {}
}
