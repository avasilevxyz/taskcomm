package xyz.avasilev.taskcomm.communication.api;

/**
 * Строка сводки: сколько коммуникаций направления и системы в статусе (docs/adr/0138-inspection-api.md).
 *
 * @param direction направление
 * @param externalSystem внешняя система
 * @param status статус
 * @param count число коммуникаций
 * @param orphan у системы нет процессора
 */
public record CommunicationSummary(
        CommunicationSnapshot.Direction direction,
        String externalSystem,
        CommunicationStatus status,
        long count,
        boolean orphan) {}
