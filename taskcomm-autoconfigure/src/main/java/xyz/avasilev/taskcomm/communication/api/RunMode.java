package xyz.avasilev.taskcomm.communication.api;

/**
 * Режим запуска коммуникации (docs/adr/0167-communication-run-mode.md) — свойство вызова, создавшего
 * её, а не процессора и не самой коммуникации.
 */
public enum RunMode {

    /**
     * Синхронный: коммуникацию создал и обрабатывает в своём потоке вызывающий, который ждёт ответа
     * здесь и сейчас, — {@link CommunicationService#sendAndProcess} или
     * {@link CommunicationService#receiveAndProcess}. Движок её не захватывает и фоном не повторяет;
     * колбэк синхронной обрабатывает движок, как у любой.
     */
    SYNC,

    /** Асинхронный: коммуникацию ведёт движок — захват, повторы, переподхват. */
    ASYNC
}
