package xyz.avasilev.taskcomm.task.internal.processor;

import org.springframework.core.ResolvableType;
import xyz.avasilev.taskcomm.TaskcommConfigurationException;
import xyz.avasilev.taskcomm.task.api.processor.BatchTaskProcessor;
import xyz.avasilev.taskcomm.task.api.processor.FlatTaskProcessor;
import xyz.avasilev.taskcomm.task.api.processor.UnitTaskProcessor;
import xyz.avasilev.taskcomm.task.internal.ContractShape;

/**
 * Java-типы нагрузок и результатов процессора, восстановленные из его дженерика
 * (docs/adr/0012-payload-storage.md). FQCN в базе не хранится: тип знает только процессор.
 * Буквы — как в контракте (docs/adr/0059-collect-contract.md); у уровней, которых у контракта нет,
 * поле пустое.
 *
 * @param task {@code TTaskPayload} — параметры задачи
 * @param batch {@code TBatchPayload} — параметры группы
 * @param unit {@code TUnitPayload} — бизнес-объект подзадачи
 * @param unitResult {@code TUnitResult} — результат подзадачи
 * @param batchResult {@code TBatchResult} — результат группы
 * @param taskResult {@code TTaskResult} — результат задачи
 */
public record ProcessorTypes(
        ResolvableType task,
        ResolvableType batch,
        ResolvableType unit,
        ResolvableType unitResult,
        ResolvableType batchResult,
        ResolvableType taskResult) {

    /**
     * Типы процессора этой формы. Все параметры обязаны быть известны по классу: иначе нагрузку
     * не во что разобрать, и лучше узнать это при старте.
     *
     * @param processorClass класс процессора без прокси
     * @param shape форма контракта
     * @param taskType тип задачи — для сообщения
     * @return типы
     * @throws TaskcommConfigurationException если параметр типа не выводится из класса
     */
    static ProcessorTypes of(final Class<?> processorClass, final ContractShape shape, final String taskType) {
        final ResolvableType self = ResolvableType.forClass(processorClass);
        return switch (shape) {
            case FLAT -> {
                final ResolvableType flat = self.as(FlatTaskProcessor.class);
                yield new ProcessorTypes(
                        resolved(flat, 0, taskType, "TTaskPayload"),
                        null,
                        null,
                        null,
                        null,
                        resolved(flat, 1, taskType, "TTaskResult"));
            }
            case UNIT -> {
                final ResolvableType unit = self.as(UnitTaskProcessor.class);
                yield new ProcessorTypes(
                        resolved(unit, 0, taskType, "TTaskPayload"),
                        null,
                        resolved(unit, 1, taskType, "TUnitPayload"),
                        resolved(unit, 2, taskType, "TUnitResult"),
                        null,
                        resolved(unit, 3, taskType, "TTaskResult"));
            }
            case BATCH -> {
                final ResolvableType batch = self.as(BatchTaskProcessor.class);
                yield new ProcessorTypes(
                        resolved(batch, 0, taskType, "TTaskPayload"),
                        resolved(batch, 1, taskType, "TBatchPayload"),
                        resolved(batch, 2, taskType, "TUnitPayload"),
                        resolved(batch, 3, taskType, "TUnitResult"),
                        resolved(batch, 4, taskType, "TBatchResult"),
                        resolved(batch, 5, taskType, "TTaskResult"));
            }
        };
    }

    private static ResolvableType resolved(
            final ResolvableType contract, final int index, final String taskType, final String parameter) {
        final ResolvableType generic = contract.getGeneric(index);
        if (generic.resolve() == null) {
            throw new TaskcommConfigurationException("Процессор типа задачи '" + taskType + "': параметр " + parameter
                    + " не выводится из класса процессора. Нагрузку и результат не во что разобрать — укажите "
                    + "конкретные типы в объявлении класса (docs/adr/0012-payload-storage.md).");
        }
        return generic;
    }
}
