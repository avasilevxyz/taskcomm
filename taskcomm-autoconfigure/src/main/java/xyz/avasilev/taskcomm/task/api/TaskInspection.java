package xyz.avasilev.taskcomm.task.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import xyz.avasilev.taskcomm.execution.api.HistoryWindow;
import xyz.avasilev.taskcomm.execution.api.Page;
import xyz.avasilev.taskcomm.execution.api.PageRequest;

/**
 * Чтение задач для разбора и выдачи — то, на чём стоит REST (docs/adr/0138-inspection-api.md,
 * docs/adr/0087-architecture-rules.md). {@link TaskService} остаётся про работу приложения.
 *
 * <p>Выдача — снимками с нагрузкой сырым JSON и читается и у типов без процессора. Списки идут
 * в окне чтения истории по времени создания дела (docs/adr/0101-active-partition-horizon.md),
 * страницами по ключу, от новых к старым; страница называет окно, в котором искали.
 */
public interface TaskInspection {

    /**
     * Сущности уровня по условиям, от новых к старым.
     *
     * @param query условия; с делом — чтение в его партиции, окно не применяется
     * @param window окно чтения истории
     * @param page страница
     * @return страница снимков
     * @throws UnknownSearchFieldException если поле поиска не объявлено или сочетание полей не объявил
     *     целиком ни один тип; значение не подошло ни одному объявлению
     */
    Page<TaskSnapshot> list(TaskQuery query, HistoryWindow window, PageRequest page);

    /**
     * Сущность по идентификатору — в окне.
     *
     * @param level уровень
     * @param id идентификатор
     * @param window окно чтения истории
     * @return снимок или пусто — нет в окне
     */
    Optional<TaskSnapshot> find(TaskSnapshot.Level level, UUID id, HistoryWindow window);

    /**
     * Журнал попыток сущности с результатами, в порядке начала.
     *
     * @param entity сущность
     * @return попытки
     */
    List<TaskAttemptSnapshot> attempts(TaskSnapshot entity);

    /**
     * Реестр полей поиска — для формы поиска в UI.
     *
     * @return строки реестра
     */
    List<SearchableFieldSnapshot> searchableFields();

    /**
     * Сводка: сколько сущностей каждого уровня и типа в каждом статусе, с признаком осиротевшего типа.
     *
     * @param window окно чтения истории
     * @return строки сводки
     */
    List<TaskTypeSummary> summary(HistoryWindow window);
}
