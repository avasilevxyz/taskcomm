package xyz.avasilev.taskcomm.communication.api.inspection;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import xyz.avasilev.taskcomm.communication.api.ExchangeMessage;

/**
 * Снимок попытки коммуникации с обменом — что ушло и что вернулось (docs/adr/0055-communication-exchange-storage.md,
 * docs/adr/0138-inspection-api.md). У входящей запрос — что прислали нам, ответ — что ответили мы;
 * у исходящей наоборот; у обработки ответа запрос — колбэк.
 */
public interface CommunicationAttemptSnapshot {

    /**
     * Идентификатор попытки.
     *
     * @return идентификатор
     */
    UUID id();

    /**
     * Фаза: {@code PROCESS} или {@code HANDLE_RESPONSE}.
     *
     * @return фаза
     */
    String phase();

    /**
     * Исход: {@code SUCCEEDED}, {@code FAILED}, {@code SKIPPED}, {@code LEASE_LOST}.
     *
     * @return исход или пусто у открытой
     */
    Optional<String> status();

    /**
     * Причина исхода.
     *
     * @return причина или пусто
     */
    Optional<String> statusDescription();

    /**
     * Начало — захват.
     *
     * @return время или пусто у ещё не захваченной
     */
    Optional<Instant> startedAt();

    /**
     * Конец.
     *
     * @return время или пусто у открытой
     */
    Optional<Instant> finishedAt();

    /**
     * Экземпляр, захвативший попытку.
     *
     * @return имя или пусто у не захваченной
     */
    Optional<String> lockedBy();

    /**
     * Запрос как по проводу.
     *
     * @return запрос или пусто — не записан
     */
    Optional<ExchangeMessage> request();

    /**
     * Ответ как по проводу.
     *
     * @return ответ или пусто — не записан
     */
    Optional<ExchangeMessage> response();

    /**
     * Доменная форма колбэка сырым JSON — у попытки обработки ответа.
     *
     * @return JSON или пусто
     */
    Optional<String> callbackPayload();

    /**
     * Доменная форма ответа сырым JSON.
     *
     * @return JSON или пусто
     */
    Optional<String> responsePayload();
}
