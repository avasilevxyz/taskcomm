package xyz.avasilev.taskcomm.communication.api;

/**
 * Статус коммуникации (docs/adr/0042-communication-statuses.md, docs/adr/0086-async-response-in-outbound.md).
 * Набор совпадает с {@code CHECK} колонки {@code tc_communication.status}.
 */
public enum CommunicationStatus {

    /** Ждёт захвата: первой попытки, повтора или обработки пришедшего ответа. */
    PENDING,

    /** Захвачена воркером или вызывающим синхронного приёма, попытка идёт. */
    RUNNING,

    /** Отправка успешна, исходящая ждёт асинхронного ответа; аренды и открытой попытки нет. */
    AWAITING_RESPONSE,

    /** Обработана успешно. */
    SUCCEEDED,

    /** Попытки исчерпаны, повтор запрещён или тело не разобралось. */
    FAILED,

    /** Отменена. Отменённое не перезапускается. */
    CANCELLED,

    /** Пропущена: процессор вызвал {@code skip(reason)} или коммуникация оказалась дубликатом. */
    SKIPPED
}
