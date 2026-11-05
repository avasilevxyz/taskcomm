package xyz.avasilev.taskcomm;

import java.io.Serial;

/**
 * Нагрузка, переданная сырым JSON, не разбирается в доменную форму процессора — тип из его дженерика
 * (docs/adr/0140-rest-controllers.md). Бросается при создании, до записи чего-либо: ошибку должен
 * получить тот, кто прислал нагрузку, а не воркер через минуту. REST отвечает на неё {@code 400}.
 */
public class UnreadablePayloadException extends TaskcommException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Создаёт исключение.
     *
     * @param message что и во что не разобралось
     * @param cause ошибка разбора
     */
    public UnreadablePayloadException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
