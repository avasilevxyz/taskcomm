package xyz.avasilev.taskcomm.task.internal.observation;

import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import xyz.avasilev.taskcomm.task.internal.ClaimedAttempt;
import xyz.avasilev.taskcomm.task.internal.ClaimedEntity;

/**
 * MDC вокруг попытки задачи — единственное место подсистемы, где стартер его трогает
 * (docs/rules/observability.md, docs/adr/0034-mdc-business-id.md).
 *
 * <p>Ключи — с префиксом {@code taskcomm.logging.mdc-prefix}: дело, задача, группа, подзадача и номер
 * попытки. Снимается <b>восстановлением снимка</b>, а не удалением своих ключей поимённо: всё, что
 * потребитель положил внутри {@code process()}, уходит вместе с ними и не утекает в пул воркеров.
 */
public final class TaskMdc {

    private final String prefix;

    /**
     * Собирает MDC попыток.
     *
     * @param prefix префикс ключей
     */
    public TaskMdc(final String prefix) {
        this.prefix = prefix;
    }

    /**
     * Кладёт ключи попытки поверх текущего MDC.
     *
     * @param attempt захваченная работа
     * @return область, закрытие которой восстанавливает прежний MDC целиком
     */
    public Scope open(final ClaimedAttempt attempt) {
        final Map<String, String> previous = MDC.getCopyOfContextMap();
        this.put("executionId", attempt.entity().executionId());
        final ClaimedEntity.Parent parent = attempt.entity().parent();
        final UUID id = attempt.target().id();
        switch (attempt.target().level()) {
            case TASK -> this.put("taskId", id);
            case BATCH -> {
                this.put("taskId", parent == null ? null : parent.taskId());
                this.put("batchId", id);
            }
            case UNIT -> {
                this.put("taskId", parent == null ? null : parent.taskId());
                this.put("batchId", parent == null ? null : parent.batchId());
                this.put("unitId", id);
            }
        }
        MDC.put(this.prefix + "attempt", Integer.toString(attempt.history().number()));
        return () -> {
            if (previous == null) {
                MDC.clear();
            } else {
                MDC.setContextMap(previous);
            }
        };
    }

    private void put(final String key, final UUID value) {
        if (value != null) {
            MDC.put(this.prefix + key, value.toString());
        }
    }

    /** Область ключей попытки; закрытие восстанавливает прежний MDC. */
    @FunctionalInterface
    public interface Scope extends AutoCloseable {

        /** Восстанавливает прежний MDC. Исключений не бросает. */
        @Override
        void close();
    }
}
