/**
 * Контракты процессоров коммуникаций (docs/adr/0143-internal-subpackages.md) — то, что пишет
 * потребитель: запечатанная по транспортам иерархия {@code CommunicationProcessor} — HTTP с REST
 * и SOAP, Kafka и сырой транспорт, — {@code CommunicationContext}, {@code BodyDecoder}
 * и {@code @CommunicationProcessorSettings}. Контекст обработки здесь свой, а не общий с задачами.
 * Запечатанная иерархия обязана лежать в одном пакете.
 */
package xyz.avasilev.taskcomm.communication.api.processor;
