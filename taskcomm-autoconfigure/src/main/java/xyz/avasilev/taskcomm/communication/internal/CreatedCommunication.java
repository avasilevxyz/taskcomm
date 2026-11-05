package xyz.avasilev.taskcomm.communication.internal;

import java.time.Instant;
import java.util.UUID;
import xyz.avasilev.taskcomm.execution.api.ExecutionRef;
import xyz.avasilev.taskcomm.execution.api.Origin;

/**
 * Созданная коммуникация.
 *
 * @param id идентификатор
 * @param execution дело
 * @param createdAt время создания по часам базы
 * @param attemptId первая попытка
 * @param origin создатель или {@code null}; у входящей с известным ключом корреляции — якорь
 * @param duplicate совпадение дедупликации — коммуникация создана сразу {@code SKIPPED}; иначе {@code null}
 */
record CreatedCommunication(
        UUID id,
        ExecutionRef execution,
        Instant createdAt,
        UUID attemptId,
        Origin origin,
        CommunicationDedup.Match duplicate) {}
