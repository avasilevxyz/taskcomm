package xyz.avasilev.taskcomm.rest;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import xyz.avasilev.taskcomm.execution.api.Execution;
import xyz.avasilev.taskcomm.execution.api.ExecutionRef;
import xyz.avasilev.taskcomm.execution.api.ExecutionService;

/**
 * Дело из параметров запроса — всегда найденное, а не собранное: время создания в запросе — ключ
 * партиции, а не доказательство, что дело есть. Иначе чтение показало бы несуществующее дело пустым,
 * а создание поставило бы сущность в дело, которого нет.
 */
final class RestExecutions {

    private RestExecutions() {}

    /**
     * Ищет дело.
     *
     * @param executions сервис дел
     * @param id идентификатор
     * @param createdAt время создания или {@code null} — тогда по всем партициям
     * @return ссылка на найденное дело или пусто
     */
    static Optional<ExecutionRef> find(final ExecutionService executions, final UUID id, final Instant createdAt) {
        return (createdAt == null ? executions.find(id) : executions.find(new ExecutionRef(id, createdAt)))
                .map(Execution::ref);
    }

    /**
     * Поиск дела для {@link RestParams#execution(BiFunction)}.
     *
     * @param executions сервис дел
     * @return поиск по идентификатору и времени создания
     */
    static BiFunction<UUID, Instant, Optional<ExecutionRef>> lookup(final ExecutionService executions) {
        return (id, createdAt) -> find(executions, id, createdAt);
    }
}
