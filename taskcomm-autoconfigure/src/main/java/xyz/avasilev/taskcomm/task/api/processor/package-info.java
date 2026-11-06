/**
 * Контракты процессоров задач (docs/adr/0143-internal-subpackages.md) — то, что пишет потребитель:
 * запечатанный {@code TaskProcessor} и три его наследника, контекст обработки
 * {@code ProcessingContext} со {@code skip(reason)}, {@code doNotRetry(reason)}, {@code shouldStop()}
 * и {@code executionRef()}, {@code Emitter} и обёртки потока сборки {@code CollectedUnit}
 * и {@code CollectedBatch}, белый список полей поиска {@code SearchableField} и аннотации настроек
 * {@code @TaskProcessorSettings} с сокращениями. Запечатанная иерархия обязана лежать в одном пакете.
 */
package xyz.avasilev.taskcomm.task.api.processor;
