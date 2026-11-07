package xyz.avasilev.taskcomm.task.api.processor;

import java.util.UUID;
import xyz.avasilev.taskcomm.task.api.TaskUnitStatus;

/**
 * Подзадача в потоке сборки: всё, что нужно процессору, чтобы назвать конкретный бизнес-объект
 * в своде (docs/adr/0059-collect-contract.md). В поток идут все терминальные подзадачи, а не только
 * успешные: свод при {@code WARNING} обязан называть упавшие объекты.
 *
 * @param id идентификатор подзадачи
 * @param payload бизнес-объект подзадачи
 * @param result результат её обработки или {@code null} — у неуспешных и у вернувших {@code null}
 * @param status терминальный статус
 * @param statusDescription причина статуса или {@code null}
 * @param <TPayload> бизнес-объект подзадачи
 * @param <TResult> результат обработки подзадачи
 */
public record CollectedUnit<TPayload, TResult>(
        UUID id, TPayload payload, TResult result, TaskUnitStatus status, String statusDescription) {}
