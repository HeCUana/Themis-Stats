package dev.themisstats.storage;

import dev.themisstats.identity.Identity;
import dev.themisstats.metric.Metric;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests of the P1 scope filters.
 *
 * <p>The scope grew from two components to six: the game modes, the dimension, the category and the
 * origin of the rows joined the metric and the identity. Every filter has to narrow the same
 * public value formula, so that the fine board and the total can never disagree.</p>
 */
class StatsRepositoryScopeTest {

    private static Connection connection;
    private static StatsRepository repository;

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-0000000000b0");

    @BeforeAll
    static void setUpDatabase() throws SQLException {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement statement = connection.createStatement()) {
            for (String migration : Schema.migrations()) {
                statement.executeUpdate(migration);
            }
        }
        repository = new StatsRepository(connection);
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

    private void total(UUID uuid, String metric, String category, String dimension, String gameMode, long value)
            throws SQLException {
        try (var ps = connection.prepareStatement(
                "INSERT INTO total(uuid, identity, metric, category, dimension, game_mode, value) "
                        + "VALUES (?,?,?,?,?,?,?)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, Identity.BOT.id());
            ps.setString(3, metric);
            ps.setString(4, category);
            ps.setString(5, dimension);
            ps.setString(6, gameMode);
            ps.setLong(7, value);
            ps.executeUpdate();
        }
    }

    @Test
    void anEmptyDimensionMeansEveryDimension() throws SQLException {
        total(ALICE, Metric.MINING.id(), "minecraft:stone", "minecraft:overworld", "survival", 10);
        total(ALICE, Metric.MINING.id(), "minecraft:stone", "minecraft:the_nether", "survival", 20);
        var scope = StatsRepository.Scope.of(Metric.MINING, Identity.BOT, List.of("survival"), "", "");
        assertEquals(30L, repository.valueOf(ALICE, scope));
    }

    @Test
    void aDimensionNarrowsTheValue() throws SQLException {
        total(ALICE, Metric.MINING.id(), "minecraft:stone", "minecraft:overworld", "survival", 10);
        total(ALICE, Metric.MINING.id(), "minecraft:stone", "minecraft:the_nether", "survival", 20);
        var scope = StatsRepository.Scope.of(Metric.MINING, Identity.BOT, List.of("survival"),
                "minecraft:the_nether", "");
        assertEquals(20L, repository.valueOf(ALICE, scope));
    }

    @Test
    void aCategoryNarrowsTheValue() throws SQLException {
        total(ALICE, Metric.MINING.id(), "minecraft:stone", "minecraft:overworld", "survival", 10);
        total(ALICE, Metric.MINING.id(), "minecraft:dirt", "minecraft:overworld", "survival", 5);
        var scope = StatsRepository.Scope.of(Metric.MINING, Identity.BOT, List.of("survival"),
                "minecraft:overworld", "minecraft:dirt");
        assertEquals(5L, repository.valueOf(ALICE, scope));
    }

    @Test
    void theFineBoardAndTheTotalAgree() throws SQLException {
        total(ALICE, Metric.MINING.id(), "minecraft:stone", "minecraft:overworld", "survival", 10);
        total(ALICE, Metric.MINING.id(), "minecraft:dirt", "minecraft:overworld", "survival", 5);
        total(BOB, Metric.MINING.id(), "minecraft:stone", "minecraft:overworld", "survival", 30);
        var scope = StatsRepository.Scope.of(Metric.MINING, Identity.BOT, List.of("survival"),
                "minecraft:overworld", "minecraft:stone");
        assertEquals(40L, repository.publicTotal(scope));
        var board = repository.ranked(scope);
        assertEquals(2, board.size());
        assertEquals(30L, board.get(0).value());
        assertEquals(10L, board.get(1).value());
        // The sum of the board is the total of the scope.
        assertEquals(repository.publicTotal(scope),
                board.stream().mapToLong(StatsRepository.BoardEntry::value).sum());
    }

    @Test
    void theGameModeFilterExcludesTheOtherModes() throws SQLException {
        total(ALICE, Metric.MINING.id(), "minecraft:stone", "minecraft:overworld", "creative", 99);
        total(ALICE, Metric.MINING.id(), "minecraft:stone", "minecraft:overworld", "survival", 10);
        var scope = StatsRepository.Scope.of(Metric.MINING, Identity.BOT, List.of("survival", "adventure"),
                "", "");
        assertEquals(10L, repository.valueOf(ALICE, scope));
    }

    @Test
    void theOnlineMetricIsNotFilteredByGameMode() throws SQLException {
        total(ALICE, Metric.ONLINE.id(), "", "minecraft:overworld", "survival", 100);
        var scope = StatsRepository.Scope.of(Metric.ONLINE, Identity.BOT);
        assertTrue(scope.gameModes().isEmpty());
        assertEquals(100L, repository.valueOf(ALICE, scope));
    }

    @Test
    void anAdjustmentOfAnotherCellDoesNotLeakIntoTheFineBoard() throws SQLException {
        total(ALICE, Metric.MINING.id(), "minecraft:stone", "minecraft:overworld", "survival", 10);
        try (var ps = connection.prepareStatement(
                "INSERT INTO adjustment(uuid, identity, metric, category, dimension, game_mode, delta, reason, "
                        + "operator, created_wall) VALUES (?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, ALICE.toString());
            ps.setString(2, Identity.BOT.id());
            ps.setString(3, Metric.MINING.id());
            ps.setString(4, "minecraft:dirt");
            ps.setString(5, "minecraft:overworld");
            ps.setString(6, "survival");
            ps.setLong(7, 500L);
            ps.setString(8, "修正");
            ps.setString(9, "admin");
            ps.setLong(10, 1L);
            ps.executeUpdate();
        }
        var fine = StatsRepository.Scope.of(Metric.MINING, Identity.BOT, List.of("survival"),
                "minecraft:overworld", "minecraft:stone");
        assertEquals(10L, repository.valueOf(ALICE, fine));
    }

    @Test
    void theExclusionStillRemovesTheObjectFromEveryScope() throws SQLException {
        total(ALICE, Metric.MINING.id(), "minecraft:stone", "minecraft:overworld", "survival", 10);
        total(BOB, Metric.MINING.id(), "minecraft:stone", "minecraft:overworld", "survival", 20);
        try (var ps = connection.prepareStatement(
                "INSERT INTO exclusion(uuid, identity, excluded, reason, operator, created_wall) "
                        + "VALUES (?,?,1,?,?,?)")) {
            ps.setString(1, BOB.toString());
            ps.setString(2, Identity.BOT.id());
            ps.setString(3, "排除");
            ps.setString(4, "admin");
            ps.setLong(5, 1L);
            ps.executeUpdate();
        }
        var scope = StatsRepository.Scope.of(Metric.MINING, Identity.BOT, List.of("survival"), "", "");
        assertEquals(1, repository.ranked(scope).size());
        assertEquals(10L, repository.publicTotal(scope));
    }
}
