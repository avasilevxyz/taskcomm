package xyz.avasilev.taskcomm.task.internal.processor;

import xyz.avasilev.taskcomm.task.api.processor.TaskProcessor;
import xyz.avasilev.taskcomm.task.internal.ContractShape;

/**
 * Процессор в реестре — со всем, что о нём выводится при старте.
 *
 * @param taskType тип задачи
 * @param processor бин процессора
 * @param processorClass класс процессора без прокси — на нём ищутся аннотации
 * @param shape форма контракта
 * @param types типы нагрузок и результатов из дженерика
 * @param deprecated класс помечен {@code @Deprecated}: постановка новых задач запрещена
 *     (docs/adr/0028-contract-migration.md)
 */
public record RegisteredProcessor(
        String taskType,
        TaskProcessor<?> processor,
        Class<?> processorClass,
        ContractShape shape,
        ProcessorTypes types,
        boolean deprecated) {}
