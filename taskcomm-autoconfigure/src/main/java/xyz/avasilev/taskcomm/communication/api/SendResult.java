package xyz.avasilev.taskcomm.communication.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Чем кончилось создание исходящей. Коммуникация создаётся в обоих исходах: дубликат не отбрасывается,
 * а сохраняется сразу {@code SKIPPED} — видно, сколько раз пытались отправить
 * (docs/adr/0076-communication-deduplication.md).
 *
 * @param <C> доменная форма запроса
 */
public sealed interface SendResult<C> {

    /**
     * Созданная коммуникация.
     *
     * @return исходящая
     */
    Communication.Outbound<C> communication();

    /**
     * Создана и ждёт отправки.
     *
     * @param communication исходящая в статусе {@code PENDING}
     * @param <C> доменная форма запроса
     */
    record Created<C>(Communication.Outbound<C> communication) implements SendResult<C> {

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
     * @param <C> доменная форма запроса
     */
    record Duplicate<C>(Communication.Outbound<C> communication, UUID duplicateOf) implements SendResult<C> {

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
