package xyz.avasilev.taskcomm.communication.internal;

/**
 * Итог разбора тела входящей.
 *
 * @param payload доменная форма или {@code null}
 * @param json она же в JSON или {@code null}
 * @param failure причина неразобранного тела или {@code null}
 */
record DecodedBody(Object payload, String json, String failure) {}
