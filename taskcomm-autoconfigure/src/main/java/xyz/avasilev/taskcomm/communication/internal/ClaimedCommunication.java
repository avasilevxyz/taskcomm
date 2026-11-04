package xyz.avasilev.taskcomm.communication.internal;

import java.time.Instant;
import java.util.UUID;
import xyz.avasilev.taskcomm.execution.api.ExecutionRef;
import xyz.avasilev.taskcomm.execution.api.Origin;

/**
 * Захваченная работа — открытая попытка под арендой этого экземпляра и коммуникация, над которой
 * она идёт.
 *
 * @param lease аренда попытки
 * @param target коммуникация
 * @param system внешняя система
 * @param direction направление
 * @param phase фаза попытки
 * @param history номер попытки и признак повтора
 * @param snapshot снимок коммуникации на момент захвата
 */
record ClaimedCommunication(
        AttemptLease lease,
        CommunicationRef target,
        String system,
        Direction direction,
        CommunicationPhase phase,
        AttemptHistory history,
        Snapshot snapshot) {

    /**
     * Дело коммуникации.
     *
     * @return ссылка на дело
     */
    ExecutionRef execution() {
        return new ExecutionRef(this.snapshot.executionId(), this.target.executionCreatedAt());
    }

    /**
     * Снимок коммуникации — то, из чего воркер собирает её публичное представление.
     *
     * @param executionId дело
     * @param priority приоритет
     * @param createdAt время создания
     * @param origin указатель на создателя или {@code null}
     * @param payload доменная форма запроса в JSON
     */
    record Snapshot(UUID executionId, int priority, Instant createdAt, Origin origin, String payload) {}
}
