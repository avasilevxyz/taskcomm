package xyz.avasilev.taskcomm.communication.internal.processor;

import xyz.avasilev.taskcomm.communication.api.processor.CommunicationProcessor;
import xyz.avasilev.taskcomm.communication.internal.ContractShape;

/**
 * Процессор в реестре — со всем, что о нём известно при старте.
 *
 * @param system внешняя система
 * @param processor бин процессора
 * @param processorClass класс процессора без прокси
 * @param shape форма транспортного контракта
 * @param types типы доменных форм из дженерика
 */
public record RegisteredProcessor(
        String system,
        CommunicationProcessor<?, ?, ?> processor,
        Class<?> processorClass,
        ContractShape shape,
        ProcessorTypes types) {}
