package xyz.avasilev.taskcomm.communication.internal;

/**
 * Место попытки среди попыток своей фазы — для {@code attemptNumber()} и {@code isRetry()} контекста
 * (docs/adr/0112-attempt-number-in-processing-context.md).
 *
 * @param number номер среди попыток той же фазы с начала текущего цикла повторов, с единицы
 * @param retry {@code true}, если у коммуникации была более ранняя попытка той же фазы за всю историю
 */
record AttemptHistory(int number, boolean retry) {

    /** Первая попытка без истории — у синхронного приёма. */
    static final AttemptHistory FIRST = new AttemptHistory(1, false);
}
