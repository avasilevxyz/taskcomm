package xyz.avasilev.taskcomm.communication.api.inspection;

import java.time.Instant;

/**
 * Ключ дедупликации коммуникации (docs/adr/0076-communication-deduplication.md). Пространство ключа —
 * направление и внешняя система самой коммуникации.
 *
 * @param subject предмет у ключа по последней успешной или {@code null} у ключа по всей истории
 * @param key значение ключа
 * @param createdAt время регистрации
 */
public record DedupKeySnapshot(String subject, String key, Instant createdAt) {}
