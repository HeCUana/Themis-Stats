package dev.themisstats.storage;

import dev.themisstats.identity.Identity;
import dev.themisstats.metric.Metric;
import dev.themisstats.metric.MetricIncrement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Tests of the background writer: idempotency, the visible degraded state and the fact that the
 * totals, the session totals and the batch receipt are written in one transaction.
 */
class BatchWriterTest {

    private Connection connection;
    private BatchWriter writer;

    private static final UUID ACCOUNT = UUID.fromString("00000000-0000-0000-0000-00000000f00d");

    @BeforeEach
    void setUp() throws SQLException {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement statement = connection.createStatement()) {
            for (String migration : Schema.migrations()) {
                statement.executeUpdate(migration);
            }
        }
        writer = new BatchWriter(connection);
    }

    @AfterEach
    void tearDown() {
        if (writer != null) {
            writer.close();
        }
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // nothing to do
            }
        }
    }

    private MetricIncrement increment(long amount) {
        return new MetricIncrement(ACCOUNT, Identity.BOT, "session-1", Metric.MINING, amount,
                "minecraft:stone", "minecraft:overworld", "survival", 1L);
    }

    private long totalOfStone() throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT value FROM total WHERE metric = 'mining' AND category = 'minecraft:stone'")) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    private void waitFor(FailCondition condition, long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.test()) {
                return;
            }
            Thread.sleep(25);
        }
        fail("条件在 " + timeoutMillis + " ms 内没有成立（写入器降级=" + writer.isDegraded()
                + "，最后错误=" + writer.lastError() + "）");
    }

    /**
     * Waits until the writer really committed the batch.
     *
     * <p>The test uses the very same connection as the writer, so a plain read would already see
     * the rows of the running transaction. The number of committed batches is only raised after
     * the commit, so it is the point where the data is really there.</p>
     */
    private void awaitCommit() throws Exception {
        long before = writer.committedBatches();
        waitFor(() -> writer.committedBatches() > before, 30_000L);
    }

    private interface FailCondition {
        boolean test() throws Exception;
    }

    @Test
    void anIncrementIsCommittedToTheTotal() throws Exception {
        writer.submit(MetricBatch.of("batch-1", List.of(increment(3)), 1L));
        awaitCommit();
        assertEquals(3L, totalOfStone());
    }

    @Test
    void resubmittingTheSameBatchDoesNotAddItTwice() throws Exception {
        MetricBatch batch = MetricBatch.of("batch-double", List.of(increment(5)), 1L);
        writer.submit(batch);
        awaitCommit();
        // The very same batch is handed over again, as it would happen after a retry of an
        // unacknowledged commit.
        writer.submit(batch);
        Thread.sleep(500);
        assertEquals(5L, totalOfStone(), "a batch must not be counted twice");
    }

    @Test
    void theBatchReceiptIsWrittenTogetherWithTheTotal() throws Exception {
        writer.submit(MetricBatch.of("batch-receipt", List.of(increment(2)), 1L));
        awaitCommit();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT increments FROM applied_batch WHERE batch_id = 'batch-receipt'")) {
            assertTrue(rs.next(), "the batch receipt must exist");
            assertEquals(1, rs.getInt(1));
        }
    }

    @Test
    void theSessionTotalIsWrittenTogetherWithTheTotal() throws Exception {
        writer.submit(MetricBatch.of("batch-session", List.of(increment(4)), 1L));
        awaitCommit();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT value FROM session_metric WHERE session_id = 'session-1' AND metric = 'mining'")) {
            assertTrue(rs.next(), "the session total must exist（写入器降级=" + writer.isDegraded()
                    + "，最后错误=" + writer.lastError() + "）");
            assertEquals(4L, rs.getLong(1));
        }
    }

    @Test
    void aFailingDatabasePutsTheWriterIntoAVisibleDegradedState() throws Exception {
        // The database is closed, so the next commit has to fail.
        connection.close();
        writer.submit(MetricBatch.of("batch-fail", List.of(increment(1)), 1L));
        waitFor(() -> writer.isDegraded(), 30_000L);
        assertTrue(writer.isDegraded(), "a failing database must put the writer into the degraded state");
        assertTrue(writer.lastError() != null && !writer.lastError().isBlank(),
                "the degraded state must name the reason");
        assertTrue(writer.describe().contains("降级") || writer.describe().contains("异常"),
                "the state description must be visible to the administrator: " + writer.describe());
        assertFalse(writer.lastSuccessMillis() > 0 && writer.lastError() == null,
                "a failed write must not look healthy");
    }

    @Test
    void anEmptyBatchIsAcceptedWithoutAnyWork() {
        assertTrue(writer.submit(MetricBatch.of("batch-empty", List.of(), 1L)));
        assertEquals(0, writer.backlog());
    }
}
