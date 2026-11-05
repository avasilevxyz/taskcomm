package xyz.avasilev.taskcomm.task.api;

import java.util.Objects;

/**
 * Чем кончился вызов перезапуска (docs/adr/0049-restart-semantics.md, docs/adr/0139-restart-api.md).
 * Перезапуск принят — сущность снова в очереди, а её предки ждут детей; отказ — не изменилось ничего,
 * и {@link #reason()} называет причину и сущность, из-за которой перезапуск невозможен.
 *
 * <p>Гонка «сущность или предок изменились между чтением и перезапуском» — обычный отказ,
 * а не исключение.
 */
public sealed interface TaskRestartResult {

    /**
     * Принят ли перезапуск.
     *
     * @return {@code true}, если сущность поставлена в очередь этим вызовом
     */
    boolean accepted();

    /**
     * Причина исхода — для человека и для лога.
     *
     * @return что стало с сущностью или почему перезапуск отвергнут
     */
    String reason();

    /**
     * Перезапуск принят.
     *
     * @param reason что будет дальше: обработка заново или новое раскрытие
     */
    record Accepted(String reason) implements TaskRestartResult {

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
     * @param reason почему: статус, отмена, сборка предка, прежнее раскрытие, удалённые результаты
     */
    record Rejected(String reason) implements TaskRestartResult {

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
