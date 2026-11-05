package xyz.avasilev.taskcomm.task.internal;

import java.util.Locale;

/**
 * Попытка под наблюдением: ключи MDC вокруг всей работы воркера и метрики закрытой попытки
 * (docs/rules/observability.md).
 *
 * <p>MDC открывается до вызова процессора и восстанавливается снимком после закрытия попытки: лог
 * неуспешной попытки, который пишет воркер, получает и ключи стартера, и то, что положил процессор.
 * Попытка, которую не удалось закрыть, в метрики не идёт — её исход узнает только переподхват.
 */
final class TaskAttemptObserver {

    private final TaskMdc mdc;

    private final TaskMetrics metrics;

    /**
     * Собирает наблюдение.
     *
     * @param observation префикс MDC и метрики
     */
    TaskAttemptObserver(final TaskObservation observation) {
        this.mdc = new TaskMdc(observation.mdcPrefix());
        this.metrics = observation.metrics();
    }

    /**
     * Выполняет попытку воркером под ключами MDC и учитывает её в метриках.
     *
     * @param attempt захваченная работа
     * @param worker воркер
     */
    void execute(final ClaimedAttempt attempt, final TaskWorker worker) {
        final long started = System.nanoTime();
        try (TaskMdc.Scope _ = this.mdc.open(attempt)) {
            final String outcome = worker.execute(attempt);
            if (outcome != null) {
                this.metrics.attempt(
                        attempt.taskType(),
                        attempt.target().level().name().toLowerCase(Locale.ROOT),
                        attempt.phase().name().toLowerCase(Locale.ROOT),
                        outcome,
                        attempt.history().retry(),
                        System.nanoTime() - started);
            }
        }
    }
}
