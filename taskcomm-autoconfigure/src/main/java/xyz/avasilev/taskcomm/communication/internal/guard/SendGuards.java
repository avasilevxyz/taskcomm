package xyz.avasilev.taskcomm.communication.internal.guard;

/**
 * Защита внешней системы на отправке исходящих: ограничение скорости и размыкатель
 * (docs/adr/0137-rate-limit-and-circuit-breaker.md). Оба — в памяти экземпляра.
 *
 * @param rates ограничение скорости
 * @param breakers размыкатели
 */
public record SendGuards(RateLimiters rates, CircuitBreakers breakers) {}
