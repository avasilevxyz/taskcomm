package xyz.avasilev.taskcomm.communication.internal.observation;

import java.util.List;
import java.util.function.IntSupplier;

/** Метрик нет: нет Micrometer или реестра у потребителя. */
final class NoCommunicationMetrics implements CommunicationMetrics {

    @Override
    public void attempt(
            final String system,
            final String direction,
            final String phase,
            final String outcome,
            final boolean retry,
            final long nanos) {
        // метрик нет
    }

    @Override
    public void breaker(final String system, final IntSupplier state) {
        // метрик нет
    }

    @Override
    public void breakerTransition(final String system, final String to) {
        // метрик нет
    }

    @Override
    public void snapshot(final List<SnapshotRow> rows, final double ageSeconds) {
        // метрик нет
    }
}
