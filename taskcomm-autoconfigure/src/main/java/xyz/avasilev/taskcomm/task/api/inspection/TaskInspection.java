package xyz.avasilev.taskcomm.task.api.inspection;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import xyz.avasilev.taskcomm.PayloadShape;
import xyz.avasilev.taskcomm.SqlQuery;
import xyz.avasilev.taskcomm.execution.api.ExecutionRef;
import xyz.avasilev.taskcomm.execution.api.HistoryWindow;
import xyz.avasilev.taskcomm.execution.api.Page;
import xyz.avasilev.taskcomm.execution.api.PageRequest;
import xyz.avasilev.taskcomm.task.api.TaskService;

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
     * Число сущностей уровня по тем же условиям, что у {@link #list}
     * (docs/adr/0157-attempt-children-through-batches.md). Считает строки: для детей попытки
     * раскрытия — по индексу попытки, а по окну без других условий — дорого, и это не для опроса.
     *
     * @param query условия; с делом — счёт в его партиции, окно не применяется
     * @param window окно чтения истории
     * @return число сущностей
     * @throws UnknownSearchFieldException как у {@link #list}
     */
    long count(TaskQuery query, HistoryWindow window);

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

    /**
     * Счётчики подзадач дела по родителю и статусу — прогресс без чтения подзадач
     * (docs/adr/0150-execution-outline-and-meta.md). Чтение идёт в партиции дела, окно не применяется.
     *
     * @param execution дело
     * @return строки «задача, группа, статус, число»
     */
    List<TaskUnitCount> unitCounts(ExecutionRef execution);

    /**
     * Запрос, которым {@link #list} читает страницу, — для разбора (docs/adr/0165-read-sql.md). Текст тот
     * же, что выполняется; предел — размер страницы, без строки, по которой список узнаёт о следующей.
     * Поиск по нагрузке разворачивается по реестру полей, как при чтении.
     *
     * @param query условия
     * @param window окно чтения истории
     * @param page страница
     * @return текст и параметры
     * @throws UnknownSearchFieldException как у {@link #list}
     * @throws UnsupportedOperationException если реализация SQL не отдаёт
     */
    default SqlQuery listSql(final TaskQuery query, final HistoryWindow window, final PageRequest page) {
        throw new UnsupportedOperationException("SQL чтения эта реализация не отдаёт");
    }

    /**
     * Запрос, которым {@link #attempts} читает журнал попыток, — для разбора.
     *
     * @param entity сущность
     * @return текст и параметры
     * @throws UnsupportedOperationException если реализация SQL не отдаёт
     */
    default SqlQuery attemptsSql(final TaskSnapshot entity) {
        throw new UnsupportedOperationException("SQL чтения эта реализация не отдаёт");
    }

    /**
     * Запрос, которым {@link #unitCounts} считает подзадачи дела, — для разбора.
     *
     * @param execution дело
     * @return текст и параметры
     * @throws UnsupportedOperationException если реализация SQL не отдаёт
     */
    default SqlQuery unitCountsSql(final ExecutionRef execution) {
        throw new UnsupportedOperationException("SQL чтения эта реализация не отдаёт");
    }

    /**
     * Приоритет по умолчанию у типов задач с процессором — итог лестницы настроек
     * (docs/adr/0153-ui-creation-refinements.md): его получит задача, поставленная без своего приоритета.
     *
     * @return тип задачи → приоритет, по имени типа
     */
    Map<String, Integer> defaultPriorities();

    /**
     * Поллер, который захватывает работу типа на этом экземпляре: выделенный или {@code main}
     * (docs/adr/0156-communication-keys-and-pollers-in-reading.md). Вычисляется из настроек,
     * в попытке не хранится.
     *
     * @return тип задачи с процессором → имя поллера, по имени типа
     */
    Map<String, String> pollers();

    /**
     * Структура JSON параметров задачи этого типа — ключи и виды значений, которые ждёт разбор
     * (docs/adr/0160-payload-shape.md). Строится обходом класса {@code TTaskPayload} маппером
     * нагрузок; это подсказка для формы создания, последнее слово — за разбором в {@code submitJson}.
     *
     * @param taskType тип задачи
     * @return структура; пусто, если у типа нет процессора на этом экземпляре
     */
    Optional<PayloadShape> payloadShape(String taskType);
}
