/**
 * Внутренняя реализация подсистемы задач. Не публичный API — граница проверяется ArchUnit-тестом
 * (docs/adr/0004-public-api-boundary.md), а не только именем пакета.
 *
 * <p>Корень — общая модель сущности и попытки, SQL, граница партиций и кодек нагрузок. Дальше группы
 * по обязанностям, слоями снизу вверх (docs/adr/0143-internal-subpackages.md): {@code processor},
 * {@code settings}, {@code creation}, {@code lifecycle}, {@code claim}, {@code observation},
 * {@code engine}, {@code inspection}, {@code service}. Группа зависит только от групп ниже себя.
 */
package xyz.avasilev.taskcomm.task.internal;
