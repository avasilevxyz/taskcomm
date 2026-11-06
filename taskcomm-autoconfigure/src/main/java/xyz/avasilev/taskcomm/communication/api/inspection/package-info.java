/**
 * Чтение коммуникаций (docs/adr/0143-internal-subpackages.md): {@code CommunicationInspection},
 * его запрос {@code CommunicationQuery}, снимки коммуникаций и попыток, сводка, снимки ключа
 * корреляции и размыкателя. Процессору этот пакет не нужен — он для интерфейсов и сопровождения.
 * Корень {@code api} ссылается сюда только в отмене и перезапуске по снимку.
 */
package xyz.avasilev.taskcomm.communication.api.inspection;
