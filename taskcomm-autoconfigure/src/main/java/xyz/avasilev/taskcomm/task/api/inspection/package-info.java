/**
 * Чтение задач (docs/adr/0143-internal-subpackages.md): {@code TaskInspection}, его запрос
 * {@code TaskQuery}, снимки сущностей и попыток, сводка по типам и снимок поля поиска.
 * Процессору этот пакет не нужен — он для интерфейсов и сопровождения. Корень {@code api}
 * ссылается сюда только в отмене и перезапуске по снимку.
 */
package xyz.avasilev.taskcomm.task.api.inspection;
