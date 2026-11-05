package xyz.avasilev.taskcomm.communication.api;

import java.util.Objects;

/**
 * Чем кончился вызов перезапуска коммуникации (docs/adr/0139-restart-api.md). Перезапуск принят —
 * коммуникация снова в очереди и заново проходит отправку или обработку входящей, с новым циклом попыток;
 * отказ — не изменилось ничего, и {@link #reason()} называет причину.
 *
 * <p>Гонка «коммуникация изменилась между чтением и перезапуском» — обычный отказ, а не исключение.
 */
public sealed interface CommunicationRestartResult {

    /**
     * Принят ли перезапуск.
     *
     * @return {@code true}, если коммуникация поставлена в очередь этим вызовом
     */
    boolean accepted();

    /**
     * Причина исхода — для человека и для лога.
     *
     * @return что стало с коммуникацией или почему перезапуск отвергнут
     */
    String reason();

    /**
     * Перезапуск принят.
     *
     * @param reason что будет дальше
     */
    record Accepted(String reason) implements CommunicationRestartResult {

        /**
         * Проверяет причину.
         *
         * @param reason причина
         */
        public Accepted {
            Objects.requireNonNull(reason, "reason");
        }

        @Override
        public boolean accepted() {
            return true;
        }
    }

    /**
     * Перезапуск отвергнут.
     *
     * @param reason почему: не упала, отменена, тело не разобрано, не найдена
     */
    record Rejected(String reason) implements CommunicationRestartResult {

        /**
         * Проверяет причину.
         *
         * @param reason причина
         */
        public Rejected {
            Objects.requireNonNull(reason, "reason");
        }

        @Override
        public boolean accepted() {
            return false;
        }
    }
}
