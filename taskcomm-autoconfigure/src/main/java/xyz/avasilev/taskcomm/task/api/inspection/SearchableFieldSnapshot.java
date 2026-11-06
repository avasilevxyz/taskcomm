package xyz.avasilev.taskcomm.task.api.inspection;

import java.time.Instant;
import xyz.avasilev.taskcomm.task.api.processor.FieldType;

/**
 * Строка реестра полей поиска {@code tc_searchable_field} — для формы поиска в UI
 * (docs/adr/0037-search-field-registry.md). Одно имя поля может быть у нескольких типов и уровней,
 * с разными подписями: стартер отдаёт строки как есть, группировка — дело UI.
 *
 * @param level уровень сущности
 * @param taskType тип задачи
 * @param name имя поля в запросе поиска
 * @param type тип значения
 * @param jsonPath путь в нагрузке
 * @param title подпись или {@code null}
 * @param description пояснение или {@code null}
 * @param lastDeclaredAt когда поле последний раз объявлял процессор
 */
public record SearchableFieldSnapshot(
        TaskSnapshot.Level level,
        String taskType,
        String name,
        FieldType type,
        String jsonPath,
        String title,
        String description,
        Instant lastDeclaredAt) {}
