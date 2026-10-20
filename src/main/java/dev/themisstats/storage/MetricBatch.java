package dev.themisstats.storage;

import dev.themisstats.metric.MetricIncrement;

import java.util.List;

/**
 * One immutable unit of work for the background writer.
 *
 * <p>A batch has a stable identity ({@link #batchId()}). Re-submitting the same batch is a no-op,
 * which is what makes a retry after an unacknowledged commit safe.</p>
 */
public record MetricBatch(String batchId,
                          List<MetricIncrement> increments,
                          List<SessionUpdate> sessionUpdates,
                          long wallClockMillis) {

    public MetricBatch {
        increments = List.copyOf(increments);
        sessionUpdates = List.copyOf(sessionUpdates);
    }

    public static MetricBatch of(String batchId, List<MetricIncrement> increments, long wallClockMillis) {
        return new MetricBatch(batchId, increments, List.of(), wallClockMillis);
    }

    public boolean isEmpty() {
        return increments.isEmpty() && sessionUpdates.isEmpty();
    }
}
