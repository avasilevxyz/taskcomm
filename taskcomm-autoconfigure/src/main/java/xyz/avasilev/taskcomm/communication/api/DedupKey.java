package xyz.avasilev.taskcomm.communication.api;

import java.util.Objects;

/**
 * Ключ дедупликации (docs/adr/0076-communication-deduplication.md). Извлекает или считает его
 * потребитель — формат знает только он; стартер хранит ключи и решает, дубликат ли коммуникация.
 * Пространство ключа — направление и внешняя система.
 *
 * <p>Формулу ключа у работающей системы не меняют: новые ключи не совпадут со старыми, и на переходе
 * дубликаты пройдут (docs/guide/evolution/compatibility.md).
 */
public sealed interface DedupKey {

    /**
     * Значение ключа.
     *
     * @return значение
     */
    String key();

    /**
     * Ключ по всей истории: дубликат, если такой ключ есть у <b>любой</b> другой коммуникации —
     * в работе, успешной, упавшей, отменённой, пропущенной.
     *
     * @param key значение
     */
    record WholeHistory(String key) implements DedupKey {

        /**
         * Проверяет значение.
         *
         * @param key значение
         * @throws NullPointerException если значение пусто
         */
        public WholeHistory {
            Objects.requireNonNull(key, "key");
        }
    }

    /**
     * Ключ по последней успешной: дубликат, если ключ совпал с ключом последней <b>успешной</b>
     * коммуникации с тем же предметом или любой <b>незавершённой</b> с тем же предметом и ключом.
     * Например, предмет — товар, значение — хеш цены: та же цена второй раз не уходит, а возврат
     * к прежней — уходит.
     *
     * @param subject предмет
     * @param key значение
     */
    record LastSucceeded(String subject, String key) implements DedupKey {

        /**
         * Проверяет предмет и значение.
         *
         * @param subject предмет
         * @param key значение
         * @throws NullPointerException если предмет или значение пусты
         */
        public LastSucceeded {
            Objects.requireNonNull(subject, "subject");
            Objects.requireNonNull(key, "key");
        }
    }
}
