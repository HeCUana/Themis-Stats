package dev.themisstats.storage;

import dev.themisstats.ThemisStats;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

/**
 * Creates consistent backups of the statistics database.
 *
 * <p>The backup is made with SQLite's {@code VACUUM INTO}, which produces a consistent snapshot of
 * a <em>running</em> database including everything that is committed in the WAL. Copying the main
 * database file while the server is running would not be a valid backup, because the WAL may still
 * hold committed pages.</p>
 *
 * <p>A backup covers the statistics of this mod only. It is <b>not</b> a world backup: the world,
 * the player inventories and the Carpet configuration have to be backed up separately by the
 * server owner.</p>
 */
public final class BackupService {

    private static final DateTimeFormatter STAMP = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss").withZone(java.time.ZoneOffset.UTC);

    private final Connection connection;
    private final Path dataDirectory;

    public BackupService(Connection connection, Path dataDirectory) {
        this.connection = connection;
        this.dataDirectory = dataDirectory;
    }

    /** The result of a backup run. */
    public record BackupResult(boolean success, String fileName, long sizeBytes, String message) {
    }

    /**
     * Creates a consistent snapshot of the statistics database.
     *
     * @param includePendingIncrements whether the snapshot is taken after the pending batches have
     *                                  been committed. When {@code false}, the snapshot contains
     *                                  only what was already committed.
     */
    public BackupResult create(String reason, boolean includePendingIncrements) {
        String fileName = "stats-backup-" + STAMP.format(Instant.now()) + ".db";
        Path target = dataDirectory.resolve("backups").resolve(fileName);
        try {
            Files.createDirectories(target.getParent());
            if (Files.exists(target)) {
                Files.delete(target);
            }
            // VACUUM INTO takes a consistent snapshot of the live database, WAL included.
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("VACUUM INTO '" + target.toAbsolutePath().toString().replace("'", "''") + "'");
            }
            long size = Files.size(target);
            ThemisStats.LOGGER.info("[ThemisStats] 已创建统计数据库备份 {}（{} 字节，{}）", fileName, size, reason);
            return new BackupResult(true, fileName, size,
                    "备份完成：" + fileName + "（仅包含 Themis Stats 统计数据，不含世界与玩家数据）");
        } catch (SQLException | IOException e) {
            ThemisStats.LOGGER.error("[ThemisStats] 统计数据库备份失败", e);
            return new BackupResult(false, null, 0, "备份失败：" + e.getMessage());
        }
    }

    /** @return the backup directory */
    public Path backupDirectory() {
        return dataDirectory.resolve("backups");
    }
}
