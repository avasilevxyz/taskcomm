package xyz.avasilev.taskcomm.communication.internal.processor;

/**
 * Итог разбора тела входящей.
 *
 * @param payload доменная форма или {@code null}
 * @param json она же в JSON или {@code null}
 * @param failure причина неразобранного тела или {@code null}
 */
public record DecodedBody(Object payload, String json, String failure) {}
