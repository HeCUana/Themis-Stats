package dev.themisstats.storage;

import dev.themisstats.identity.Identity;
import dev.themisstats.metric.Metric;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Exports the statistics as CSV or JSON.
 *
 * <p>The export runs in the background on immutable data that was read from the database; it never
 * walks the world or touches live player objects.</p>
 *
 * <p>Every export carries the identity group, the unit, the scope, the caliber version and the
 * snapshot time, and it always contains the <em>full</em> 64 bit values - never an abbreviated
 * display form.</p>
 */
public final class Exporter {

    private static final DateTimeFormatter STAMP = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss").withZone(java.time.ZoneOffset.UTC);

    /** The scope of an export. */
    public record ExportScope(String description, Metric metric, Identity identity, List<String> gameModes,
                              boolean includeImported) {

        public ExportScope {
            gameModes = List.copyOf(gameModes);
        }

        /** A scope that exports what this mod collected itself. */
        public ExportScope(String description, Metric metric, Identity identity, List<String> gameModes) {
            this(description, metric, identity, gameModes, false);
        }
    }

    /** The result of an export. */
    public record ExportResult(boolean success, String fileName, int rows, String message) {
    }

    private final Connection connection;
    private final Path exportDirectory;

    public Exporter(Connection connection, Path exportDirectory) {
        this.connection = connection;
        this.exportDirectory = exportDirectory;
    }

    /**
     * @param format {@code csv} or {@code json}
     */
    public ExportResult export(String format, ExportScope scope, String operator) {
        try {
            Files.createDirectories(exportDirectory);
        } catch (IOException e) {
            return new ExportResult(false, null, 0, "无法创建导出目录：" + e.getMessage());
        }
        List<Row> rows = readRows(scope);
        String stamp = STAMP.format(Instant.now());
        boolean csv = "csv".equalsIgnoreCase(format);
        String fileName = "themisstats-" + scope.metric().id() + "-" + scope.identity().id() + "-"
                + stamp + (csv ? ".csv" : ".json");
        Path target = exportDirectory.resolve(fileName);
        try (BufferedWriter writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
            if (csv) {
                writeCsv(writer, scope, rows, operator);
            } else {
                writeJson(writer, scope, rows, operator);
            }
        } catch (IOException e) {
            return new ExportResult(false, null, 0, "导出失败：" + e.getMessage());
        }
        return new ExportResult(true, fileName, rows.size(),
                "导出完成：" + fileName + "（" + rows.size() + " 行，口径版本 "
                        + Schema.VERSION + "，快照时间 " + Instant.now() + "）");
    }

    /** One exported row. */
    private record Row(String uuid, String name, Identity identity, Metric metric, String category,
                       String dimension, String gameMode, long value) {
    }

    private List<Row> readRows(ExportScope scope) {
        StringBuilder sql = new StringBuilder(
                "SELECT t.uuid, COALESCE(a.current_name, t.uuid), t.identity, t.metric, t.category, "
                        + "t.dimension, t.game_mode, SUM(t.value + COALESCE((SELECT SUM(ad.delta) "
                        + "FROM adjustment ad WHERE ad.uuid = t.uuid AND ad.identity = t.identity "
                        + "AND ad.metric = t.metric AND ad.category = t.category "
                        + "AND ad.dimension = t.dimension AND ad.game_mode = t.game_mode), 0)) AS public_value "
                        + "FROM total t LEFT JOIN account a ON a.uuid = t.uuid "
                        + "WHERE t.metric = ? AND t.identity = ?");
        List<Object> parameters = new ArrayList<>();
        parameters.add(scope.metric().id());
        parameters.add(scope.identity().id());
        if (!scope.includeImported()) {
            // The export follows the public leaderboard: the explicitly imported historical data
            // is only part of it when the scope asks for it.
            sql.append(" AND t.source = 'collected'");
        }
        if (!scope.gameModes().isEmpty()) {
            sql.append(" AND t.game_mode IN (");
            for (int i = 0; i < scope.gameModes().size(); i++) {
                sql.append(i == 0 ? "?" : ",?");
                parameters.add(scope.gameModes().get(i));
            }
            sql.append(')');
        }
        sql.append(" GROUP BY t.uuid, t.category, t.dimension, t.game_mode "
                + "ORDER BY public_value DESC, t.uuid ASC");

        List<Row> rows = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < parameters.size(); i++) {
                ps.setString(i + 1, String.valueOf(parameters.get(i)));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new Row(rs.getString(1), rs.getString(2), Identity.byId(rs.getString(3)),
                            Metric.byId(rs.getString(4)), rs.getString(5), rs.getString(6), rs.getString(7),
                            rs.getLong(8)));
                }
            }
        } catch (SQLException e) {
            throw new StatsRepository.StorageException("导出查询失败", e);
        }
        return rows;
    }

    private void writeCsv(BufferedWriter writer, ExportScope scope, List<Row> rows, String operator)
            throws IOException {
        // A leading apostrophe protects values that a spreadsheet would otherwise interpret as a
        // formula. Numbers stay numbers, text is quoted and escaped.
        writer.write("# Themis Stats 导出\n");
        writer.write("# 指标=" + csv(scope.metric().id()) + "; 身份组=" + csv(scope.identity().id())
                + "; 单位=" + csv(scope.metric().unit().display())
                + "; 范围=" + csv(scope.description())
                + "; 口径版本=" + Schema.VERSION
                + "; 快照时间=" + Instant.now() + "; 导出者=" + csv(operator) + "\n");
        writer.write("uuid,name,identity,metric,category,dimension,game_mode,value\n");
        for (Row row : rows) {
            writer.write(csv(row.uuid()));
            writer.write(',');
            writer.write(csv(row.name()));
            writer.write(',');
            writer.write(csv(row.identity().id()));
            writer.write(',');
            writer.write(csv(row.metric().id()));
            writer.write(',');
            writer.write(csv(row.category()));
            writer.write(',');
            writer.write(csv(row.dimension()));
            writer.write(',');
            writer.write(csv(row.gameMode()));
            writer.write(',');
            writer.write(Long.toString(row.value()));
            writer.write('\n');
        }
    }

    private void writeJson(BufferedWriter writer, ExportScope scope, List<Row> rows, String operator)
            throws IOException {
        writer.write("{\n");
        writer.write("  \"project\": \"Themis Stats\",\n");
        writer.write("  \"metric\": \"" + json(scope.metric().id()) + "\",\n");
        writer.write("  \"identity\": \"" + json(scope.identity().id()) + "\",\n");
        writer.write("  \"unit\": \"" + json(scope.metric().unit().display()) + "\",\n");
        writer.write("  \"scope\": \"" + json(scope.description()) + "\",\n");
        writer.write("  \"caliberVersion\": " + Schema.VERSION + ",\n");
        writer.write("  \"snapshotTime\": \"" + Instant.now() + "\",\n");
        writer.write("  \"exportedBy\": \"" + json(operator) + "\",\n");
        writer.write("  \"rows\": [\n");
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            writer.write("    {\"uuid\": \"" + json(row.uuid()) + "\", \"name\": \"" + json(row.name())
                    + "\", \"identity\": \"" + json(row.identity().id())
                    + "\", \"metric\": \"" + json(row.metric().id())
                    + "\", \"category\": \"" + json(row.category())
                    + "\", \"dimension\": \"" + json(row.dimension())
                    + "\", \"gameMode\": \"" + json(row.gameMode())
                    + "\", \"value\": " + row.value() + "}");
            writer.write(i == rows.size() - 1 ? "\n" : ",\n");
        }
        writer.write("  ]\n}\n");
    }

    /**
     * Escapes a value for CSV. A value that starts with a character a spreadsheet would treat as a
     * formula is prefixed with an apostrophe so that it stays text.
     */
    static String csv(String raw) {
        String value = raw == null ? "" : raw;
        if (!value.isEmpty() && "=+-@\t\r".indexOf(value.charAt(0)) >= 0) {
            value = "'" + value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    static String json(String raw) {
        String value = raw == null ? "" : raw;
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
