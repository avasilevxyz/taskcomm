package xyz.avasilev.taskcomm.communication.api.inspection;

import java.time.Instant;
import java.util.UUID;

/**
 * Ключ дедупликации, зарегистрированный за коммуникацией дела, — для экрана дела
 * (docs/adr/0172-execution-dedup-keys.md). Пространство ключа — направление и внешняя система.
 *
 * @param direction направление коммуникации
 * @param externalSystem внешняя система: {@code source} у входящей, {@code destination} у исходящей
 * @param subject предмет у ключа по последней успешной или {@code null} у ключа по всей истории
 * @param key значение ключа
 * @param communicationId коммуникация, которой принадлежит ключ
 * @param createdAt время регистрации
 */
public record DedupKeyEntry(
        CommunicationSnapshot.Direction direction,
        String externalSystem,
        String subject,
        String key,
        UUID communicationId,
        Instant createdAt) {}
