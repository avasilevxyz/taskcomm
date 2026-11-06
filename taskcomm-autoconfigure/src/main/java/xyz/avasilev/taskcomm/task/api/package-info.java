/**
 * Публичный API подсистемы задач: модель — {@code Task}, {@code TaskBatch}, {@code TaskUnit} и их
 * статусы — и {@code TaskService} с параметрами постановки и результатами отмены и перезапуска.
 * Контракты процессоров — в {@code processor}, чтение — в {@code inspection}
 * (docs/adr/0143-internal-subpackages.md). Это единственная часть подсистемы, на которую опирается
 * потребитель стартера — реализация живёт в соседнем пакете {@code internal} и обратной
 * совместимости не обещает.
 */
package xyz.avasilev.taskcomm.task.api;
