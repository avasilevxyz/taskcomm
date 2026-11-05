package xyz.avasilev.taskcomm.task.internal;

import java.time.Duration;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.function.SingletonSupplier;
import xyz.avasilev.taskcomm.task.api.TaskCancelResult;
import xyz.avasilev.taskcomm.task.api.TaskRestartResult;

/**
 * Управляющие операции {@link xyz.avasilev.taskcomm.task.api.TaskService}: отмена, перезапуск и ручное
 * удаление результатов (docs/adr/0125-task-cancel-api.md, docs/adr/0139-restart-api.md).
 */
class TaskAdministration {

    private final TaskCancellation cancellation;

    private final TaskRestart restart;

    private final Supplier<TaskEngine> engine;

    /**
     * Собирает операции.
     *
     * @param dataSource {@code DataSource} стартера
     * @param transactionManager менеджер транзакций стартера
     * @param pollers раскладка поллеров — шаг дедлайна по типу
     * @param engine движок — лениво; ручное удаление результатов идёт на его планировщике
     */
    TaskAdministration(
            final DataSource dataSource,
            final PlatformTransactionManager transactionManager,
            final PollerLayout pollers,
            final Supplier<TaskEngine> engine) {
        this.cancellation = new TaskCancellation(
                dataSource, transactionManager, new TaskChildren(dataSource, transactionManager, pollers));
        this.restart = new TaskRestart(dataSource, transactionManager, pollers);
        this.engine = SingletonSupplier.of(engine);
    }

    TaskCancelResult cancel(final TaskEntityRef target) {
        return this.cancellation.cancel(target);
    }

    TaskRestartResult restart(final TaskEntityRef target) {
        return this.restart.restart(target);
    }

    int discardChildResults(final Duration olderThan) {
        if (olderThan.isNegative()) {
            throw new IllegalArgumentException("Срок не может быть отрицательным: " + olderThan);
        }
        return this.engine.get().discardResults(olderThan);
    }
}
