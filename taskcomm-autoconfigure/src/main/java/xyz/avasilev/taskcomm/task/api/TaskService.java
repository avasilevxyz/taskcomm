package xyz.avasilev.taskcomm.task.api;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import xyz.avasilev.taskcomm.execution.api.ExecutionRef;
import xyz.avasilev.taskcomm.task.api.inspection.TaskInspection;
import xyz.avasilev.taskcomm.task.api.inspection.TaskSnapshot;
import xyz.avasilev.taskcomm.task.api.processor.ProcessingContext;
import xyz.avasilev.taskcomm.task.api.processor.TaskProcessor;

/**
 * Постановка задач и чтение задач дела (docs/adr/0111-task-public-api-shape.md).
 *
 * <p><b>Постановка пишется в текущую транзакцию потребителя</b>: таблица задач работает как outbox,
 * и задача становится видимой воркеру в момент коммита бизнес-данных; откат убирает и её. Вне
 * транзакции — например, из процессора — стартер открывает свою короткую. Транзакция потребителя
 * на другой базе, чем у стартера, — исключение: атомарность пропала бы молча
 * (docs/adr/0017-task-creation-transaction.md, docs/adr/0094-creation-always-in-transaction.md).
 *
 * <p><b>Дело</b>: поставленная из кода потребителя задача заводит новое дело; поставленная внутри
 * процессора — попадает в дело обрабатываемой сущности. Привязка не переживает смену потока:
 * в другом потоке передайте {@link ProcessingContext#executionRef()} явно
 * (docs/adr/0056-execution-ref.md).
 *
 * <p>Тип задачи с процессором, помеченным {@code @Deprecated}, новых задач не принимает. Тип без
 * процессора — исключение до записи.
 */
public interface TaskService {

    /**
     * Ставит задачу.
     *
     * @param taskType тип задачи
     * @param payload параметры задачи
     * @param <TPayload> параметры задачи
     * @return созданная задача в статусе {@code PENDING}
     * @throws xyz.avasilev.taskcomm.TaskcommException если у типа нет процессора или процессор помечен
     *     {@code @Deprecated}
     */
    <TPayload> Task<TPayload> submit(String taskType, TPayload payload);

    /**
     * Ставит задачу в указанное дело — из любого потока.
     *
     * @param taskType тип задачи
     * @param payload параметры задачи
     * @param execution дело; побеждает неявную привязку потока
     * @param <TPayload> параметры задачи
     * @return созданная задача в статусе {@code PENDING}
     * @throws xyz.avasilev.taskcomm.TaskcommException если у типа нет процессора или процессор помечен
     *     {@code @Deprecated}
     */
    <TPayload> Task<TPayload> submit(String taskType, TPayload payload, ExecutionRef execution);

    /**
     * Ставит задачу с необязательными параметрами.
     *
     * @param taskType тип задачи
     * @param payload параметры задачи
     * @param options дело, «не раньше», приоритет
     * @param <TPayload> параметры задачи
     * @return созданная задача в статусе {@code PENDING}
     * @throws xyz.avasilev.taskcomm.TaskcommException если у типа нет процессора или процессор помечен
     *     {@code @Deprecated}
     */
    <TPayload> Task<TPayload> submit(String taskType, TPayload payload, TaskSubmitOptions options);

    /**
     * Ставит задачу процессору, указанному классом: компилятор сверяет тип нагрузки с дженериком
     * процессора. Тип задачи берётся из реестра процессоров.
     *
     * @param processor класс процессора — бина в контексте
     * @param payload параметры задачи
     * @param <TPayload> параметры задачи
     * @return созданная задача в статусе {@code PENDING}
     * @throws xyz.avasilev.taskcomm.TaskcommException если класс не зарегистрирован процессором или помечен
     *     {@code @Deprecated}
     */
    <TPayload> Task<TPayload> submit(Class<? extends TaskProcessor<TPayload>> processor, TPayload payload);

    /**
     * Ставит задачу процессору, указанному классом, в указанное дело.
     *
     * @param processor класс процессора — бина в контексте
     * @param payload параметры задачи
     * @param execution дело; побеждает неявную привязку потока
     * @param <TPayload> параметры задачи
     * @return созданная задача в статусе {@code PENDING}
     * @throws xyz.avasilev.taskcomm.TaskcommException если класс не зарегистрирован процессором или помечен
     *     {@code @Deprecated}
     */
    <TPayload> Task<TPayload> submit(
            Class<? extends TaskProcessor<TPayload>> processor, TPayload payload, ExecutionRef execution);

    /**
     * Ставит задачу процессору, указанному классом, с необязательными параметрами.
     *
     * @param processor класс процессора — бина в контексте
     * @param payload параметры задачи
     * @param options дело, «не раньше», приоритет
     * @param <TPayload> параметры задачи
     * @return созданная задача в статусе {@code PENDING}
     * @throws xyz.avasilev.taskcomm.TaskcommException если класс не зарегистрирован процессором или помечен
     *     {@code @Deprecated}
     */
    <TPayload> Task<TPayload> submit(
            Class<? extends TaskProcessor<TPayload>> processor, TPayload payload, TaskSubmitOptions options);

    /**
     * Ставит задачу по нагрузке сырым JSON — для REST и для кода, у которого доменной формы нет
     * (docs/adr/0140-rest-controllers.md). JSON разбирается в тип из дженерика процессора сразу, до записи.
     *
     * @param taskType строковый тип задачи
     * @param payloadJson нагрузка задачи в JSON
     * @param options опции постановки
     * @return созданная задача
     * @throws xyz.avasilev.taskcomm.TaskcommException если у типа нет процессора или процессор помечен
     *     {@code @Deprecated}
     * @throws xyz.avasilev.taskcomm.UnreadablePayloadException если JSON не разбирается в нагрузку процессора
     */
    Task<?> submitJson(String taskType, String payloadJson, TaskSubmitOptions options);

    /**
     * Задачи дела указанного типа, в порядке создания (docs/adr/0110-execution-composition-reads-in-subsystems.md).
     * Чтение идёт в партиции дела. Из {@code process} подзадачи не вызывайте: миллион подзадач дадут
     * миллион запросов; в {@code collect} это один вызов.
     *
     * @param execution дело
     * @param taskType тип задачи
     * @return задачи дела этого типа; нагрузка восстановлена по дженерику процессора
     * @throws xyz.avasilev.taskcomm.TaskcommException если у типа нет процессора — нагрузку не во что разобрать
     */
    List<Task<?>> findInExecution(ExecutionRef execution, String taskType);

    /**
     * Задачи дела процессора, указанного классом, в порядке создания.
     *
     * @param execution дело
     * @param processor класс процессора — бина в контексте
     * @param <TPayload> параметры задачи
     * @return задачи дела этого типа
     * @throws xyz.avasilev.taskcomm.TaskcommException если класс не зарегистрирован процессором
     */
    <TPayload> List<Task<TPayload>> findInExecution(
            ExecutionRef execution, Class<? extends TaskProcessor<TPayload>> processor);

    /**
     * Текущий результат задачи — результат её последней попытки, если та успешна
     * (docs/adr/0058-task-result-storage.md).
     *
     * @param task задача
     * @return результат, восстановленный по дженерику процессора, или пусто — результата нет,
     *     последняя попытка неуспешна или процессор возвращает {@link Void}
     * @throws xyz.avasilev.taskcomm.TaskcommException если у типа нет процессора — нагрузку не во что разобрать
     */
    Optional<Object> result(Task<?> task);

    /**
     * Текущий результат задачи указанного класса.
     *
     * @param task задача
     * @param resultType класс результата задачи — тот, что стоит в дженерике процессора
     * @param <TResult> результат задачи
     * @return результат или пусто
     * @throws xyz.avasilev.taskcomm.TaskcommException если класс не совпадает с результатом процессора или у типа нет
     *     процессора
     */
    <TResult> Optional<TResult> result(Task<?> task, Class<TResult> resultType);

    /**
     * Отменяет задачу целиком (docs/adr/0070-cancellation-on-any-entity.md,
     * docs/adr/0125-task-cancel-api.md). Отметка ставится только на задаче; не начинавшиеся
     * потомки становятся {@code SKIPPED}, ждавшие повтора — {@code CANCELLED}, работающие доигрывают
     * и пишут настоящий исход. Сборка у отменённой задачи не запускается, если ещё не началась.
     * Отменённое не перезапускается никогда (docs/adr/0071-no-restart-after-cancel.md).
     *
     * @param task задача
     * @return принята отмена или отвергнута — у терминальной и уже отменённой задачи
     */
    TaskCancelResult cancel(Task<?> task);

    /**
     * Отменяет группу, не трогая остальную задачу: задача доработает и закончится по своду,
     * в котором отменённая группа считается неуспешной.
     *
     * @param batch группа
     * @return принята отмена или отвергнута
     */
    TaskCancelResult cancel(TaskBatch<?> batch);

    /**
     * Отменяет одну подзадачу.
     *
     * @param unit подзадача
     * @return принята отмена или отвергнута
     */
    TaskCancelResult cancel(TaskUnit<?> unit);

    /**
     * Отменяет сущность по снимку из {@link TaskInspection} — для экранов и сопровождения, в том числе
     * сущность осиротевшего типа, у которой доменной формы нет.
     *
     * @param entity снимок задачи, группы или подзадачи
     * @return принята отмена или отвергнута
     */
    TaskCancelResult cancel(TaskSnapshot entity);

    /**
     * Перезапускает задачу: новое раскрытие или, у плоской задачи, обработка заново, с новым циклом
     * попыток (docs/adr/0049-restart-semantics.md). Прежние дети остаются историей. Перезапускается
     * завершённая задача — {@code SUCCEEDED}, {@code WARNING}, {@code FAILED}, {@code SKIPPED};
     * отменённое не перезапускается никогда (docs/adr/0071-no-restart-after-cancel.md).
     *
     * @param task задача
     * @return принят перезапуск или отвергнут с причиной
     */
    TaskRestartResult restart(Task<?> task);

    /**
     * Перезапускает группу: новое раскрытие, затем сборка; предки снова ждут детей. Отвергается, пока
     * задача собирается (docs/adr/0097-no-restart-during-collect.md), если группа принадлежит прежнему
     * раскрытию задачи (docs/adr/0098-restart-only-latest-expansion.md) или результаты детей задачи
     * уже удалены.
     *
     * @param batch группа
     * @return принят перезапуск или отвергнут с причиной
     */
    TaskRestartResult restart(TaskBatch<?> batch);

    /**
     * Перезапускает упавшую подзадачу на месте: заново {@code process}, соседи не трогаются, предки
     * снова ждут детей и соберутся, когда закончится последняя. Отвергается по тем же причинам, что
     * перезапуск группы, и если результаты группы удалены — тогда перезапускают группу или задачу.
     *
     * @param unit подзадача
     * @return принят перезапуск или отвергнут с причиной
     */
    TaskRestartResult restart(TaskUnit<?> unit);

    /**
     * Перезапускает сущность по снимку из {@link TaskInspection} — по правилам её уровня.
     *
     * @param entity снимок задачи, группы или подзадачи
     * @return принят перезапуск или отвергнут с причиной
     */
    TaskRestartResult restart(TaskSnapshot entity);

    /**
     * Удаляет результаты детей у всех собранных родителей (docs/adr/0122-child-results-discard-sweep.md).
     * То же, что {@link #discardChildResults(Duration)} с нулевым сроком.
     *
     * @return сколько родителей отмечено
     */
    int discardChildResults();

    /**
     * Удаляет результаты детей у родителей, чья успешная сборка закончилась не позже срока назад,
     * независимо от {@code taskcomm.results.discard}. В вызове на родителях ставится отметка — с ней
     * перезапуск их подзадач отвергается; сами строки результатов удаляются в фоне движком
     * (docs/adr/0139-restart-api.md). Движок остановлен — строки лежат до удаления партиции.
     *
     * @param olderThan сколько прошло с конца сборки; ноль — все
     * @return сколько родителей отмечено
     */
    int discardChildResults(Duration olderThan);
}
