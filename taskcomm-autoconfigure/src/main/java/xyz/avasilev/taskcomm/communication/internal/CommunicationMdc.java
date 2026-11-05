package xyz.avasilev.taskcomm.communication.internal;

import java.util.Map;
import org.slf4j.MDC;

/**
 * MDC вокруг попытки коммуникации — единственное место подсистемы, где стартер его трогает
 * (docs/rules/observability.md, docs/adr/0034-mdc-business-id.md). Своя реализация — общего кода
 * с задачами нет (docs/adr/0010-separate-engines.md).
 *
 * <p>Ключи — с префиксом {@code taskcomm.logging.mdc-prefix}: дело, коммуникация и номер попытки.
 * Снимается <b>восстановлением снимка</b>: всё, что положил процессор, уходит вместе с ключами стартера.
 * У синхронного приёма это MDC вызывающего потока — он возвращается к нему таким, каким был.
 */
final class CommunicationMdc {

    private final String prefix;

    /**
     * Собирает MDC попыток.
     *
     * @param prefix префикс ключей
     */
    CommunicationMdc(final String prefix) {
        this.prefix = prefix;
    }

    /**
     * Кладёт ключи попытки поверх текущего MDC.
     *
     * @param attempt захваченная работа
     * @return область, закрытие которой восстанавливает прежний MDC целиком
     */
    Scope open(final ClaimedCommunication attempt) {
        final Map<String, String> previous = MDC.getCopyOfContextMap();
        MDC.put(this.prefix + "executionId", attempt.snapshot().executionId().toString());
        MDC.put(this.prefix + "communicationId", attempt.target().id().toString());
        MDC.put(this.prefix + "attempt", Integer.toString(attempt.history().number()));
        return () -> {
            if (previous == null) {
                MDC.clear();
            } else {
                MDC.setContextMap(previous);
            }
        };
    }

    /** Область ключей попытки; закрытие восстанавливает прежний MDC. */
    @FunctionalInterface
    interface Scope extends AutoCloseable {

        /** Восстанавливает прежний MDC. Исключений не бросает. */
        @Override
        void close();
    }
}
