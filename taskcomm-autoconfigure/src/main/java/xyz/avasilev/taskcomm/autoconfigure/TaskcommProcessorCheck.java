package xyz.avasilev.taskcomm.autoconfigure;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ListableBeanFactory;
import xyz.avasilev.taskcomm.communication.api.CommunicationProcessor;
import xyz.avasilev.taskcomm.communication.internal.CommunicationEngine;
import xyz.avasilev.taskcomm.task.api.TaskProcessor;
import xyz.avasilev.taskcomm.task.internal.TaskEngine;

/**
 * Проверка при старте: есть ли процессоры у включённых подсистем (docs/adr/0051-engine-health.md).
 * Процессоры считаются по именам бинов, без их создания. Обе подсистемы выключены — проверять нечего.
 *
 * @param processors сколько процессоров у включённых подсистем
 */
@Slf4j
record TaskcommProcessorCheck(int processors) {

    /**
     * Считает процессоры и предупреждает, если их нет.
     *
     * @param beanFactory фабрика бинов
     * @return итог проверки
     */
    static TaskcommProcessorCheck run(final ListableBeanFactory beanFactory) {
        final boolean tasks = beanFactory.getBeanNamesForType(TaskEngine.class, false, false).length > 0;
        final boolean communications =
                beanFactory.getBeanNamesForType(CommunicationEngine.class, false, false).length > 0;
        int count = 0;
        if (tasks) {
            count += beanFactory.getBeanNamesForType(TaskProcessor.class, true, false).length;
        }
        if (communications) {
            count += beanFactory.getBeanNamesForType(CommunicationProcessor.class, true, false).length;
        }
        if ((tasks || communications) && count == 0) {
            log.warn("taskcomm подключён, но не зарегистрировано ни одного процессора — ни TaskProcessor, ни "
                    + "CommunicationProcessor. Почти наверняка это ошибка сборки: процессоры объявляются бинами "
                    + "в коде приложения. Движки исправны, им просто нечего делать; не нужна подсистема — "
                    + "выключите её свойством taskcomm.<подсистема>.enabled (docs/adr/0051-engine-health.md).");
        }
        return new TaskcommProcessorCheck(count);
    }
}
