package dev.themisstats.storage;

import dev.themisstats.identity.Identity;
import dev.themisstats.metric.Metric;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * All reads of the statistics database.
 *
 * <p>Every public number is computed with one single formula:</p>
 * <pre>public value = collected value of the matching cells + administrator adjustments of the same cells</pre>
 *
 * <p>The leaderboard, the totals overview, the gap to the neighbour and the export all use this
 * formula, so they can never disagree with each other.</p>
 */
public final class StatsRepository {

    /** A row of a leaderboard. */
    public record BoardEntry(UUID uuid, String name, long value, long rank) {
    }

    /** The result of a rank query. */
    public record RankInfo(UUID uuid, String name, long value, int rank, int totalRanked,
                           List<BoardEntry> above, List<BoardEntry> below) {
    }

    /** The filters that define which cells a public number is computed from. */
    public record Scope(Metric metric, Identity identity, List<String> gameModes) {

        public static Scope of(Metric metric, Identity identity) {
            if (metric.isModeClassified()) {
                // Behaviour metrics are public in survival + adventure by default.
                return new Scope(metric, identity, List.of("survival", "adventure"));
            }
            // `online` is measured per identity and session, not per game mode.
            return new Scope(metric, identity, List.of());
        }
    }

    private final Connection connection;

    public StatsRepository(Connection connection) {
        this.connection = connection;
    }

    // ------------------------------------------------------------------ leaderboard

    /**
     * @param page    1 based page number
     * @param perPage how many entries per page
     * @return the entries of the requested page, already ranked
     */
    public List<BoardEntry> board(Scope scope, int page, int perPage) {
        List<BoardEntry> all = ranked(scope);
        return page(all, page, perPage);
    }

    /**
     * @return every object with a positive public value, ordered and ranked with the competition
     *         ranking (1, 2, 2, 4)
     */
    public List<BoardEntry> ranked(Scope scope) {
        String sql = "SELECT uuid, name, public_value FROM (" + rankedSet(scope)
                + ") WHERE public_value > 0 ORDER BY public_value DESC, uuid ASC";
        List<BoardEntry> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            bindScope(ps, scope, 1);
            try (ResultSet rs = ps.executeQuery()) {
                long previousValue = Long.MIN_VALUE;
                long previousRank = 0;
                long position = 0;
                while (rs.next()) {
                    position++;
                    long value = rs.getLong(3);
                    long rank;
                    if (value == previousValue) {
                        rank = previousRank;
                    } else {
                        rank = position;
                        previousRank = rank;
                        previousValue = value;
                    }
                    result.add(new BoardEntry(UUID.fromString(rs.getString(1)), rs.getString(2), value, rank));
                }
            }
        } catch (SQLException e) {
            throw new StorageException("查询排行榜失败", e);
        }
        return result;
    }

    /** @return the number of objects with a positive public value for this scope */
    public int boardSize(Scope scope) {
        String sql = "SELECT COUNT(*) FROM (SELECT uuid, public_value FROM (" + rankedSet(scope)
                + ") WHERE public_value > 0)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            bindScope(ps, scope, 1);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new StorageException("统计排行榜人数失败", e);
        }
    }

    /** @return the public value of a single object */
    public long valueOf(UUID uuid, Scope scope) {
        String sql = "SELECT COALESCE(SUM(public_value), 0) FROM (" + rankedSet(scope) + ") WHERE uuid = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int index = bindScope(ps, scope, 1);
            ps.setString(index, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw new StorageException("查询累计值失败", e);
        }
    }

    /**
     * Computes the competition rank (1, 2, 2, 4) of an object and its neighbours.
     */
    public RankInfo rankOf(UUID uuid, Scope scope) {
        long value = valueOf(uuid, scope);
        // How many objects have a strictly higher public value? That is the rank minus one.
        String higherSql = "SELECT COUNT(*) FROM (SELECT uuid, public_value FROM (" + rankedSet(scope)
                + ") WHERE public_value > ?)";
        int higher;
        try (PreparedStatement ps = connection.prepareStatement(higherSql)) {
            int index = bindScope(ps, scope, 1);
            ps.setLong(index, value);
            try (ResultSet rs = ps.executeQuery()) {
                higher = rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new StorageException("查询名次失败", e);
        }
        int rank = higher + 1;
        int total = boardSize(scope);
        String name = nameOf(uuid);

        List<BoardEntry> above = List.of();
        List<BoardEntry> below = List.of();
        if (value > 0) {
            above = neighbours(scope, value, true);
            below = neighbours(scope, value, false);
        }
        return new RankInfo(uuid, name, value, rank, total, above, below);
    }

    private List<BoardEntry> neighbours(Scope scope, long value, boolean above) {
        String comparison = above ? ">" : "<";
        String order = above ? "DESC" : "ASC";
        String sql = "SELECT uuid, name, public_value FROM (" + rankedSet(scope)
                + ") WHERE public_value > 0 AND public_value " + comparison + " ? "
                + "ORDER BY public_value " + order + ", uuid ASC LIMIT 3";
        List<BoardEntry> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int index = bindScope(ps, scope, 1);
            ps.setLong(index, value);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new BoardEntry(UUID.fromString(rs.getString(1)), rs.getString(2),
                            rs.getLong(3), 0));
                }
            }
        } catch (SQLException e) {
            throw new StorageException("查询相邻名次失败", e);
        }
        return result;
    }

    /**
     * @return the sum of the public values of a whole identity group, used for the public overview
     */
    public long publicTotal(Scope scope) {
        String sql = "SELECT COALESCE(SUM(public_value), 0) FROM (SELECT uuid, public_value FROM ("
                + rankedSet(scope) + "))";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            bindScope(ps, scope, 1);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw new StorageException("查询全服总计失败", e);
        }
    }

    /**
     * @return the per category breakdown of one object, e.g. every block type it mined
     */
    public Map<String, Long> categoryBreakdown(UUID uuid, Identity identity, Metric metric) {
        String sql = "SELECT category, SUM(value) FROM total "
                + "WHERE uuid = ? AND identity = ? AND metric = ? AND category <> '' "
                + "GROUP BY category ORDER BY SUM(value) DESC";
        Map<String, Long> result = new LinkedHashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, identity.id());
            ps.setString(3, metric.id());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.put(rs.getString(1), rs.getLong(2));
                }
            }
        } catch (SQLException e) {
            throw new StorageException("查询细分类累计失败", e);
        }
        return result;
    }

    /** @return the total number of distinct objects that have any data for the metric */
    public int distinctObjects(Metric metric, Identity identity) {
        String sql = "SELECT COUNT(DISTINCT uuid) FROM total WHERE metric = ? AND identity = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, metric.id());
            ps.setString(2, identity.id());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new StorageException("查询对象数量失败", e);
        }
    }

    // ------------------------------------------------------------------ SQL building blocks

    /**
     * The public value of one cell: the collected value plus the adjustments of the same cell.
     */
    private String publicValueExpression() {
        return "t.value + COALESCE((SELECT SUM(ad.delta) FROM adjustment ad "
                + "WHERE ad.uuid = t.uuid AND ad.identity = t.identity AND ad.metric = t.metric "
                + "AND ad.category = t.category AND ad.dimension = t.dimension "
                + "AND ad.game_mode = t.game_mode), 0)";
    }

    /**
     * The common table expression that produces one row per object with its public value.
     * Every public query is built on top of it, so all of them share the same scope and the same
     * formula.
     */
    private String rankedSet(Scope scope) {
        StringBuilder sb = new StringBuilder();
        sb.append("SELECT t.uuid AS uuid, COALESCE(a.current_name, t.uuid) AS name, ");
        sb.append("SUM(").append(publicValueExpression()).append(") AS public_value ");
        sb.append("FROM total t LEFT JOIN account a ON a.uuid = t.uuid ");
        sb.append("WHERE ").append(boardWhere(scope));
        sb.append(" GROUP BY t.uuid");
        return sb.toString();
    }

    private String boardWhere(Scope scope) {
        StringBuilder sb = new StringBuilder("t.metric = ? AND t.identity = ?");
        if (!scope.gameModes().isEmpty()) {
            sb.append(" AND t.game_mode IN (");
            for (int i = 0; i < scope.gameModes().size(); i++) {
                sb.append(i == 0 ? "?" : ",?");
            }
            sb.append(')');
        }
        sb.append(" AND NOT EXISTS (SELECT 1 FROM exclusion x WHERE x.uuid = t.uuid "
                + "AND x.identity = t.identity AND x.excluded = 1)");
        return sb.toString();
    }

    private int bindScope(PreparedStatement ps, Scope scope, int index) throws SQLException {
        ps.setString(index++, scope.metric().id());
        ps.setString(index++, scope.identity().id());
        for (String mode : scope.gameModes()) {
            ps.setString(index++, mode);
        }
        return index;
    }

    private static <T> List<T> page(List<T> all, int page, int perPage) {
        if (page < 1) {
            page = 1;
        }
        int from = Math.min((page - 1) * perPage, all.size());
        int to = Math.min(from + perPage, all.size());
        return new ArrayList<>(all.subList(from, to));
    }

    private String nameOf(UUID uuid) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT current_name FROM account WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : uuid.toString();
            }
        } catch (SQLException e) {
            return uuid.toString();
        }
    }

    /** Thrown when a storage read fails. */
    public static final class StorageException extends RuntimeException {
        public StorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
