package xyz.avasilev.taskcomm.communication.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Чем кончился асинхронный приём входящей — {@link CommunicationService#receive}. Коммуникация
 * создаётся при любом исходе; обрабатывать её будет движок. Ответ отправителю формирует вызывающий
 * сам: {@code response_*} у асинхронного приёма пуст (docs/adr/0133-synchronous-inbound-reception.md).
 *
 * @param <TRequest> доменная форма запроса
 */
public sealed interface ReceiveResult<TRequest> {

    /**
     * Созданная коммуникация.
     *
     * @return входящая
     */
    Communication.Inbound<TRequest> communication();

    /**
     * Принята: создана в транзакции вызывающего и будет обработана движком после её коммита.
     *
     * @param communication входящая в статусе {@code PENDING}
     * @param <TRequest> доменная форма запроса
     */
    record Accepted<TRequest>(Communication.Inbound<TRequest> communication) implements ReceiveResult<TRequest> {

        /**
         * Проверяет коммуникацию.
         *
         * @param communication входящая
         * @throws NullPointerException если коммуникация пуста
         */
        public Accepted {
            Objects.requireNonNull(communication, "communication");
        }
    }

    /**
     * Дубликат по ключу из значения-запроса: создана сразу {@code SKIPPED}.
     *
     * @param communication входящая в статусе {@code SKIPPED}
     * @param duplicateOf коммуникация, с ключом которой она совпала, — на неё можно сослаться в ответе
     * @param <TRequest> доменная форма запроса
     */
    record Duplicate<TRequest>(Communication.Inbound<TRequest> communication, UUID duplicateOf)
            implements ReceiveResult<TRequest> {

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
     * Тело не разобралось: создана сразу {@code FAILED}, без повторов — текст не станет валидным
     * сам собой. Ошибку отправителю отвечает вызывающий.
     *
     * @param communication входящая в статусе {@code FAILED}, без доменной формы запроса
     * @param reason причина разбора
     * @param <TRequest> доменная форма запроса
     */
    record Unparseable<TRequest>(Communication.Inbound<TRequest> communication, String reason)
            implements ReceiveResult<TRequest> {

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
