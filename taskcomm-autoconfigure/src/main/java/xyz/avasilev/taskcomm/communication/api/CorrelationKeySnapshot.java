package xyz.avasilev.taskcomm.communication.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Ключ корреляции дела — для выдачи дела целиком (docs/adr/0040-communication-correlation.md).
 *
 * @param externalSystem система, от которой ждут колбэк
 * @param correlationId значение ключа
 * @param communicationId коммуникация-якорь
 * @param createdAt время регистрации
 */
public record CorrelationKeySnapshot(
        String externalSystem, String correlationId, UUID communicationId, Instant createdAt) {}
