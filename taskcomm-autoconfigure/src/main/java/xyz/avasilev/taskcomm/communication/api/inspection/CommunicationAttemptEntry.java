package xyz.avasilev.taskcomm.communication.api.inspection;

import java.time.Instant;
import java.util.UUID;

/**
 * Строка попытки коммуникации без обмена — для схемы дела
 * (docs/adr/0166-execution-outline-units-and-attempts.md): чья попытка, фаза и исход. По ней созданные
 * сущности встают под попытку, которая их создала.
 *
 * @param communicationId коммуникация
 * @param id попытка
 * @param phase фаза
 * @param status исход или {@code null} у незакрытой попытки
 * @param statusDescription причина исхода или {@code null}
 * @param startedAt начало или {@code null} — попытка ещё не захвачена
 * @param finishedAt конец или {@code null}
 */
public record CommunicationAttemptEntry(
        UUID communicationId,
        UUID id,
        String phase,
        String status,
        String statusDescription,
        Instant startedAt,
        Instant finishedAt) {}
