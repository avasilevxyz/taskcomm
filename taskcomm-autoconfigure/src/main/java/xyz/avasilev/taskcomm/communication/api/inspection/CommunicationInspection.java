package xyz.avasilev.taskcomm.communication.api.inspection;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
}
