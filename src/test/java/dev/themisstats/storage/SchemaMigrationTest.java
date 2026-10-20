package dev.themisstats.storage;

import dev.themisstats.identity.Identity;
import dev.themisstats.metric.Metric;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests of the schema migrations.
 *
 * <p>A migration has to keep the data of an existing world. The tests therefore build the database
 * step by step, put data in it and then apply the next migration, exactly like a server that is
 * updated does.</p>
 */
class SchemaMigrationTest {

    private static final UUID BOT_A = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    @Test
    void theCurrentVersionMatchesTheNumberOfMigrations() {
        assertEquals(Schema.migrations().length, Schema.VERSION);
    }

    @Test
    void theOriginBecomesPartOfTheCellInVersionSix() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        try {
            String[] migrations = Schema.migrations();
            // Build the database up to version 5.
            for (int i = 0; i < 5; i++) {
                apply(connection, migrations[i]);
            }
            try (var ps = connection.prepareStatement(
                    "INSERT INTO total(uuid, identity, metric, category, dimension, game_mode, value) "
                            + "VALUES (?,?,?,?,?,?,?)")) {
                ps.setString(1, BOT_A.toString());
                ps.setString(2, Identity.BOT.id());
                ps.setString(3, Metric.MINING.id());
                ps.setString(4, "minecraft:stone");
                ps.setString(5, "minecraft:overworld");
                ps.setString(6, "survival");
                ps.setLong(7, 77L);
                ps.executeUpdate();
            }
            // Now the migration to version 6.
            apply(connection, migrations[5]);

            var repository = new StatsRepository(connection);
            var scope = StatsRepository.Scope.of(Metric.MINING, Identity.BOT, java.util.List.of("survival"));
            // The collected value survived the migration.
            assertEquals(77L, repository.valueOf(BOT_A, scope));
            try (Statement statement = connection.createStatement();
                 var rs = statement.executeQuery("SELECT source FROM total WHERE uuid = '"
                         + BOT_A + "'")) {
                assertTrue(rs.next());
                assertEquals("collected", rs.getString(1));
            }
        } finally {
            connection.close();
        }
    }

    @Test
    void theOriginIsPartOfThePrimaryKeyInVersionSix() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        try {
            String[] migrations = Schema.migrations();
            for (String migration : migrations) {
                apply(connection, migration);
            }
            // The same cell can hold a collected value and an imported value at the same time.
            insert(connection, "collected", 10L);
            insert(connection, "imported", 50L);
            var repository = new StatsRepository(connection);
            var scope = StatsRepository.Scope.of(Metric.MINING, Identity.BOT, java.util.List.of());
            assertEquals(10L, repository.valueOf(BOT_A, scope));
            assertEquals(60L, repository.valueOf(BOT_A, scope.withImported()));
            assertEquals(1, repository.importedRanked(scope).size());
            assertEquals(50L, repository.importedRanked(scope).get(0).value());
        } finally {
            connection.close();
        }
    }

    private void insert(Connection connection, String source, long value) throws SQLException {
        try (var ps = connection.prepareStatement(
                "INSERT INTO total(uuid, identity, metric, category, dimension, game_mode, value, source) "
                        + "VALUES (?,?,?,?,?,?,?,?)")) {
            ps.setString(1, BOT_A.toString());
            ps.setString(2, Identity.BOT.id());
            ps.setString(3, Metric.MINING.id());
            ps.setString(4, "minecraft:stone");
            ps.setString(5, "minecraft:overworld");
            ps.setString(6, "survival");
            ps.setLong(7, value);
            ps.setString(8, source);
            ps.executeUpdate();
        }
    }

    private void apply(Connection connection, String ddl) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(ddl);
        }
    }
}
