package dev.themisstats.storage;

import dev.themisstats.identity.Identity;
import dev.themisstats.metric.Metric;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The explicit import of historical data.
 *
 * <p>The import is <strong>never</strong> automatic. An administrator has to point at a file, and
 * the imported rows are marked with the origin {@code imported}, so they can never be mixed up
 * with what this mod collected itself.</p>
 *
 * <p>What the import honestly is:</p>
 * <ul>
 *   <li>a way to bring a summary of an earlier period into the database;</li>
 *   <li><b>not</b> a way to restore what real and fake players did in the past. The file only
 *       carries what the operator put into it, and the rows stay in the unclassified origin.</li>
 * </ul>
 */
public final class ImportService {

    /** The origin of the rows this mod collected itself. */
    public static final String SOURCE_COLLECTED = "collected";

    /** The origin of the rows an administrator imported. */
    public static final String SOURCE_IMPORTED = "imported";

    /** One imported row. */
    public record Row(UUID uuid, String name, String identity, String metric, String category,
                      String dimension, String gameMode, long value) {
    }

    /** The result of an import. */
    public record Result(int imported, int skipped, List<String> problems) {
    }

    private final Connection connection;

    public ImportService(Connection connection) {
        this.connection = connection;
    }

    /**
     * Reads a CSV file and imports it.
     *
     * <p>The expected columns are: {@code uuid,name,identity,metric,category,dimension,gameMode,value}.
     * Lines that cannot be understood are skipped and reported; they never abort the import.</p>
     */
    public Result importCsv(Path file, String operator) {
        List<String> problems = new ArrayList<>();
        List<Row> rows = new ArrayList<>();
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return new Result(0, 1, List.of("无法读取文件：" + e.getMessage()));
        }
        int lineNumber = 0;
        int dataRows = 0;
        for (String line : lines) {
            lineNumber++;
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                // An empty line and a comment are not data rows, so they are not counted at all.
                continue;
            }
            dataRows++;
            String[] parts = trimmed.split(",", -1);
            if (parts.length < 8) {
                problems.add("第 " + lineNumber + " 行：列数不足（需要 8 列）");
                continue;
            }
            try {
                UUID uuid = UUID.fromString(parts[0].trim());
                String name = parts[1].trim();
                Identity identity = Identity.byId(parts[2].trim());
                Metric metric = Metric.byId(parts[3].trim());
                if (metric == null) {
                    problems.add("第 " + lineNumber + " 行：未知指标 " + parts[3].trim());
                    continue;
                }
                long value = Long.parseLong(parts[7].trim());
                if (value < 0) {
                    problems.add("第 " + lineNumber + " 行：数值为负");
                    continue;
                }
                rows.add(new Row(uuid, name, identity.id(), metric.id(), parts[4].trim(),
                        parts[5].trim(), parts[6].trim(), value));
            } catch (IllegalArgumentException e) {
                problems.add("第 " + lineNumber + " 行：无法解析（" + e.getMessage() + "）");
            }
        }
        int imported = 0;
        for (Row row : rows) {
            if (writeRow(row)) {
                imported++;
            } else {
                problems.add("写入失败：" + row.uuid() + " " + row.metric());
            }
        }
        // Only the data rows count as skipped, not the empty lines and not the comments.
        return new Result(imported, Math.max(0, dataRows - imported), problems);
    }

    private boolean writeRow(Row row) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO total(uuid, identity, metric, category, dimension, game_mode, value, source) "
                        + "VALUES (?,?,?,?,?,?,?,?) "
                        + "ON CONFLICT(uuid, identity, metric, category, dimension, game_mode, source) "
                        + "DO UPDATE SET value = value + excluded.value")) {
            ps.setString(1, row.uuid().toString());
            ps.setString(2, row.identity());
            ps.setString(3, row.metric());
            ps.setString(4, row.category());
            ps.setString(5, row.dimension());
            ps.setString(6, row.gameMode());
            ps.setLong(7, row.value());
            ps.setString(8, SOURCE_IMPORTED);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * Imports a single value for one object, used by the project meter.
     */
    public void addImported(UUID uuid, Identity identity, Metric metric, String category, String dimension,
                            String gameMode, long value) {
        writeRow(new Row(uuid, "", identity.id(), metric.id(), category, dimension, gameMode, value));
    }
}
