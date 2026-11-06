package xyz.avasilev.taskcomm.autoconfigure;

import java.time.Duration;
import xyz.avasilev.taskcomm.TaskcommConfigurationException;

/** Проверка свойств наблюдаемости при старте (docs/rules/configuration.md). */
final class TaskcommObservability {

    private TaskcommObservability() {}

    /**
     * Префикс ключей MDC. Пустой допустим — тогда ключи без префикса, и столкновение с ключами процессора
     * становится заботой потребителя.
     *
     * @param properties свойства стартера
     * @return префикс
     * @throws TaskcommConfigurationException если префикс не задан
     */
    static String mdcPrefix(final TaskcommProperties properties) {
        final String prefix = properties.getLogging().getMdcPrefix();
        if (prefix == null) {
            throw new TaskcommConfigurationException("taskcomm.logging.mdc-prefix не задан; по умолчанию — tc.");
        }
        return prefix;
    }

    /**
     * Сколько интервалов поток может не тикать до {@code DOWN}.
     *
     * @param properties свойства стартера
     * @return число интервалов
     * @throws TaskcommConfigurationException если оно меньше двух
     */
    static int missedIntervals(final TaskcommProperties properties) {
        final int missed = properties.getHealth().getMissedIntervals();
        if (missed < 2) {
            throw new TaskcommConfigurationException("taskcomm.health.missed-intervals = " + missed
                    + ": нужно не меньше 2, иначе пауза сборщика мусора или занятый планировщик дадут ложный DOWN"
                    + " (docs/adr/0051-engine-health.md)");
        }
        return missed;
    }

    /**
     * Такт снимка метрик на всю схему.
     *
     * @param properties свойства стартера
     * @return такт
     * @throws TaskcommConfigurationException если такт не задан или не положителен
     */
    static Duration metricsInterval(final TaskcommProperties properties) {
        final Duration interval = properties.getMetrics().getInterval();
        if (interval == null || interval.isNegative() || interval.isZero()) {
            throw new TaskcommConfigurationException("taskcomm.metrics.interval = " + interval
                    + ": такт снимка метрик должен быть положительным"
                    + " (docs/adr/0102-schema-wide-metrics-snapshot.md)");
        }
        return interval;
    }
}
