package xyz.avasilev.taskcomm.communication.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Итог синхронной отправки исходящей (docs/adr/0167-communication-run-mode.md).
 *
 * @param <TRequest> доменная форма запроса
 * @param <TSyncResponse> доменная форма синхронного ответа
 * @param <TAsyncResponse> доменная форма колбэка, {@code Void} у не ждущих
 */
public sealed interface SendAndProcessResult<TRequest, TSyncResponse, TAsyncResponse> {

    /**
     * Обработано: синхронный ответ получен, колбэка не ждали или его не будет.
     *
     * @param communication коммуникация
     * @param response синхронный ответ — что вернул процессор
     * @param <TRequest> доменная форма запроса
     * @param <TSyncResponse> доменная форма синхронного ответа
     * @param <TAsyncResponse> доменная форма колбэка
     */
    record Processed<TRequest, TSyncResponse, TAsyncResponse>(
            Communication.Outbound<TRequest> communication, TSyncResponse response)
            implements SendAndProcessResult<TRequest, TSyncResponse, TAsyncResponse> {

        /**
         * Проверяет коммуникацию.
         *
         * @throws NullPointerException если коммуникации нет
         */
        public Processed {
            Objects.requireNonNull(communication, "communication");
        }
    }

    /**
     * Колбэк пришёл и обработан, пока вызов его ждал.
     *
     * @param communication коммуникация
     * @param response синхронный ответ
     * @param asyncResponse колбэк в доменной форме
     * @param <TRequest> доменная форма запроса
     * @param <TSyncResponse> доменная форма синхронного ответа
     * @param <TAsyncResponse> доменная форма колбэка
     */
    record Responded<TRequest, TSyncResponse, TAsyncResponse>(
            Communication.Outbound<TRequest> communication, TSyncResponse response, TAsyncResponse asyncResponse)
            implements SendAndProcessResult<TRequest, TSyncResponse, TAsyncResponse> {

        /**
         * Проверяет коммуникацию.
         *
         * @throws NullPointerException если коммуникации нет
         */
        public Responded {
            Objects.requireNonNull(communication, "communication");
        }
    }

    /**
     * Отправлено, колбэк не пришёл за предел ожидания. Коммуникация ждёт его дальше, как обычно:
     * пришедший потом колбэк обработает движок в {@code handleResponse}.
     *
     * @param communication коммуникация
     * @param response синхронный ответ
     * @param <TRequest> доменная форма запроса
     * @param <TSyncResponse> доменная форма синхронного ответа
     * @param <TAsyncResponse> доменная форма колбэка
     */
    record Awaiting<TRequest, TSyncResponse, TAsyncResponse>(
            Communication.Outbound<TRequest> communication, TSyncResponse response)
            implements SendAndProcessResult<TRequest, TSyncResponse, TAsyncResponse> {

        /**
         * Проверяет коммуникацию.
         *
         * @throws NullPointerException если коммуникации нет
         */
        public Awaiting {
            Objects.requireNonNull(communication, "communication");
        }
    }

    /**
     * Не получилось: попытки в потоке исчерпаны, повтор запрещён, коммуникация отменена или колбэк
     * не обработан. Коммуникация в конечном статусе.
     *
     * @param communication коммуникация
     * @param reason причина
     * @param <TRequest> доменная форма запроса
     * @param <TSyncResponse> доменная форма синхронного ответа
     * @param <TAsyncResponse> доменная форма колбэка
     */
    record Failed<TRequest, TSyncResponse, TAsyncResponse>(
            Communication.Outbound<TRequest> communication, String reason)
            implements SendAndProcessResult<TRequest, TSyncResponse, TAsyncResponse> {

        /**
         * Проверяет коммуникацию и причину.
         *
         * @throws NullPointerException если чего-то нет
         */
        public Failed {
            Objects.requireNonNull(communication, "communication");
            Objects.requireNonNull(reason, "reason");
        }
    }

    /**
     * Совпал ключ дедупликации: коммуникация создана {@code SKIPPED}, процессор не вызывался.
     *
     * @param communication созданная коммуникация
     * @param duplicateOf коммуникация, которую она повторяет
     * @param <TRequest> доменная форма запроса
     * @param <TSyncResponse> доменная форма синхронного ответа
     * @param <TAsyncResponse> доменная форма колбэка
     */
    record Duplicate<TRequest, TSyncResponse, TAsyncResponse>(
            Communication.Outbound<TRequest> communication, UUID duplicateOf)
            implements SendAndProcessResult<TRequest, TSyncResponse, TAsyncResponse> {

        /**
         * Проверяет коммуникацию и оригинал.
         *
         * @throws NullPointerException если чего-то нет
         */
        public Duplicate {
            Objects.requireNonNull(communication, "communication");
            Objects.requireNonNull(duplicateOf, "duplicateOf");
        }
    }

    /**
     * Система недоступна: размыкатель разомкнут или нет слота ограничителя скорости дольше предела
     * ожидания. Коммуникация не создавалась.
     *
     * @param system внешняя система
     * @param reason причина
     * @param <TRequest> доменная форма запроса
     * @param <TSyncResponse> доменная форма синхронного ответа
     * @param <TAsyncResponse> доменная форма колбэка
     */
    record Unavailable<TRequest, TSyncResponse, TAsyncResponse>(String system, String reason)
            implements SendAndProcessResult<TRequest, TSyncResponse, TAsyncResponse> {

        /**
         * Проверяет систему и причину.
         *
         * @throws NullPointerException если чего-то нет
         */
        public Unavailable {
            Objects.requireNonNull(system, "system");
            Objects.requireNonNull(reason, "reason");
        }
    }
}
