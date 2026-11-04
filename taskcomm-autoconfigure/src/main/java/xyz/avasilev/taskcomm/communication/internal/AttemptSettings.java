package xyz.avasilev.taskcomm.communication.internal;

import java.util.Objects;

/**
 * Итоговые настройки попыток одной фазы одной внешней системы: повторы и аренда.
 *
 * @param retry политика повторов
 * @param lease аренда
 */
public record AttemptSettings(RetryPolicy retry, LeaseSettings lease) {

    /**
     * Проверяет, что обе части заданы.
     *
     * @throws NullPointerException если часть пустая
     */
    public AttemptSettings {
        Objects.requireNonNull(retry, "retry");
        Objects.requireNonNull(lease, "lease");
    }
}
