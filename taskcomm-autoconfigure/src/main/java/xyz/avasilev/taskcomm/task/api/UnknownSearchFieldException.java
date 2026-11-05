package xyz.avasilev.taskcomm.task.api;

import java.util.List;
import java.util.Set;
import xyz.avasilev.taskcomm.TaskcommException;

/**
 * Поиск по полю, которое не объявил ни один процессор этого уровня, или по сочетанию полей, которое
 * не объявил целиком ни один тип (docs/adr/0037-search-field-registry.md). Через REST — {@code 400}.
 */
public class UnknownSearchFieldException extends TaskcommException {

    private static final long serialVersionUID = 1L;

    /** Известные имена полей уровня. */
    private final List<String> knownFields;

    /**
     * Собирает исключение.
     *
     * @param message сообщение
     * @param knownFields известные имена полей уровня
     */
    public UnknownSearchFieldException(final String message, final Set<String> knownFields) {
        super(message + " Известные поля: " + knownFields.stream().sorted().toList() + ".");
        this.knownFields = knownFields.stream().sorted().toList();
    }

    /**
     * Известные имена полей уровня.
     *
     * @return имена по алфавиту
     */
    public List<String> knownFields() {
        return this.knownFields;
    }
}
