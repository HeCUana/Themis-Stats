package dev.themisstats.storage;

import dev.themisstats.identity.Identity;
import dev.themisstats.metric.Metric;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests of the explicit import of historical data.
 *
 * <p>The import is the only way data enters the database that this mod did not collect itself, so
 * the tests check both halves of the contract: the rows are read exactly as written, and they never
 * count towards the public leaderboard unless the scope asks for them.</p>
 */
class ImportServiceTest {

    private static Connection connection;
    private static ImportService service;
    private static StatsRepository stats;

    private static final UUID BOT_A = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    @BeforeAll
    static void setUpDatabase() throws SQLException {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement statement = connection.createStatement()) {
            for (String migration : Schema.migrations()) {
                statement.executeUpdate(migration);
            }
        }
        service = new ImportService(connection);
        stats = new StatsRepository(connection);
    }

    @BeforeEach
    void clear() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM total");
            statement.executeUpdate("DELETE FROM adjustment");
            statement.executeUpdate("DELETE FROM exclusion");
            statement.executeUpdate("DELETE FROM account");
            statement.executeUpdate("DELETE FROM account_name");
        }
    }

    private Path csv(Path directory, String content) throws IOException {
        Path file = directory.resolve("import.csv");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void aValidRowIsImported(@TempDir Path directory) throws IOException {
        Path file = csv(directory, BOT_A + ",bot_a,bot,mining,minecraft:stone,minecraft:overworld,survival,120\n");
        ImportService.Result result = service.importCsv(file, "admin");
        assertEquals(1, result.imported());
        assertEquals(0, result.skipped());
        assertTrue(result.problems().isEmpty());
        // The imported value only shows up in the scope that asks for it.
        assertEquals(120L, stats.valueOf(BOT_A, importedScope()));
        assertEquals(0L, stats.valueOf(BOT_A, publicScope()));
    }

    private StatsRepository.Scope importedScope() {
        return StatsRepository.Scope.of(Metric.MINING, Identity.BOT, java.util.List.of(), "", "")
                .withImported();
    }

    private StatsRepository.Scope publicScope() {
        return StatsRepository.Scope.of(Metric.MINING, Identity.BOT, java.util.List.of(), "", "");
    }

    @Test
    void anUnknownMetricIsSkippedAndReported(@TempDir Path directory) throws IOException {
        Path file = csv(directory, BOT_A + ",bot_a,bot,not_a_metric,x,x,survival,1\n");
        ImportService.Result result = service.importCsv(file, "admin");
        assertEquals(0, result.imported());
        assertEquals(1, result.skipped());
        assertEquals(1, result.problems().size());
        assertTrue(result.problems().get(0).contains("not_a_metric"));
    }

    @Test
    void aRowWithTooFewColumnsIsSkippedAndReported(@TempDir Path directory) throws IOException {
        Path file = csv(directory, BOT_A + ",bot_a,bot,mining\n");
        ImportService.Result result = service.importCsv(file, "admin");
        assertEquals(0, result.imported());
        assertEquals(1, result.skipped());
        assertTrue(result.problems().get(0).contains("列数不足"));
    }

    @Test
    void aNegativeValueIsSkippedAndReported(@TempDir Path directory) throws IOException {
        Path file = csv(directory, BOT_A + ",bot_a,bot,mining,x,x,survival,-5\n");
        ImportService.Result result = service.importCsv(file, "admin");
        assertEquals(0, result.imported());
        assertEquals(1, result.skipped());
        assertTrue(result.problems().get(0).contains("为负"));
    }

    @Test
    void commentsAndEmptyLinesAreNotCountedAsSkipped(@TempDir Path directory) throws IOException {
        Path file = csv(directory, "# a comment\n\n" + BOT_A + ",bot_a,bot,mining,x,x,survival,7\n");
        ImportService.Result result = service.importCsv(file, "admin");
        assertEquals(1, result.imported());
        assertEquals(0, result.skipped());
    }

    @Test
    void aMissingFileIsReportedInsteadOfCrashing(@TempDir Path directory) {
        ImportService.Result result = service.importCsv(directory.resolve("missing.csv"), "admin");
        assertEquals(0, result.imported());
        assertEquals(1, result.skipped());
        assertEquals(1, result.problems().size());
    }

    @Test
    void importedRowsAreMarkedAsImported(@TempDir Path directory) throws IOException, SQLException {
        Path file = csv(directory, BOT_A + ",bot_a,bot,mining,x,x,survival,50\n");
        service.importCsv(file, "admin");
        try (Statement statement = connection.createStatement();
             var rs = statement.executeQuery("SELECT source FROM total WHERE uuid = '"
                     + BOT_A + "'")) {
            assertTrue(rs.next());
            assertEquals(ImportService.SOURCE_IMPORTED, rs.getString(1));
        }
    }

    @Test
    void importedRowsStayOutOfThePublicBoardUnlessTheyAreAskedFor(@TempDir Path directory) throws IOException {
        Path file = csv(directory, BOT_A + ",bot_a,bot,mining,x,x,survival,50\n");
        service.importCsv(file, "admin");
        var scope = StatsRepository.Scope.of(Metric.MINING, Identity.BOT, java.util.List.of("survival"));
        // The public board does not count the imported history on its own.
        assertEquals(0L, stats.publicTotal(scope));
        assertTrue(stats.ranked(scope).isEmpty());
        // The scope that asks for it explicitly sees the number.
        assertEquals(50L, stats.publicTotal(scope.withImported()));
        assertEquals(1, stats.ranked(scope.withImported()).size());
    }

    @Test
    void importingTheSameCellTwiceAddsUp(@TempDir Path directory) throws IOException {
        Path file = csv(directory, BOT_A + ",bot_a,bot,mining,x,x,survival,50\n");
        service.importCsv(file, "admin");
        service.importCsv(file, "admin");
        assertEquals(100L, stats.valueOf(BOT_A, importedScope()));
    }

    @Test
    void anImportNeverMixesWithTheCollectedValueOfTheSameCell(@TempDir Path directory)
            throws IOException, SQLException {
        // The collected value of the same cell.
        try (var ps = connection.prepareStatement(
                "INSERT INTO total(uuid, identity, metric, category, dimension, game_mode, value, source) "
                        + "VALUES (?,?,?,?,?,?,?,'collected')")) {
            ps.setString(1, BOT_A.toString());
            ps.setString(2, Identity.BOT.id());
            ps.setString(3, Metric.MINING.id());
            ps.setString(4, "x");
            ps.setString(5, "x");
            ps.setString(6, "survival");
            ps.setLong(7, 10L);
            ps.executeUpdate();
        }
        Path file = csv(directory, BOT_A + ",bot_a,bot,mining,x,x,survival,50\n");
        service.importCsv(file, "admin");
        // The public value is still the collected one: the import is a separate row.
        assertEquals(10L, stats.valueOf(BOT_A, publicScope()));
        // Both together are what the scope that asks for the import sees.
        assertEquals(60L, stats.valueOf(BOT_A, importedScope()));
    }

    @Test
    void theImportNeverHappensOnItsOwn(@TempDir Path directory) throws IOException, SQLException {
        // Nothing but the import itself may put an imported row into the database.
        csv(directory, BOT_A + ",bot_a,bot,mining,x,x,survival,50\n");
        try (Statement statement = connection.createStatement();
             var rs = statement.executeQuery("SELECT COUNT(*) FROM total WHERE source = 'imported'")) {
            assertTrue(rs.next());
            assertEquals(0L, rs.getLong(1));
        }
        assertFalse(ImportService.SOURCE_COLLECTED.equals(ImportService.SOURCE_IMPORTED));
    }
}
