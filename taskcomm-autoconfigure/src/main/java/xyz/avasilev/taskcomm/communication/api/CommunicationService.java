package xyz.avasilev.taskcomm.communication.api;

import java.util.List;
import xyz.avasilev.taskcomm.communication.api.inspection.CommunicationInspection;
import xyz.avasilev.taskcomm.communication.api.inspection.CommunicationSnapshot;
import xyz.avasilev.taskcomm.communication.api.processor.CommunicationProcessor;
import xyz.avasilev.taskcomm.communication.api.processor.CommunicationProcessorSettings;
import xyz.avasilev.taskcomm.execution.api.ExecutionRef;

/**
 * Создание исходящих, приём входящих и колбэков, чтение коммуникаций дела.
 *
 * <p><b>Создание пишется в текущую транзакцию потребителя</b> — исходящих, асинхронно принятых
 * входящих и колбэков: коммуникация становится видимой воркеру в момент коммита бизнес-данных,
 * откат убирает и её. Вне транзакции стартер открывает свою короткую
 * (docs/adr/0094-creation-always-in-transaction.md). Синхронный приём устроен иначе — см.
 * {@link #receiveAndProcess(String, InboundRequest)}.
 *
 * <p><b>Дело</b>: созданное из кода потребителя заводит новое дело, созданное внутри процессора —
 * попадает в дело обрабатываемой сущности. В другом потоке передайте ссылку на дело явно
 * (docs/adr/0056-execution-ref.md). Входящая с известным ключом корреляции попадает в дело якоря.
 *
 * <p>Система без процессора — исключение, наследник {@code TaskcommException}, до записи чего-либо
 * (docs/adr/0105-orphan-communications.md). Ключ дедупликации при создании в транзакции строже
 * {@code READ COMMITTED} — тоже исключение до записи (docs/adr/0095-dedup-requires-read-committed.md).
 */
public interface CommunicationService {

    /**
     * Создаёт исходящую.
     *
     * @param destination внешняя система-получатель
     * @param payload доменная форма запроса
     * @param <C> доменная форма запроса
     * @return созданная коммуникация или дубликат
     */
    <C> SendResult<C> send(String destination, C payload);

    /**
     * Создаёт исходящую в указанном деле — из любого потока.
     *
     * @param destination внешняя система-получатель
     * @param payload доменная форма запроса
     * @param execution дело; побеждает неявную привязку потока
     * @param <C> доменная форма запроса
     * @return созданная коммуникация или дубликат
     */
    <C> SendResult<C> send(String destination, C payload, ExecutionRef execution);

    /**
     * Создаёт исходящую с необязательными параметрами.
     *
     * @param destination внешняя система-получатель
     * @param payload доменная форма запроса
     * @param options дело, «не раньше», приоритет, ключи дедупликации и корреляции
     * @param <C> доменная форма запроса
     * @return созданная коммуникация или дубликат
     */
    <C> SendResult<C> send(String destination, C payload, CommunicationSendOptions options);

    /**
     * Создаёт исходящую процессору, указанному классом: компилятор сверяет тип запроса с дженериком
     * процессора, система берётся из реестра процессоров.
     *
     * @param processor класс процессора — бина в контексте
     * @param payload доменная форма запроса
     * @param <C> доменная форма запроса
     * @return созданная коммуникация или дубликат
     * @throws IllegalArgumentException если такого процессора нет в реестре
     */
    <C> SendResult<C> send(Class<? extends CommunicationProcessor<C, ?, ?>> processor, C payload);

    /**
     * Создаёт исходящую процессору, указанному классом, с необязательными параметрами.
     *
     * @param processor класс процессора — бина в контексте
     * @param payload доменная форма запроса
     * @param options дело, «не раньше», приоритет, ключи дедупликации и корреляции
     * @param <C> доменная форма запроса
     * @return созданная коммуникация или дубликат
     * @throws IllegalArgumentException если такого процессора нет в реестре
     */
    <C> SendResult<C> send(
            Class<? extends CommunicationProcessor<C, ?, ?>> processor, C payload, CommunicationSendOptions options);

    /**
     * Отправляет исходящую по нагрузке сырым JSON — для REST и для кода, у которого доменной формы нет
     * (docs/adr/0140-rest-controllers.md). JSON разбирается в тип запроса из дженерика процессора сразу,
     * до записи.
     *
     * @param destination внешняя система-получатель
     * @param payloadJson запрос в JSON
     * @param options опции отправки
     * @return созданная коммуникация или дубликат
     * @throws IllegalArgumentException если процессора такой системы нет
     * @throws xyz.avasilev.taskcomm.UnreadablePayloadException если JSON не разбирается в запрос процессора
     */
    SendResult<?> sendJson(String destination, String payloadJson, CommunicationSendOptions options);

    /**
     * Асинхронный приём входящей: тело разбирается здесь, в потоке вызывающего, кодеком транспортного
     * контракта процессора; коммуникация создаётся в транзакции вызывающего, обрабатывает её движок
     * с повторами. Ответ отправителю — забота вызывающего: {@code response_*} остаётся пустым.
     *
     * @param source внешняя система-отправитель
     * @param request что прислали
     * @return принята, дубликат или неразбираемое тело
     */
    ReceiveResult<?> receive(String source, InboundRequest request);

    /**
     * Асинхронный приём входящей процессором, указанным классом.
     *
     * @param processor класс процессора — бина в контексте
     * @param request что прислали
     * @param <C> доменная форма запроса
     * @return принята, дубликат или неразбираемое тело
     * @throws IllegalArgumentException если такого процессора нет в реестре
     */
    <C> ReceiveResult<C> receive(Class<? extends CommunicationProcessor<C, ?, ?>> processor, InboundRequest request);

    /**
     * Синхронный приём входящей: обработка здесь же, в потоке вызывающего, <b>одной попыткой</b>
     * (docs/adr/0133-synchronous-inbound-reception.md). Коммуникация создаётся своей короткой
     * транзакцией сразу с попыткой под арендой этого экземпляра, процессор вызывается под heartbeat,
     * его ответ пишется в {@code response_*} и возвращается — отдайте его отправителю как есть.
     * Неуспех — коммуникация {@code FAILED} без повтора.
     *
     * <p><b>Вызывайте вне своей транзакции.</b> Внутри активной транзакции — исключение, наследник
     * {@code TaskcommException}, до записи чего-либо: атомарности с бизнес-данными у синхронного
     * приёма нет и быть не может.
     *
     * <p><b>Задайте системе предел в одну попытку</b> — {@code maxAttempts = 1} в
     * {@link CommunicationProcessorSettings} или {@code max-attempts: 1} в {@code yaml}. Если поток
     * вызывающего умрёт или зависнет дольше предела обработки, переподхват закроет попытку, и дальше
     * коммуникация пойдёт по общим правилам повторов — в фоне, хотя отвечать уже некому. Стартер это
     * не проверяет. Ключ дедупликации берите по последней успешной: ключ по всей истории совпадёт
     * с упавшей доставкой, и повторная доставка не обработается.
     *
     * @param source внешняя система-отправитель
     * @param request что прислали
     * @return обработана, неуспех, дубликат или неразбираемое тело
     */
    ReceiveAndProcessResult<?, ?> receiveAndProcess(String source, InboundRequest request);

    /**
     * Синхронный приём входящей процессором, указанным классом: компилятор выводит тип ответа
     * отправителю из дженерика процессора. Правила — как у
     * {@link #receiveAndProcess(String, InboundRequest)}.
     *
     * @param processor класс процессора — бина в контексте
     * @param request что прислали
     * @param <C> доменная форма запроса
     * @param <R> доменная форма ответа отправителю
     * @return обработана, неуспех, дубликат или неразбираемое тело
     * @throws IllegalArgumentException если такого процессора нет в реестре
     */
    <C, R> ReceiveAndProcessResult<C, R> receiveAndProcess(
            Class<? extends CommunicationProcessor<C, R, ?>> processor, InboundRequest request);

    /**
     * Приём колбэка — асинхронного ответа на исходящую (docs/adr/0086-async-response-in-outbound.md).
     * Лукап по паре «система-отправитель, ключ корреляции», разбор тела — в потоке вызывающего
     * кодеком процессора системы {@code source}, запись — в транзакции вызывающего.
     *
     * @param source внешняя система — отправитель колбэка
     * @param request колбэк
     * @return куда записан колбэк
     */
    ReceiveResponseResult receiveResponse(String source, CallbackRequest request);

    /**
     * Коммуникации дела с этой внешней системой, в обоих направлениях, в порядке создания
     * (docs/adr/0110-execution-composition-reads-in-subsystems.md). Чтение идёт в партиции дела.
     * Из {@code process} подзадачи не вызывайте: миллион подзадач дадут миллион запросов.
     *
     * @param execution дело
     * @param externalSystem внешняя система
     * @return коммуникации дела; запрос восстановлен по дженерику процессора
     */
    List<Communication<?>> findInExecution(ExecutionRef execution, String externalSystem);

    /**
     * Коммуникации дела с системой процессора, указанного классом, в порядке создания.
     *
     * @param execution дело
     * @param processor класс процессора — бина в контексте
     * @param <C> доменная форма запроса
     * @return коммуникации дела
     * @throws IllegalArgumentException если такого процессора нет в реестре
     */
    <C> List<Communication<C>> findInExecution(
            ExecutionRef execution, Class<? extends CommunicationProcessor<C, ?, ?>> processor);

    /**
     * Отменяет коммуникацию (docs/adr/0139-restart-api.md): ждущая захвата или ответа становится
     * {@code CANCELLED} сразу, отправляющаяся или обрабатываемая доигрывает попытку и пишет настоящий
     * исход; отправка, после которой ждут ответа, — {@code CANCELLED}. Отменённое не перезапускается
     * никогда (docs/adr/0071-no-restart-after-cancel.md).
     *
     * @param communication коммуникация
     * @return принята отмена или отвергнута — у терминальной и уже отменённой
     */
    CommunicationCancelResult cancel(Communication<?> communication);

    /**
     * Отменяет коммуникацию по снимку из {@link CommunicationInspection} — в том числе коммуникацию
     * системы без процессора.
     *
     * @param communication снимок
     * @return принята отмена или отвергнута
     */
    CommunicationCancelResult cancel(CommunicationSnapshot communication);

    /**
     * Перезапускает упавшую коммуникацию: заново отправка исходящей или обработка входящей, с новым циклом
     * попыток; ключи дедупликации снова не завершены. Исходящая, у которой упала обработка ответа, тоже
     * отправляется заново. Входящая, принятая синхронно, обрабатывается в фоне — ответить отправителю
     * уже некому. Отвергается у отменённой, не упавшей и у входящей с неразобранным телом.
     *
     * @param communication коммуникация
     * @return принят перезапуск или отвергнут с причиной
     */
    CommunicationRestartResult restart(Communication<?> communication);

    /**
     * Перезапускает коммуникацию по снимку из {@link CommunicationInspection}.
     *
     * @param communication снимок
     * @return принят перезапуск или отвергнут с причиной
     */
    CommunicationRestartResult restart(CommunicationSnapshot communication);
}
