package xyz.avasilev.taskcomm.communication.api.inspection;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import xyz.avasilev.taskcomm.PayloadShape;
import xyz.avasilev.taskcomm.SqlQuery;
import xyz.avasilev.taskcomm.communication.api.CommunicationService;
import xyz.avasilev.taskcomm.execution.api.ExecutionRef;
import xyz.avasilev.taskcomm.execution.api.HistoryWindow;
import xyz.avasilev.taskcomm.execution.api.Page;
import xyz.avasilev.taskcomm.execution.api.PageRequest;

/**
 * Чтение коммуникаций для разбора и выдачи — то, на чём стоит REST (docs/adr/0138-inspection-api.md).
 * {@link CommunicationService} остаётся про работу приложения.
 *
 * <p>Выдача — снимками с доменными формами сырым JSON; списки — в окне чтения истории по времени
 * создания дела, страницами по ключу, от новых к старым.
 */
public interface CommunicationInspection {

    /**
     * Коммуникации по условиям, от новых к старым.
     *
     * @param query условия; с делом — чтение в его партиции, окно не применяется
     * @param window окно чтения истории
     * @param page страница
     * @return страница снимков
     */
    Page<CommunicationSnapshot> list(CommunicationQuery query, HistoryWindow window, PageRequest page);

    /**
     * Коммуникация по идентификатору — в окне.
     *
     * @param id идентификатор
     * @param window окно чтения истории
     * @return снимок или пусто — нет в окне
     */
    Optional<CommunicationSnapshot> find(UUID id, HistoryWindow window);

    /**
     * История попыток с обменом, в порядке создания.
     *
     * @param communication коммуникация
     * @return попытки
     */
    List<CommunicationAttemptSnapshot> attempts(CommunicationSnapshot communication);

    /**
     * Ключи корреляции, зарегистрированные за коммуникациями дела.
     *
     * @param execution дело
     * @return ключи в порядке регистрации
     */
    List<CorrelationKeySnapshot> correlationKeys(ExecutionRef execution);

    /**
     * Запрос, которым {@link #list} читает страницу, — для разбора (docs/adr/0165-read-sql.md). Текст тот
     * же, что выполняется; предел — размер страницы, без строки, по которой список узнаёт о следующей.
     *
     * @param query условия
     * @param window окно чтения истории
     * @param page страница
     * @return текст и параметры
     * @throws UnsupportedOperationException если реализация SQL не отдаёт
     */
    default SqlQuery listSql(final CommunicationQuery query, final HistoryWindow window, final PageRequest page) {
        throw new UnsupportedOperationException("SQL чтения эта реализация не отдаёт");
    }

    /**
     * Запрос, которым {@link #attempts} читает историю попыток, — для разбора.
     *
     * @param communication коммуникация
     * @return текст и параметры
     * @throws UnsupportedOperationException если реализация SQL не отдаёт
     */
    default SqlQuery attemptsSql(final CommunicationSnapshot communication) {
        throw new UnsupportedOperationException("SQL чтения эта реализация не отдаёт");
    }

    /**
     * Запрос, которым {@link #correlationKeys(ExecutionRef)} читает реестр корреляции дела, — для разбора.
     *
     * @param execution дело
     * @return текст и параметры
     * @throws UnsupportedOperationException если реализация SQL не отдаёт
     */
    default SqlQuery correlationKeysSql(final ExecutionRef execution) {
        throw new UnsupportedOperationException("SQL чтения эта реализация не отдаёт");
    }

    /**
     * Ключи корреляции, для которых коммуникация — якорь
     * (docs/adr/0156-communication-keys-and-pollers-in-reading.md).
     *
     * @param communication коммуникация
     * @return ключи в порядке регистрации
     */
    List<CorrelationKeySnapshot> correlationKeys(CommunicationSnapshot communication);

    /**
     * Ключи дедупликации коммуникации (docs/adr/0156-communication-keys-and-pollers-in-reading.md).
     * У дубликата ключей нет: ключи дубликатов не хранятся.
     *
     * @param communication коммуникация
     * @return ключи в порядке регистрации
     */
    List<DedupKeySnapshot> dedupKeys(CommunicationSnapshot communication);

    /**
     * Размыкатели систем с включённым размыкателем — на этом экземпляре.
     *
     * @return состояния
     */
    List<CircuitBreakerSnapshot> circuitBreakers();

    /**
     * Сводка: сколько коммуникаций каждого направления и системы в каждом статусе.
     *
     * @param window окно чтения истории
     * @return строки сводки
     */
    List<CommunicationSummary> summary(HistoryWindow window);

    /**
     * Приоритет по умолчанию у внешних систем с процессором — итог лестницы настроек
     * (docs/adr/0153-ui-creation-refinements.md): его получит коммуникация, созданная без своего приоритета.
     *
     * @return внешняя система → приоритет, по имени системы
     */
    Map<String, Integer> defaultPriorities();

    /**
     * Поллер, который захватывает коммуникации внешней системы на этом экземпляре: выделенный
     * или {@code main} (docs/adr/0156-communication-keys-and-pollers-in-reading.md). Вычисляется
     * из настроек, в попытке не хранится.
     *
     * @return внешняя система с процессором → имя поллера, по имени системы
     */
    Map<String, String> pollers();

    /**
     * Структура JSON запроса к внешней системе — ключи и виды значений {@code TRequest}, которые
     * ждёт разбор (docs/adr/0160-payload-shape.md). Строится обходом класса маппером нагрузок;
     * это подсказка для формы создания, последнее слово — за разбором в {@code sendJson}.
     * Для тела входящей не в JSON — SOAP, XML — структура не говорит ничего.
     *
     * @param system внешняя система
     * @return структура; пусто, если у системы нет процессора на этом экземпляре
     */
    Optional<PayloadShape> requestShape(String system);
}
