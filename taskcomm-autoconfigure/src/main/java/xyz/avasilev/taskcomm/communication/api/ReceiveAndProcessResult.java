package xyz.avasilev.taskcomm.communication.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Чем кончился синхронный приём входящей — {@link CommunicationService#receiveAndProcess}
 * (docs/adr/0133-synchronous-inbound-reception.md). Коммуникация создаётся при любом исходе;
 * разбирайте итог {@code switch} без ветки {@code default}.
 *
 * <pre>{@code
 * return switch (communicationService.receiveAndProcess(OrderProcessor.class, request)) {
 *     case ReceiveAndProcessResult.Processed<Order, Ack> processed -> ResponseEntity.ok(processed.response());
 *     case ReceiveAndProcessResult.Duplicate<Order, Ack> _ -> ResponseEntity.ok().build();
 *     case ReceiveAndProcessResult.Unparseable<Order, Ack> _ -> ResponseEntity.badRequest().build();
 *     case ReceiveAndProcessResult.Failed<Order, Ack> _ -> ResponseEntity.internalServerError().build();
 * };
 * }</pre>
 *
 * @param <TRequest> доменная форма запроса
 * @param <TSyncResponse> доменная форма ответа отправителю
 */
public sealed interface ReceiveAndProcessResult<TRequest, TSyncResponse> {

    /**
     * Созданная коммуникация.
     *
     * @return входящая
     */
    Communication.Inbound<TRequest> communication();

    /**
     * Процессор отработал успешно. Ответ записан в {@code response_*} попытки — отдайте его
     * отправителю как есть.
     *
     * @param communication входящая в статусе {@code SUCCEEDED} или {@code SKIPPED}, если процессор
     *     объявил пропуск
     * @param response что вернул процессор; {@code null}, если он вернул {@code null}
     * @param <TRequest> доменная форма запроса
     * @param <TSyncResponse> доменная форма ответа отправителю
     */
    record Processed<TRequest, TSyncResponse>(Communication.Inbound<TRequest> communication, TSyncResponse response)
            implements ReceiveAndProcessResult<TRequest, TSyncResponse> {

        /**
         * Проверяет коммуникацию.
         *
         * @param communication входящая
         * @param response ответ
         * @throws NullPointerException если коммуникация пуста
         */
        public Processed {
            Objects.requireNonNull(communication, "communication");
        }
    }

    /**
     * Обработка неуспешна — например, процессор бросил исключение: попытка и коммуникация
     * {@code FAILED} <b>без повтора</b> — отвечать отправителю повтором уже некому. Отвечайте
     * ошибкой, например {@code 500}.
     *
     * @param communication входящая в статусе {@code FAILED}
     * @param reason причина неуспеха
     * @param <TRequest> доменная форма запроса
     * @param <TSyncResponse> доменная форма ответа отправителю
     */
    record Failed<TRequest, TSyncResponse>(Communication.Inbound<TRequest> communication, String reason)
            implements ReceiveAndProcessResult<TRequest, TSyncResponse> {

        /**
         * Проверяет коммуникацию и причину.
         *
         * @param communication входящая
         * @param reason причина
         * @throws NullPointerException если что-то пусто
         */
        public Failed {
            Objects.requireNonNull(communication, "communication");
            Objects.requireNonNull(reason, "reason");
        }
    }

    /**
     * Дубликат по ключу из значения-запроса: создана сразу {@code SKIPPED}, процессор не вызывался.
     *
     * @param communication входящая в статусе {@code SKIPPED}
     * @param duplicateOf коммуникация, с ключом которой она совпала
     * @param <TRequest> доменная форма запроса
     * @param <TSyncResponse> доменная форма ответа отправителю
     */
    record Duplicate<TRequest, TSyncResponse>(Communication.Inbound<TRequest> communication, UUID duplicateOf)
            implements ReceiveAndProcessResult<TRequest, TSyncResponse> {

        /**
         * Проверяет коммуникации.
         *
         * @param communication входящая
         * @param duplicateOf совпавшая
         * @throws NullPointerException если что-то пусто
         */
        public Duplicate {
            Objects.requireNonNull(communication, "communication");
            Objects.requireNonNull(duplicateOf, "duplicateOf");
        }
    }

    /**
     * Тело не разобралось: создана сразу {@code FAILED}, процессор не вызывался.
     *
     * @param communication входящая в статусе {@code FAILED}, без доменной формы запроса
     * @param reason причина разбора
     * @param <TRequest> доменная форма запроса
     * @param <TSyncResponse> доменная форма ответа отправителю
     */
    record Unparseable<TRequest, TSyncResponse>(Communication.Inbound<TRequest> communication, String reason)
            implements ReceiveAndProcessResult<TRequest, TSyncResponse> {

        /**
         * Проверяет коммуникацию и причину.
         *
         * @param communication входящая
         * @param reason причина
         * @throws NullPointerException если что-то пусто
         */
        public Unparseable {
            Objects.requireNonNull(communication, "communication");
            Objects.requireNonNull(reason, "reason");
        }
    }
}
