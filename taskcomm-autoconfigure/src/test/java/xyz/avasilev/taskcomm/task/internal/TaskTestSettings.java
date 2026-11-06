package xyz.avasilev.taskcomm.task.internal;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import xyz.avasilev.taskcomm.task.internal.settings.AttemptSettings;
import xyz.avasilev.taskcomm.task.internal.settings.ClaimForm;
import xyz.avasilev.taskcomm.task.internal.settings.LeaseSettings;
import xyz.avasilev.taskcomm.task.internal.settings.PollerLayout;
import xyz.avasilev.taskcomm.task.internal.settings.PollerSettings;
import xyz.avasilev.taskcomm.task.internal.settings.ResultDiscard;
import xyz.avasilev.taskcomm.task.internal.settings.RetryPolicy;
import xyz.avasilev.taskcomm.task.internal.settings.SafetySettings;

/** Настройки попыток и поллеров для интеграционных тестов подсистемы задач. */
public final class TaskTestSettings {

    /** Результаты детей не удаляются. */
    public static final ResultDiscard KEEP_RESULTS = new ResultDiscard(ResultDiscard.Mode.NEVER, Duration.ZERO);

    /** Подстраховка с тактом и выдержкой в минуту. */
    public static final SafetySettings SAFETY = new SafetySettings(Duration.ofMinutes(1), Duration.ofMinutes(1));

    private TaskTestSettings() {}

    /** Раскладка из одного основного поллера с шагом дедлайна в минуту. */
    public static PollerLayout layout() {
        return new PollerLayout(mainPoller(ClaimForm.DEADLINE), List.of());
    }

    public static PollerSettings mainPoller(final ClaimForm form) {
        return new PollerSettings(
                PollerSettings.MAIN, Set.of(), Duration.ofSeconds(1), 100, 8, form, Duration.ofMinutes(1), 0.1);
    }

    /**
     * Настройки попыток для тестов.
     *
     * @param maxAttempts лимит попыток
     * @param initialDelay задержка первого повтора
     * @param lease аренда
     * @return настройки
     */
    public static AttemptSettings settings(final int maxAttempts, final Duration initialDelay, final Duration lease) {
        return new AttemptSettings(
                new RetryPolicy(maxAttempts, initialDelay, 2.0, Duration.ofHours(1), 0.2),
                new LeaseSettings(lease, lease.dividedBy(4), lease.multipliedBy(3)));
    }
}
