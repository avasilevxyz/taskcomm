package xyz.avasilev.taskcomm.communication.api;

import java.util.Objects;

/**
 * Чем кончился вызов отмены коммуникации (docs/adr/0139-restart-api.md). Отмена принята — на коммуникации
 * стоит отметка {@code cancel_requested_at}: ожидающая очереди или ответа становится {@code CANCELLED} сразу,
 * отправляющаяся или обрабатываемая доигрывает попытку и пишет настоящий исход, а отправка, после
 * которой ждут ответа, — {@code CANCELLED} (docs/adr/0100-cancel-keeps-real-outcome.md). Отказ — отметка
 * не ставилась: коммуникация терминальна или уже отменена.
 *
 * <p>Гонка «коммуникация завершилась между чтением и отменой» — обычный отказ, а не исключение.
 */
public sealed interface CommunicationCancelResult {

    /**
     * Принята ли отмена.
     *
     * @return {@code true}, если отметка поставлена этим вызовом
     */
    boolean accepted();

    /**
     * Причина исхода — для человека и для лога.
     *
     * @return что стало с коммуникацией или почему отмена отвергнута
     */
    String reason();

    /**
     * Отмена принята.
     *
     * @param reason что стало с коммуникацией: отменена сразу или доиграет попытку
     */
    record Accepted(String reason) implements CommunicationCancelResult {

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
     * Отмена отвергнута.
     *
     * @param reason почему: терминальна, уже отменена, не найдена
     */
    record Rejected(String reason) implements CommunicationCancelResult {

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
