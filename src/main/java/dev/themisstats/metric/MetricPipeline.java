package dev.themisstats.metric;

import dev.themisstats.ThemisStats;
import dev.themisstats.identity.Identity;
import dev.themisstats.session.Session;
import dev.themisstats.storage.BatchWriter;
import dev.themisstats.storage.MetricBatch;
import dev.themisstats.storage.SessionUpdate;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The metric collection pipeline.
 *
 * <p>Every increment is stamped with the identity, the session, the dimension and the game mode
 * <em>at the moment the behaviour happened</em>. Nothing is re-derived later from a currently
 * online object, because the identity may have changed in the meantime.</p>
 *
 * <p>Increments are buffered in memory and handed to the background writer as an immutable batch.
 * The game thread never waits for the disk.</p>
 */
public final class MetricPipeline {

    private static final int MAX_BUFFERED_INCREMENTS = 20_000;

    private final BatchWriter writer;
    private final Object lock = new Object();
    private final List<MetricIncrement> buffer = new ArrayList<>();
    private final List<SessionUpdate> pendingSessions = new ArrayList<>();
    private final AtomicLong batchCounter = new AtomicLong();
    private final AtomicLong droppedIncrements = new AtomicLong();
    private final AtomicLong recordedIncrements = new AtomicLong();

    public MetricPipeline(BatchWriter writer) {
        this.writer = writer;
    }

    /**
     * Records a metric increment for a player.
     *
     * @param session   the session the behaviour happened in
     * @param metric    the metric
     * @param amount    the amount, must be positive
     * @param category  the block / entity / reason breakdown key, may be {@code null}
     */
    public void record(Session session, Metric metric, long amount, String category, String dimension,
                       String gameMode) {
        if (session == null || amount <= 0) {
            return;
        }
        MetricIncrement increment = new MetricIncrement(session.accountUuid(), session.identity(),
                session.id(), metric, amount, category, dimension, gameMode, System.currentTimeMillis());
        synchronized (lock) {
            if (buffer.size() >= MAX_BUFFERED_INCREMENTS) {
                droppedIncrements.incrementAndGet();
                return;
            }
            buffer.add(increment);
        }
        recordedIncrements.incrementAndGet();
    }

    /**
     * Queues a session settlement (or checkpoint).
     */
    public void submitSession(SessionUpdate update) {
        synchronized (lock) {
            pendingSessions.add(update);
        }
    }

    /**
     * Moves the buffered increments into a batch and hands it to the writer.
     *
     * @return the number of increments that were handed over
     */
    public int flush() {
        List<MetricIncrement> increments;
        List<SessionUpdate> sessions;
        synchronized (lock) {
            if (buffer.isEmpty() && pendingSessions.isEmpty()) {
                return 0;
            }
            increments = new ArrayList<>(buffer);
            sessions = new ArrayList<>(pendingSessions);
            buffer.clear();
            pendingSessions.clear();
        }
        String batchId = "b-" + System.currentTimeMillis() + "-" + batchCounter.incrementAndGet();
        MetricBatch batch = new MetricBatch(batchId, increments, sessions, System.currentTimeMillis());
        if (!writer.submit(batch)) {
            // The queue is full: the writer degrades and reports the gap. The increments are lost
            // for this interval, which is exactly what the degraded state documents.
            ThemisStats.LOGGER.error("[ThemisStats] 指标批次 {} 未被接受，写入队列已满", batchId);
        }
        return increments.size();
    }

    /** @return how many increments were dropped because the in-memory buffer was full */
    public long droppedIncrements() {
        return droppedIncrements.get();
    }

    /** @return how many increments were recorded in total */
    public long recordedIncrements() {
        return recordedIncrements.get();
    }

    /** @return how many increments are waiting in the buffer right now */
    public int buffered() {
        synchronized (lock) {
            return buffer.size();
        }
    }
}
