package xyz.avasilev.taskcomm.communication.api.inspection;

import java.time.Instant;

/**
 * Состояние размыкателя системы <b>на ответившем экземпляре</b> — у каждого своё
 * (docs/adr/0065-communication-circuit-breaker.md).
 *
 * @param instance имя экземпляра
 * @param externalSystem внешняя система
 * @param state {@code CLOSED}, {@code OPEN} или {@code PROBING}
 * @param calls вызовов в окне
 * @param failureRate доля отказов в окне
 * @param nextProbeAt момент следующей пробы у разомкнутого или {@code null}
 */
public record CircuitBreakerSnapshot(
        String instance, String externalSystem, String state, int calls, double failureRate, Instant nextProbeAt) {}
