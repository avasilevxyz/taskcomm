package xyz.avasilev.taskcomm.communication.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Чем кончилось создание исходящей. Коммуникация создаётся в обоих исходах: дубликат не отбрасывается,
 * а сохраняется сразу {@code SKIPPED} — видно, сколько раз пытались отправить
 * (docs/adr/0076-communication-deduplication.md).
 *
 * @param <TRequest> доменная форма запроса
 */
public sealed interface SendResult<TRequest> {

    /**
     * Созданная коммуникация.
     *
     * @return исходящая
     */
    Communication.Outbound<TRequest> communication();

    /**
     * Создана и ждёт отправки.
     *
     * @param communication исходящая в статусе {@code PENDING}
     * @param <TRequest> доменная форма запроса
     */
    record Created<TRequest>(Communication.Outbound<TRequest> communication) implements SendResult<TRequest> {

        /**
         * Проверяет коммуникацию.
         *
         * @param communication исходящая
         * @throws NullPointerException если коммуникация пуста
         */
        public Created {
            Objects.requireNonNull(communication, "communication");
        }
    }

    /**
     * Дубликат по ключу, заданному при создании: создана сразу {@code SKIPPED}, отправки не будет.
     *
     * @param communication исходящая в статусе {@code SKIPPED}
     * @param duplicateOf коммуникация, с ключом которой она совпала
     * @param <TRequest> доменная форма запроса
     */
    record Duplicate<TRequest>(Communication.Outbound<TRequest> communication, UUID duplicateOf)
            implements SendResult<TRequest> {

        /**
         * Проверяет коммуникации.
         *
         * @param communication исходящая
         * @param duplicateOf совпавшая
         * @throws NullPointerException если что-то пусто
         */
        public Duplicate {
            Objects.requireNonNull(communication, "communication");
            Objects.requireNonNull(duplicateOf, "duplicateOf");
        }
    }
}
