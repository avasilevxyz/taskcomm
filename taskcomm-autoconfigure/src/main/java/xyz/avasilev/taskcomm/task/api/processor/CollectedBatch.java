package xyz.avasilev.taskcomm.task.api.processor;

import java.util.UUID;
import xyz.avasilev.taskcomm.task.api.TaskBatchStatus;

/**
 * Группа в потоке сборки задачи (docs/adr/0059-collect-contract.md). В поток идут все терминальные
 * группы последней попытки раскрытия задачи.
 *
 * @param id идентификатор группы
 * @param payload параметры группы
 * @param result результат сборки группы или {@code null} — у неуспешных и у вернувших {@code null}
 * @param status терминальный статус
 * @param statusDescription причина статуса или {@code null}
 * @param <TPayload> параметры группы
 * @param <TResult> результат сборки группы
 */
public record CollectedBatch<TPayload, TResult>(
        UUID id, TPayload payload, TResult result, TaskBatchStatus status, String statusDescription) {}
