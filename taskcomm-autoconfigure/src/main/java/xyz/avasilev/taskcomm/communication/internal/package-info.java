/**
 * Внутренняя реализация подсистемы коммуникаций: собственные захват, дедупликация и
 * ограничение скорости — без общего кода с подсистемой задач (docs/adr/0010). Не публичный API.
 *
 * <p>Корень — общая модель коммуникации и попытки, SQL, граница партиций и кодек. Дальше группы
 * по обязанностям, слоями снизу вверх (docs/adr/0143-internal-subpackages.md): {@code processor},
 * {@code settings}, {@code keys}, {@code creation}, {@code lifecycle}, {@code observation},
 * {@code guard}, {@code claim}, {@code engine}, {@code inspection}, {@code reception},
 * {@code service}. Группа зависит только от групп ниже себя.
 */
package xyz.avasilev.taskcomm.communication.internal;
