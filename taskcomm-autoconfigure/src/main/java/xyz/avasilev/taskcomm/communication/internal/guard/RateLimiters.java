package xyz.avasilev.taskcomm.communication.internal.guard;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import xyz.avasilev.taskcomm.communication.internal.settings.CommunicationSettings;

/**
 * Ограничение скорости отправки исходящих — <b>на экземпляр</b> (docs/adr/0011-communication-model.md,
 * docs/adr/0137-rate-limit-and-circuit-breaker.md): лимит в вызовах в секунду, token bucket в памяти,
 * запас — не больше одной секунды. Воркер ждёт разрешения между захватом и отправкой; ожидание идёт
 * под арендой, heartbeat её продлевает, и время ожидания входит в предел обработки.
 */
public class RateLimiters {

    /** Как часто ожидающий проверяет сигнал прекращения. */
    private static final long CHECK_NANOS = TimeUnit.MILLISECONDS.toNanos(100);

    private final CommunicationSettings settings;

    private final LongSupplier nanoClock;

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimiters(final CommunicationSettings settings, final LongSupplier nanoClock) {
        this.settings = settings;
        this.nanoClock = nanoClock;
    }

    /**
     * Ждёт разрешения на одну отправку в систему.
     *
     * @param system внешняя система
     * @param stop сигнал прекращения — потеря аренды или отмена
     * @return {@code true}, если разрешение получено; {@code false} — ожидание прервано сигналом
     * @throws InterruptedException если поток прерван
     */
    public boolean acquire(final String system, final BooleanSupplier stop) throws InterruptedException {
        final Double rate = this.settings.rateLimit(system);
        if (rate == null) {
            return true;
        }
        final Bucket bucket = this.buckets.computeIfAbsent(system, key -> new Bucket(rate, this.nanoClock.getAsLong()));
        long wait = bucket.take(this.nanoClock.getAsLong());
        while (wait > 0) {
            if (stop.getAsBoolean()) {
                return false;
            }
            TimeUnit.NANOSECONDS.sleep(Math.min(wait, CHECK_NANOS));
            wait = bucket.take(this.nanoClock.getAsLong());
        }
        return true;
    }

    /** Ведро разрешений одной системы. */
    private static final class Bucket {

        private final double perNano;

        private final double capacity;

        private double tokens;

        private long refilledAt;

        Bucket(final double perSecond, final long now) {
            this.perNano = perSecond / TimeUnit.SECONDS.toNanos(1);
            this.capacity = Math.max(1, perSecond);
            this.tokens = 1;
            this.refilledAt = now;
        }

        /**
         * Берёт разрешение, если есть.
         *
         * @param now монотонное время
         * @return {@code 0}, если взято; иначе сколько наносекунд ждать до следующего
         */
        synchronized long take(final long now) {
            this.tokens = Math.min(this.capacity, this.tokens + (now - this.refilledAt) * this.perNano);
            this.refilledAt = now;
            if (this.tokens >= 1) {
                this.tokens -= 1;
                return 0;
            }
            return Math.max(1, (long) Math.ceil((1 - this.tokens) / this.perNano));
        }
    }
}
