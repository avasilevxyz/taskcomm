package xyz.avasilev.taskcomm.communication.internal;

import java.time.Instant;
import java.util.UUID;

/**
 * Ключ строки коммуникации — с ключом партиционирования, чтобы каждый запрос отсекал партиции.
 *
 * @param id идентификатор коммуникации
 * @param executionCreatedAt ключ партиционирования — время создания дела
 */
public record CommunicationRef(UUID id, Instant executionCreatedAt) {}
