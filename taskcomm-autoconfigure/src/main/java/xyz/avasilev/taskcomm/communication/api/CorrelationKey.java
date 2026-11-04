package xyz.avasilev.taskcomm.communication.api;

import java.util.Objects;
import java.util.Optional;

/**
 * Ключ корреляции — по нему колбэк находит дорогу к коммуникации-якорю
 * (docs/adr/0040-communication-correlation.md, docs/adr/0075-correlation-key-scoped-by-system.md).
 * Ключ уникален в пределах внешней системы, от которой ждут колбэк. Регистрация накапливает:
 * новый ключ прежние не снимает.
 *
 * <p><b>Регистрация на исходящей включает ожидание ответа</b>, если система ключа — её
 * {@code destination} (docs/adr/0104-wait-defined-by-correlation-key.md).
 */
public final class CorrelationKey {

    private final String externalSystem;
    private final String correlationId;

    private CorrelationKey(final String externalSystem, final String correlationId) {
        this.externalSystem = externalSystem;
        this.correlationId = Objects.requireNonNull(correlationId, "correlationId");
    }

    /**
     * Ключ от внешней стороны самой коммуникации: {@code destination} у исходящей, {@code source}
     * у входящей.
     *
     * @param correlationId значение ключа
     * @return ключ
     */
    public static CorrelationKey of(final String correlationId) {
        return new CorrelationKey(null, correlationId);
    }

    /**
     * Ключ от явно названной системы. Колбэк от другой системы, чем внешняя сторона якоря, в якорь
     * не дописывается, а становится отдельной входящей в его деле.
     *
     * @param externalSystem система, от которой ждут колбэк
     * @param correlationId значение ключа
     * @return ключ
     */
    public static CorrelationKey of(final String externalSystem, final String correlationId) {
        return new CorrelationKey(Objects.requireNonNull(externalSystem, "externalSystem"), correlationId);
    }

    /**
     * Значение ключа.
     *
     * @return значение
     */
    public String correlationId() {
        return this.correlationId;
    }

    /**
     * Явно названная система.
     *
     * @return система или пусто — тогда внешняя сторона самой коммуникации
     */
    public Optional<String> externalSystem() {
        return Optional.ofNullable(this.externalSystem);
    }
}
