package xyz.avasilev.taskcomm.task.internal.processor;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import xyz.avasilev.taskcomm.TaskcommConfigurationException;
import xyz.avasilev.taskcomm.task.api.processor.BatchTaskProcessor;
import xyz.avasilev.taskcomm.task.api.processor.FlatTaskProcessor;
import xyz.avasilev.taskcomm.task.api.processor.SearchableField;
import xyz.avasilev.taskcomm.task.api.processor.TaskProcessor;
import xyz.avasilev.taskcomm.task.api.processor.UnitTaskProcessor;

/**
 * Проверка шаблонов названия задачи, группы и подзадачи (docs/adr/0162-task-title-template.md): каждая
 * подстановка {@code {имя}} — объявленное поле поиска своего уровня. Значение интерфейс берёт по его
 * пути из нагрузки, и подстановка без поля молча оставалась бы пустой.
 */
final class TaskTitleTemplate {

    /** Подстановка — имя в фигурных скобках. */
    static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]+)}");

    private TaskTitleTemplate() {}

    /**
     * Имена подстановок шаблона в порядке появления.
     *
     * @param template шаблон или {@code null}
     * @return имена; пусто без шаблона
     */
    static Set<String> placeholders(final String template) {
        final Set<String> names = new LinkedHashSet<>();
        if (template != null) {
            final Matcher matcher = PLACEHOLDER.matcher(template);
            while (matcher.find()) {
                names.add(matcher.group(1));
            }
        }
        return names;
    }

    /**
     * Проверяет шаблоны процессора — каждого уровня, который у его контракта есть.
     *
     * @param processor процессор
     * @throws TaskcommConfigurationException если подстановка не названа полем поиска своего уровня
     */
    static void check(final TaskProcessor<?> processor) {
        check(processor, "задачи", processor.taskTitle(), processor.searchableTaskFields());
        switch (processor) {
            case FlatTaskProcessor<?, ?> _ -> {
                // групп и подзадач нет
            }
            case UnitTaskProcessor<?, ?, ?, ?> unit ->
                check(processor, "подзадачи", unit.unitTitle(), unit.searchableUnitFields());
            case BatchTaskProcessor<?, ?, ?, ?, ?, ?> batch -> {
                check(processor, "группы", batch.batchTitle(), batch.searchableBatchFields());
                check(processor, "подзадачи", batch.unitTitle(), batch.searchableUnitFields());
            }
        }
    }

    private static void check(
            final TaskProcessor<?> processor,
            final String level,
            final String template,
            final List<SearchableField> fields) {
        final List<String> declared = fields.stream().map(SearchableField::name).toList();
        for (final String name : placeholders(template)) {
            if (!declared.contains(name)) {
                throw new TaskcommConfigurationException("Процессор типа задачи '" + processor.taskType()
                        + "': в шаблоне названия " + level + " «" + template + "» подстановка {" + name
                        + "} — не поле поиска уровня " + level + ". Объявленные: " + declared
                        + " (docs/adr/0162-task-title-template.md).");
            }
        }
    }
}
