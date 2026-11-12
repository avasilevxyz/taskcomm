/**
 * Общее для всего стартера: базовое исключение {@link xyz.avasilev.taskcomm.TaskcommException}
 * и его наследники, которые не принадлежат ни одной подсистеме. Подсистемы, агрегат дела и код
 * схемы опираются на этот пакет, не опираясь друг на друга.
 *
 * <p>Пути вида {@code docs/...} в Javadoc стартера считаются от корня репозитория
 * <a href="https://github.com/avasilevxyz/taskcomm/tree/v2.1.0">avasilevxyz/taskcomm</a> на теге
 * этой версии. Публичный API — всё вне пакетов {@code internal}. Памятка для ИИ-агентов, которые
 * пишут код потребителя, лежит в jar как {@code META-INF/taskcomm/AGENTS.md}.
 */
package xyz.avasilev.taskcomm;
