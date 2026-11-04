package xyz.avasilev.taskcomm.communication.internal;

import java.time.Instant;
import java.util.UUID;
import xyz.avasilev.taskcomm.execution.api.ExecutionRef;

/**
 * Созданная коммуникация.
 *
 * @param id идентификатор
 * @param execution дело
 * @param createdAt время создания по часам базы
 * @param attemptId первая попытка
 */
record CreatedCommunication(UUID id, ExecutionRef execution, Instant createdAt, UUID attemptId) {}
