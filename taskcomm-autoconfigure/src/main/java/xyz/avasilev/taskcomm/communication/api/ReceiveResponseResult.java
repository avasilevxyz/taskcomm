package xyz.avasilev.taskcomm.communication.api;

import java.util.Objects;
import xyz.avasilev.taskcomm.communication.api.processor.CommunicationProcessor;

/**
 * Чем кончился приём колбэка — {@link CommunicationService#receiveResponse}
 * (docs/adr/0086-async-response-in-outbound.md). Колбэк сохраняется при любом исходе.
 */
public sealed interface ReceiveResponseResult {

    /**
     * Коммуникация, в которую записан колбэк.
     *
     * @return исходящая, в которую он дописан, или созданная для него входящая
     */
    Communication<?> communication();

    /**
     * Колбэк дописан в исходящую, которая его ждёт или ещё отправляется; обработает его
     * {@link CommunicationProcessor#handleResponse} под арендой движка.
     *
     * @param communication исходящая
     */
    record Attached(Communication.Outbound<?> communication) implements ReceiveResponseResult {

        /**
         * Проверяет коммуникацию.
         *
         * @param communication исходящая
         * @throws NullPointerException если коммуникация пуста
         */
        public Attached {
            Objects.requireNonNull(communication, "communication");
        }
    }

    /**
     * Ключ зарегистрирован под этой системой явно, а внешняя сторона якоря другая: колбэк стал
     * отдельной входящей в деле якоря и обрабатывается её {@code process}
     * (docs/adr/0075-correlation-key-scoped-by-system.md).
     *
     * @param communication созданная входящая
     */
    record Separate(Communication.Inbound<?> communication) implements ReceiveResponseResult {

        /**
         * Проверяет коммуникацию.
         *
         * @param communication входящая
         * @throws NullPointerException если коммуникация пуста
         */
        public Separate {
            Objects.requireNonNull(communication, "communication");
        }
    }

    /**
     * Ждущей коммуникации нет: найденная уже терминальна, ответ принят или отменена — или ключ
     * не найден вовсе. Колбэк записан входящей сразу {@code FAILED} «ожидающий запрос по ключу
     * не найден»; обрабатывать его некому.
     *
     * @param communication входящая в статусе {@code FAILED}
     */
    record Unmatched(Communication.Inbound<?> communication) implements ReceiveResponseResult {

        /**
         * Проверяет коммуникацию.
         *
         * @param communication входящая
         * @throws NullPointerException если коммуникация пуста
         */
        public Unmatched {
            Objects.requireNonNull(communication, "communication");
        }
    }

    /**
     * Тело колбэка не разобралось.
     *
     * @param communication коммуникация, в которой записан колбэк
     * @param reason причина разбора
     */
    record Unparseable(Communication<?> communication, String reason) implements ReceiveResponseResult {

        /**
         * Проверяет коммуникацию и причину.
         *
         * @param communication коммуникация
         * @param reason причина
         * @throws NullPointerException если что-то пусто
         */
        public Unparseable {
            Objects.requireNonNull(communication, "communication");
            Objects.requireNonNull(reason, "reason");
        }
    }
}
