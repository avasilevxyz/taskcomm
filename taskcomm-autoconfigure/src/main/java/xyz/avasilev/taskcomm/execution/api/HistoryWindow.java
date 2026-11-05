package xyz.avasilev.taskcomm.execution.api;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Окно чтения истории — диапазон по <b>времени создания дела</b>, а не сущности: так устроен ключ
 * партиционирования, и окно отсекает партиции (docs/adr/0101-active-partition-horizon.md). Сущность,
 * созданная сегодня внутри дела годовой давности, лежит в партиции годовой давности.
 *
 * <p>Режим «искать везде» снимает окно и обходит все партиции. Выдача называет окно, в котором искали:
 * «не найдено» иначе неотличимо от «не найдено в окне».
 */
public final class HistoryWindow {

    private static final HistoryWindow EVERYWHERE = new HistoryWindow(null, null);

    private final Instant from;

    private final Instant to;

    private HistoryWindow(final Instant from, final Instant to) {
        this.from = from;
        this.to = to;
    }

    /**
     * Дела, созданные в полуоткрытом диапазоне {@code [from, to)}.
     *
     * @param from начало, включительно
     * @param to конец, не включительно
     * @return окно
     * @throws IllegalArgumentException если конец не позже начала
     */
    public static HistoryWindow between(final Instant from, final Instant to) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (!to.isAfter(from)) {
            throw new IllegalArgumentException("Окно истории: конец " + to + " не позже начала " + from);
        }
        return new HistoryWindow(from, to);
    }

    /**
     * Без окна — все партиции. Операция разбора, а не горячий путь.
     *
     * @return окно «искать везде»
     */
    public static HistoryWindow everywhere() {
        return EVERYWHERE;
    }

    /**
     * Начало окна.
     *
     * @return начало или пусто у «искать везде»
     */
    public Optional<Instant> from() {
        return Optional.ofNullable(this.from);
    }

    /**
     * Конец окна, не включительно.
     *
     * @return конец или пусто у «искать везде»
     */
    public Optional<Instant> to() {
        return Optional.ofNullable(this.to);
    }

    /**
     * Ищем ли везде.
     *
     * @return {@code true}, если окна нет
     */
    public boolean isEverywhere() {
        return this.from == null;
    }

    @Override
    public boolean equals(final Object other) {
        return other instanceof final HistoryWindow window
                && Objects.equals(this.from, window.from)
                && Objects.equals(this.to, window.to);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.from, this.to);
    }

    @Override
    public String toString() {
        return this.isEverywhere() ? "везде" : "[" + this.from + ", " + this.to + ")";
    }
}
