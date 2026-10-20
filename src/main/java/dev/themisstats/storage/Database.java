package dev.themisstats.storage;

import dev.themisstats.ThemisStats;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/**
 * The SQLite database of a single world.
 *
 * <p>The database lives in {@code <world>/themisstats/stats.db}. Each world therefore keeps its
 * own statistics; nothing is mixed into a global working directory database.</p>
 *
 * <p>Opening the database is guarded: if the file is corrupt or was written by a newer schema
 * version, the statistics module is disabled with a diagnostic instead of overwriting the existing
 * data with an empty database.</p>
 */
public final class Database implements AutoCloseable {

    private final Path dataDirectory;
    private final Path databaseFile;
    private final Connection connection;
    private final int schemaVersion;

    private Database(Path dataDirectory, Path databaseFile, Connection connection, int schemaVersion) {
        this.dataDirectory = dataDirectory;
        this.databaseFile = databaseFile;
        this.connection = connection;
        this.schemaVersion = schemaVersion;
    }

    /**
     * The exception thrown when the database cannot be used. The caller must disable the
     * statistics module but keep the server running.
     */
    public static final class DatabaseUnavailableException extends Exception {
        public DatabaseUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }

        public DatabaseUnavailableException(String message) {
            super(message);
        }
    }

    /**
     * Opens (and if needed creates and migrates) the statistics database of a world.
     *
     * @param worldDirectory the world root directory
     * @throws DatabaseUnavailableException if the database is unusable and must not be overwritten
     */
    public static Database open(Path worldDirectory) throws DatabaseUnavailableException {
        Path dataDirectory = worldDirectory.resolve("themisstats");
        Path databaseFile = dataDirectory.resolve("stats.db");
        try {
            Files.createDirectories(dataDirectory);
        } catch (IOException e) {
            throw new DatabaseUnavailableException("无法创建统计数据目录: " + dataDirectory, e);
        }

        // A pre-existing database that cannot be opened must never be replaced by an empty one.
        try {
            if (Files.exists(databaseFile) && Files.size(databaseFile) == 0) {
                // A zero length file is a leftover of an interrupted create; it carries no data.
                Files.deleteIfExists(databaseFile);
            }
        } catch (IOException e) {
            throw new DatabaseUnavailableException("无法检查统计数据库文件: " + databaseFile, e);
        }

        Connection connection;
        try {
            Properties props = new Properties();
            props.setProperty("journal_mode", "WAL");
            props.setProperty("synchronous", "NORMAL");
            props.setProperty("busy_timeout", "5000");
            props.setProperty("foreign_keys", "TRUE");
            connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.toAbsolutePath(), props);
            connection.setAutoCommit(true);
        } catch (SQLException e) {
            throw new DatabaseUnavailableException("无法打开统计数据库: " + databaseFile
                    + "（可能是数据库损坏或版本过新，统计模块已停用，原库未被修改）", e);
        }

        int version;
        try {
            version = migrate(connection, databaseFile);
        } catch (SQLException e) {
            closeQuietly(connection);
            throw new DatabaseUnavailableException("统计数据库迁移失败，原库未被修改: " + databaseFile, e);
        }
        return new Database(dataDirectory, databaseFile, connection, version);
    }

    /**
     * Applies the pending migrations.
     *
     * @return the resulting schema version
     */
    private static int migrate(Connection connection, Path databaseFile) throws SQLException {
        int current = readSchemaVersion(connection);
        if (current > Schema.VERSION) {
            throw new SQLException("数据库版本 " + current + " 高于本版本支持的 " + Schema.VERSION
                    + "，拒绝写入以免破坏数据");
        }
        if (current == Schema.VERSION) {
            return current;
        }
        // Back up before touching the schema.
        backupBeforeMigration(connection, databaseFile, current);

        String[] migrations = Schema.migrations();
        for (int target = current + 1; target <= migrations.length; target++) {
            String ddl = migrations[target - 1];
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(ddl);
                statement.executeUpdate("INSERT OR REPLACE INTO meta(key, value) VALUES('schema_version', '"
                        + target + "')");
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
            ThemisStats.LOGGER.info("[ThemisStats] 数据库迁移完成，版本 {}", target);
        }
        return Schema.VERSION;
    }

    private static int readSchemaVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            try (ResultSet rs = statement.executeQuery(
                    "SELECT value FROM meta WHERE key = 'schema_version'")) {
                if (rs.next()) {
                    return Integer.parseInt(rs.getString(1));
                }
            } catch (SQLException e) {
                // The meta table does not exist yet => fresh database.
                return 0;
            }
        }
        return 0;
    }

    private static void backupBeforeMigration(Connection connection, Path databaseFile, int currentVersion)
            throws SQLException {
        try {
            Path backup = databaseFile.resolveSibling("stats.db.v" + currentVersion + ".bak");
            Files.deleteIfExists(backup);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("VACUUM INTO '" + backup.toAbsolutePath().toString().replace("'", "''") + "'");
            }
            ThemisStats.LOGGER.info("[ThemisStats] 迁移前已创建数据库备份: {}", backup);
        } catch (SQLException | IOException e) {
            throw new SQLException("迁移前备份失败，已中止迁移: " + e.getMessage(), e);
        }
    }

    public Connection connection() {
        return connection;
    }

    public Path dataDirectory() {
        return dataDirectory;
    }

    public Path databaseFile() {
        return databaseFile;
    }

    public int schemaVersion() {
        return schemaVersion;
    }

    @Override
    public void close() {
        closeQuietly(connection);
    }

    static void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            if (!connection.isClosed()) {
                try (Statement statement = connection.createStatement()) {
                    // Fold the WAL back into the main file so that a file copy is a complete database.
                    statement.executeUpdate("PRAGMA wal_checkpoint(TRUNCATE)");
                } catch (SQLException ignored) {
                    // best effort
                }
                connection.close();
            }
        } catch (SQLException ignored) {
            // closing must never throw
        }
    }
}
