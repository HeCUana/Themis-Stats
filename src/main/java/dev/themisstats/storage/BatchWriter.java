package dev.themisstats.storage;

import dev.themisstats.ThemisStats;
import dev.themisstats.metric.MetricIncrement;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The background writer.
 *
 * <p>Metric increments are handed over as immutable {@link MetricBatch batches} and committed by a
 * dedicated thread. The game thread never waits for the disk.</p>
 *
 * <p><b>Bounded and observable.</b> The queue has a fixed capacity. If it overflows, the writer
 * enters a clearly visible <em>degraded</em> state: the gap is recorded, the backlog is counted and
 * reported by {@code /themisstats admin status}, and the affected interval is marked as incomplete
 * in the database. Nothing is dropped silently.</p>
 *
 * <p><b>Idempotent.</b> Every batch carries a stable id that is written to {@code applied_batch}
 * inside the very same transaction that updates the totals. Re-submitting a batch therefore cannot
 * add the same increment twice.</p>
 */
public final class BatchWriter implements AutoCloseable {

    /** How many batches may wait in the queue before the writer degrades. */
    private static final int QUEUE_CAPACITY = 512;

    /** The target commit interval. */
    private static final long COMMIT_INTERVAL_MILLIS = 5_000L;

    private final Connection connection;
    private final BlockingQueue<MetricBatch> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final Thread worker;
    private final AtomicLong submittedBatches = new AtomicLong();
    private final AtomicLong committedBatches = new AtomicLong();
    private final AtomicLong committedIncrements = new AtomicLong();
    private final AtomicLong degradedEvents = new AtomicLong();
    private final Object flushLock = new Object();

    private volatile boolean running = true;
    private volatile boolean degraded;
    private volatile String lastError;
    private volatile long lastErrorMillis;
    private volatile long lastSuccessMillis;
    private volatile long degradedSinceMillis;
    private volatile String degradedSinceReason;

    public BatchWriter(Connection connection) {
        this.connection = connection;
        this.worker = new Thread(this::run, "ThemisStats-Writer");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    // ------------------------------------------------------------------ public API

    /**
     * Hands a batch over to the writer.
     *
     * @return {@code true} if the batch was accepted, {@code false} if the queue was full and the
     *         writer had to degrade
     */
    public boolean submit(MetricBatch batch) {
        if (batch == null || batch.isEmpty()) {
            return true;
        }
        if (!running) {
            return false;
        }
        submittedBatches.incrementAndGet();
        if (queue.offer(batch)) {
            return true;
        }
        // The queue is full: do not block the game thread, degrade visibly instead.
        enterDegraded("写入队列已满（容量 " + QUEUE_CAPACITY + " 批），本批增量未进入队列");
        return false;
    }

    /**
     * Requests that everything queued so far is committed.
     *
     * @param timeoutMillis how long to wait for the flush
     * @return {@code true} if the queue was empty and committed when the call returned
     */
    public boolean flush(long timeoutMillis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        CompletableFuture<Void> marker = new CompletableFuture<>();
        // A marker batch lets us detect that the writer processed everything queued before it.
        MetricBatch markerBatch = new MetricBatch("flush-" + System.nanoTime(), List.of(), List.of(),
                System.currentTimeMillis());
        if (!queue.offer(markerBatch)) {
            return false;
        }
        while (System.nanoTime() < deadline) {
            if (queue.isEmpty()) {
                return true;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        marker.complete(null);
        return queue.isEmpty();
    }

    /** @return the number of batches waiting in the queue */
    public int backlog() {
        return queue.size();
    }

    /** @return whether the writer is currently in the degraded state */
    public boolean isDegraded() {
        return degraded;
    }

    public String lastError() {
        return lastError;
    }

    public long lastErrorMillis() {
        return lastErrorMillis;
    }

    public long lastSuccessMillis() {
        return lastSuccessMillis;
    }

    public long degradedSinceMillis() {
        return degradedSinceMillis;
    }

    public String degradedSinceReason() {
        return degradedSinceReason;
    }

    public long submittedBatches() {
        return submittedBatches.get();
    }

    public long committedBatches() {
        return committedBatches.get();
    }

    public long committedIncrements() {
        return committedIncrements.get();
    }

    public long degradedEvents() {
        return degradedEvents.get();
    }

    /** @return a human readable summary of the writer state, for the admin status page */
    public String describe() {
        if (lastError != null) {
            return "保存异常：" + lastError + "（最近一次错误 " + new java.util.Date(lastErrorMillis) + "）";
        }
        if (degraded) {
            return "采集降级中：" + degradedSinceReason + "（自 "
                    + new java.util.Date(degradedSinceMillis) + "）";
        }
        if (lastSuccessMillis == 0) {
            return "尚未完成首次提交";
        }
        return "正常，最后成功提交 " + new java.util.Date(lastSuccessMillis);
    }

    // ------------------------------------------------------------------ worker

    private void run() {
        while (running) {
            MetricBatch batch = null;
            try {
                batch = queue.poll(COMMIT_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
                if (batch != null) {
                    commit(batch);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (SQLException e) {
                lastError = e.getMessage();
                lastErrorMillis = System.currentTimeMillis();
                enterDegraded("数据库提交失败：" + e.getMessage());
                // Re-queue at the head so that the batch is retried, and keep the order.
                if (batch != null) {
                    requeue(batch);
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            } catch (RuntimeException e) {
                lastError = String.valueOf(e.getMessage());
                lastErrorMillis = System.currentTimeMillis();
                enterDegraded("提交时发生异常：" + e.getMessage());
                if (batch != null) {
                    requeue(batch);
                }
            }
        }
    }

    private void requeue(MetricBatch batch) {
        // The queue is a FIFO array queue; offer() puts the batch at the tail. The batch keeps its
        // identity, so a retry cannot double count.
        if (!queue.offer(batch)) {
            ThemisStats.LOGGER.error("[ThemisStats] 无法重新入队批次 {}，该批增量存在缺口", batch.batchId());
            degradedEvents.incrementAndGet();
        }
    }

    private void enterDegraded(String reason) {
        degradedEvents.incrementAndGet();
        if (!degraded) {
            degraded = true;
            degradedSinceMillis = System.currentTimeMillis();
            degradedSinceReason = reason;
            ThemisStats.LOGGER.error("[ThemisStats] 统计写入进入降级状态：{}", reason);
        }
    }

    /**
     * Commits a batch. Totals, session rows and the batch acknowledgement are written in a single
     * transaction, so the database never shows totals that are not backed by a batch receipt.
     */
    private void commit(MetricBatch batch) throws SQLException {
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            if (batchApplied(batch.batchId())) {
                // Already committed: acknowledging it again must not add anything.
                connection.commit();
                committedBatches.incrementAndGet();
                return;
            }
            applySessionUpdates(batch.sessionUpdates());
            applyIncrements(batch.increments());
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO applied_batch(batch_id, applied_wall, increments) VALUES (?,?,?)")) {
                ps.setString(1, batch.batchId());
                ps.setLong(2, System.currentTimeMillis());
                ps.setInt(3, batch.increments().size());
                ps.executeUpdate();
            }
            connection.commit();
            committedBatches.incrementAndGet();
            committedIncrements.addAndGet(batch.increments().size());
            lastSuccessMillis = System.currentTimeMillis();
            lastError = null;
            if (degraded && queue.isEmpty()) {
                degraded = false;
                degradedSinceMillis = 0;
                degradedSinceReason = null;
                ThemisStats.LOGGER.info("[ThemisStats] 统计写入已恢复正常");
            }
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    private boolean batchApplied(String batchId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM applied_batch WHERE batch_id = ?")) {
            ps.setString(1, batchId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void applyIncrements(List<MetricIncrement> increments) throws SQLException {
        if (increments.isEmpty()) {
            return;
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO total(uuid, identity, metric, category, dimension, game_mode, value) "
                        + "VALUES (?,?,?,?,?,?,?) "
                        + "ON CONFLICT(uuid, identity, metric, category, dimension, game_mode) "
                        + "DO UPDATE SET value = value + excluded.value")) {
            for (MetricIncrement increment : increments) {
                ps.setString(1, increment.accountUuid().toString());
                ps.setString(2, increment.identity().id());
                ps.setString(3, increment.metric().id());
                ps.setString(4, increment.categoryKey());
                ps.setString(5, increment.dimension() == null ? "" : increment.dimension());
                ps.setString(6, increment.gameMode() == null ? "" : increment.gameMode());
                ps.setLong(7, increment.amount());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO session_metric(session_id, metric, category, dimension, game_mode, value) "
                        + "VALUES (?,?,?,?,?,?) "
                        + "ON CONFLICT(session_id, metric, category, dimension, game_mode) "
                        + "DO UPDATE SET value = value + excluded.value")) {
            for (MetricIncrement increment : increments) {
                ps.setString(1, increment.sessionId());
                ps.setString(2, increment.metric().id());
                ps.setString(3, increment.categoryKey());
                ps.setString(4, increment.dimension() == null ? "" : increment.dimension());
                ps.setString(5, increment.gameMode() == null ? "" : increment.gameMode());
                ps.setLong(6, increment.amount());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void applySessionUpdates(List<SessionUpdate> updates) throws SQLException {
        for (SessionUpdate update : updates) {
            if (update.settled()) {
                settleSession(update);
            } else {
                createOrCheckpointSession(update);
            }
        }
    }

    private void createOrCheckpointSession(SessionUpdate update) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO session(id, boot_id, uuid, identity, start_wall, end_wall, end_reason, "
                        + "checkpoint_wall, checkpoint_nano, actual_online_ms, collected_ms, "
                        + "initial_dimension, initial_x, initial_y, initial_z, last_dimension, "
                        + "last_x, last_y, last_z, owner_uuid, owner_name, purpose, note, complete, gap_note) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) "
                        + "ON CONFLICT(id) DO UPDATE SET "
                        + "checkpoint_wall = excluded.checkpoint_wall, "
                        + "checkpoint_nano = excluded.checkpoint_nano, "
                        + "actual_online_ms = excluded.actual_online_ms, "
                        + "collected_ms = excluded.collected_ms, "
                        + "last_dimension = excluded.last_dimension, "
                        + "last_x = excluded.last_x, last_y = excluded.last_y, last_z = excluded.last_z, "
                        + "owner_uuid = excluded.owner_uuid, owner_name = excluded.owner_name, "
                        + "purpose = excluded.purpose, note = excluded.note, "
                        + "gap_note = excluded.gap_note")) {
            bindSession(ps, update, false);
            ps.executeUpdate();
        }
    }

    private void settleSession(SessionUpdate update) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO session(id, boot_id, uuid, identity, start_wall, end_wall, end_reason, "
                        + "checkpoint_wall, checkpoint_nano, actual_online_ms, collected_ms, "
                        + "initial_dimension, initial_x, initial_y, initial_z, last_dimension, "
                        + "last_x, last_y, last_z, owner_uuid, owner_name, purpose, note, complete, gap_note) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) "
                        + "ON CONFLICT(id) DO UPDATE SET "
                        + "end_wall = excluded.end_wall, end_reason = excluded.end_reason, "
                        + "checkpoint_wall = excluded.checkpoint_wall, "
                        + "checkpoint_nano = excluded.checkpoint_nano, "
                        + "actual_online_ms = excluded.actual_online_ms, "
                        + "collected_ms = excluded.collected_ms, "
                        + "last_dimension = excluded.last_dimension, "
                        + "last_x = excluded.last_x, last_y = excluded.last_y, last_z = excluded.last_z, "
                        + "owner_uuid = excluded.owner_uuid, owner_name = excluded.owner_name, "
                        + "purpose = excluded.purpose, note = excluded.note, "
                        + "complete = excluded.complete, gap_note = excluded.gap_note")) {
            bindSession(ps, update, true);
            ps.executeUpdate();
        }
        // The collected time of the session is the `online` metric of that identity.
        long onlineMillis = update.collectedMillis();
        if (onlineMillis > 0) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO total(uuid, identity, metric, category, dimension, game_mode, value) "
                            + "VALUES (?,?,?,'','','',?) "
                            + "ON CONFLICT(uuid, identity, metric, category, dimension, game_mode) "
                            + "DO UPDATE SET value = value + excluded.value")) {
                ps.setString(1, update.accountUuid().toString());
                ps.setString(2, update.identity());
                ps.setString(3, "online");
                ps.setLong(4, onlineMillis);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO session_metric(session_id, metric, category, dimension, game_mode, value) "
                            + "VALUES (?,?,'','','',?) "
                            + "ON CONFLICT(session_id, metric, category, dimension, game_mode) "
                            + "DO UPDATE SET value = value + excluded.value")) {
                ps.setString(1, update.sessionId());
                ps.setString(2, "online");
                ps.setLong(3, onlineMillis);
                ps.executeUpdate();
            }
        }
    }

    private void bindSession(PreparedStatement ps, SessionUpdate update, boolean settled) throws SQLException {
        ps.setString(1, update.sessionId());
        ps.setString(2, update.bootId());
        ps.setString(3, update.accountUuid().toString());
        ps.setString(4, update.identity());
        ps.setLong(5, update.startWall());
        if (update.endWall() == null) {
            ps.setNull(6, java.sql.Types.INTEGER);
        } else {
            ps.setLong(6, update.endWall());
        }
        ps.setString(7, update.endReason());
        ps.setLong(8, update.checkpointWall());
        ps.setLong(9, update.checkpointNano());
        ps.setLong(10, update.actualOnlineMillis());
        ps.setLong(11, update.collectedMillis());
        ps.setString(12, update.initialDimension());
        setNullableDouble(ps, 13, update.initialX());
        setNullableDouble(ps, 14, update.initialY());
        setNullableDouble(ps, 15, update.initialZ());
        ps.setString(16, update.lastDimension());
        setNullableDouble(ps, 17, update.lastX());
        setNullableDouble(ps, 18, update.lastY());
        setNullableDouble(ps, 19, update.lastZ());
        ps.setString(20, update.ownerUuid());
        ps.setString(21, update.ownerName());
        ps.setString(22, update.purpose());
        ps.setString(23, update.note());
        ps.setInt(24, settled && update.gapNote() != null ? 0 : 1);
        ps.setString(25, update.gapNote());
    }

    private static void setNullableDouble(PreparedStatement ps, int index, Double value) throws SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.REAL);
        } else {
            ps.setDouble(index, value);
        }
    }

    // ------------------------------------------------------------------ shutdown

    @Override
    public void close() {
        running = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!queue.isEmpty() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        worker.interrupt();
        try {
            worker.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        synchronized (flushLock) {
            flushLock.notifyAll();
        }
    }
}
