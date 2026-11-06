/**
 * Публичный API подсистемы коммуникаций: модель — {@code Communication} с направлениями
 * {@code Inbound} и {@code Outbound}, статус, сообщение обмена и нейтральные заголовки — и
 * {@code CommunicationService} с созданием исходящих, двумя методами приёма входящих и приёмом
 * колбэка, значения-запросы, ключи дедупликации и корреляции и итоги этих методов. Контракты
 * процессоров — в {@code processor}, чтение — в {@code inspection}
 * (docs/adr/0143-internal-subpackages.md). Модель у коммуникаций плоская и намеренно
 * не унифицирована с иерархией задач (docs/adr/0010-separate-engines.md).
 */
package xyz.avasilev.taskcomm.communication.api;
