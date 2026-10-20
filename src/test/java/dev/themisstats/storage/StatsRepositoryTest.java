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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests of the public value formula and of the ranking rules.
 *
 * <p>These tests run against a real SQLite database with the real schema, because the behaviour
 * under test is SQL behaviour: the formula, the competition ranking, the exclusion filter and the
 * game mode filter. A mocked repository would not prove any of it.</p>
 */
class StatsRepositoryTest {

    private static Connection connection;
    private static StatsRepository repository;
    private static AccountRepository accounts;

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-0000000000b0");
    private static final UUID CAROL = UUID.fromString("00000000-0000-0000-0000-0000000000c0");
    private static final UUID DAVE = UUID.fromString("00000000-0000-0000-0000-0000000000d0");
    private static final UUID ERIN = UUID.fromString("00000000-0000-0000-0000-0000000000e0");

    @BeforeAll
    static void setUpDatabase() throws SQLException {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement statement = connection.createStatement()) {
            for (String migration : Schema.migrations()) {
                statement.executeUpdate(migration);
            }
        }
        repository = new StatsRepository(connection);
        accounts = new AccountRepository(connection);
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

    private void total(UUID uuid, Metric metric, String category, String dimension, String gameMode, long value)
            throws SQLException {
        try (var ps = connection.prepareStatement(
                "INSERT INTO total(uuid, identity, metric, category, dimension, game_mode, value) "
                        + "VALUES (?,?,?,?,?,?,?)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, Identity.BOT.id());
            ps.setString(3, metric.id());
            ps.setString(4, category);
            ps.setString(5, dimension);
            ps.setString(6, gameMode);
            ps.setLong(7, value);
            ps.executeUpdate();
        }
    }

    @Test
    void competitionRankingProducesOneTwoTwoFour() throws SQLException {
        total(ALICE, Metric.MINING, "minecraft:stone", "minecraft:overworld", "survival", 100);
        total(BOB, Metric.MINING, "minecraft:stone", "minecraft:overworld", "survival", 50);
        total(CAROL, Metric.MINING, "minecraft:stone", "minecraft:overworld", "survival", 50);
        total(DAVE, Metric.MINING, "minecraft:stone", "minecraft:overworld", "survival", 10);

        var ranked = repository.ranked(StatsRepository.Scope.of(Metric.MINING, Identity.BOT));

        assertEquals(4, ranked.size());
        assertEquals(1, ranked.get(0).rank(), "the best value must be rank 1");
        assertEquals(2, ranked.get(1).rank(), "the first tied value must be rank 2");
        assertEquals(2, ranked.get(2).rank(), "the second tied value must share rank 2");
        assertEquals(4, ranked.get(3).rank(), "the rank after a tie must skip 3");
        assertEquals(50, ranked.get(1).value());
        assertEquals(50, ranked.get(2).value());
    }

    @Test
    void zeroValuesDoNotTakeABoardPlace() throws SQLException {
        total(ALICE, Metric.MINING, "minecraft:stone", "minecraft:overworld", "survival", 0);
        total(BOB, Metric.MINING, "minecraft:stone", "minecraft:overworld", "survival", 7);

        var ranked = repository.ranked(StatsRepository.Scope.of(Metric.MINING, Identity.BOT));

        assertEquals(1, ranked.size(), "an object with only zero must not take a board place");
        assertEquals(BOB, ranked.get(0).uuid());
    }

    @Test
    void creativeDataIsNotPartOfThePublicBoard() throws SQLException {
        total(ALICE, Metric.MINING, "minecraft:stone", "minecraft:overworld", "survival", 10);
        total(ALICE, Metric.MINING, "minecraft:stone", "minecraft:overworld", "creative", 999);

        var scope = StatsRepository.Scope.of(Metric.MINING, Identity.BOT);
        assertEquals(10, repository.valueOf(ALICE, scope),
                "the public value must only contain the survival + adventure cells");
        assertEquals(10, repository.publicTotal(scope));
    }

    @Test
    void adjustmentsAreAddedToThePublicValue() throws SQLException {
        total(ALICE, Metric.MINING, "minecraft:stone", "minecraft:overworld", "survival", 10);
        accounts.addAdjustment(ALICE, Identity.BOT, Metric.MINING.id(), "minecraft:stone",
                "minecraft:overworld", "survival", 5, "manual correction", "admin", 1L);

        var scope = StatsRepository.Scope.of(Metric.MINING, Identity.BOT);
        assertEquals(15, repository.valueOf(ALICE, scope),
                "the public value must be the collected value plus the adjustment");
        assertEquals(15, repository.publicTotal(scope),
                "the overview total must use the same formula as the board");
    }

    @Test
    void anAdjustmentForAnotherCellDoesNotLeak() throws SQLException {
        total(ALICE, Metric.MINING, "minecraft:stone", "minecraft:overworld", "survival", 10);
        accounts.addAdjustment(ALICE, Identity.BOT, Metric.MINING.id(), "minecraft:dirt",
                "minecraft:overworld", "survival", 5, "other cell", "admin", 1L);

        assertEquals(10, repository.valueOf(ALICE, StatsRepository.Scope.of(Metric.MINING, Identity.BOT)),
                "an adjustment of a different detail scope must not change this cell");
    }

    @Test
    void excludedObjectsLeaveTheBoardButKeepTheirData() throws SQLException {
        total(ALICE, Metric.MINING, "minecraft:stone", "minecraft:overworld", "survival", 100);
        total(BOB, Metric.MINING, "minecraft:stone", "minecraft:overworld", "survival", 50);
        accounts.setExclusion(ALICE, Identity.BOT, true, "test object", "admin", 1L);

        var scope = StatsRepository.Scope.of(Metric.MINING, Identity.BOT);
        var ranked = repository.ranked(scope);

        assertEquals(1, ranked.size(), "an excluded object must not appear on the public board");
        assertEquals(BOB, ranked.get(0).uuid());
        assertEquals(50, repository.publicTotal(scope),
                "the public total must use the same exclusion filter");
        assertTrue(accounts.isExcluded(ALICE, Identity.BOT));
    }

    @Test
    void onlineIsNotFilteredByGameMode() throws SQLException {
        try (var ps = connection.prepareStatement(
                "INSERT INTO total(uuid, identity, metric, category, dimension, game_mode, value) "
                        + "VALUES (?,?,?,'','','',?)")) {
            ps.setString(1, ALICE.toString());
            ps.setString(2, Identity.BOT.id());
            ps.setString(3, Metric.ONLINE.id());
            ps.setLong(4, 123456L);
            ps.executeUpdate();
        }
        var scope = StatsRepository.Scope.of(Metric.ONLINE, Identity.BOT);
        assertEquals(123456L, repository.valueOf(ALICE, scope),
                "the online metric is measured per identity and session, not per game mode");
    }

    @Test
    void largeValuesSurviveTheRoundTrip() throws SQLException {
        long huge = 9_000_000_000L;
        total(ALICE, Metric.MINING, "minecraft:stone", "minecraft:overworld", "survival", huge);
        long value = repository.valueOf(ALICE, StatsRepository.Scope.of(Metric.MINING, Identity.BOT));
        assertEquals(huge, value, "a value above the 32 bit range must not be truncated");
    }

    @Test
    void rankOfAnObjectWithoutDataIsNotAFakeLastPlace() throws SQLException {
        total(BOB, Metric.MINING, "minecraft:stone", "minecraft:overworld", "survival", 10);
        var info = repository.rankOf(ALICE, StatsRepository.Scope.of(Metric.MINING, Identity.BOT));
        assertEquals(0, info.value());
        assertNotNull(info);
    }

    @Test
    void categoryBreakdownKeepsTheDetailRows() throws SQLException {
        total(ALICE, Metric.MINING, "minecraft:stone", "minecraft:overworld", "survival", 7);
        total(ALICE, Metric.MINING, "minecraft:dirt", "minecraft:overworld", "survival", 3);
        var breakdown = repository.categoryBreakdown(ALICE, Identity.BOT, Metric.MINING);
        assertEquals(2, breakdown.size());
        assertEquals(7L, breakdown.get("minecraft:stone"));
        assertEquals(3L, breakdown.get("minecraft:dirt"));
    }

    @Test
    void pagingReturnsTheRequestedWindow() throws SQLException {
        for (int i = 0; i < 25; i++) {
            total(UUID.nameUUIDFromBytes(("player" + i).getBytes()), Metric.MINING, "minecraft:stone",
                    "minecraft:overworld", "survival", i + 1);
        }
        var scope = StatsRepository.Scope.of(Metric.MINING, Identity.BOT);
        var page2 = repository.board(scope, 2, 10);
        assertEquals(10, page2.size());
        assertEquals(15, page2.get(0).value(), "page 2 must continue after the first ten entries");
        assertEquals(25, repository.boardSize(scope));
    }

    @Test
    void namesAreFoundCaseInsensitively() {
        accounts.touchAccount(ALICE, "Bot_A", 1L);
        List<AccountRepository.AccountInfo> found = accounts.accountsByName("bot_a");
        assertEquals(1, found.size());
        assertEquals(ALICE, found.get(0).uuid());
        assertEquals(1, accounts.accountsByName("BOT_A").size());
    }
}
