package dev.themisstats.storage;

import dev.themisstats.identity.Identity;
import dev.themisstats.session.SessionEndReason;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reads and writes everything that is not a metric total: accounts, sessions, exclusions,
 * administrator adjustments, bot profiles, display preferences and the audit log.
 */
public final class AccountRepository {

    /** A known account. */
    public record AccountInfo(UUID uuid, String currentName, long firstSeen, long lastSeen) {
    }

    /** A session row. */
    public record SessionInfo(String id, String bootId, UUID uuid, Identity identity, long startWall,
                               Long endWall, SessionEndReason endReason, long actualOnlineMs,
                               long collectedMs, String lastDimension, Double lastX, Double lastY,
                               Double lastZ, String ownerUuid, String ownerName, String purpose,
                               String note, boolean complete, String gapNote) {
    }

    /** The administrator profile of a bot like object. */
    public record BotProfile(UUID uuid, Identity identity, String ownerUuid, String ownerName,
                              String purpose, String note, long updatedWall) {
    }

    /** One administrator adjustment. */
    public record AdjustmentInfo(long id, UUID uuid, Identity identity, String metric, String category,
                                  String dimension, String gameMode, long delta, String reason,
                                  String operator, long createdWall) {
    }

    private final Connection connection;

    public AccountRepository(Connection connection) {
        this.connection = connection;
    }

    // ------------------------------------------------------------------ accounts

    /** Records that an account was seen, with the name it currently has. */
    public void touchAccount(UUID uuid, String name, long wallClockMillis) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO account(uuid, current_name, first_seen, last_seen) VALUES (?,?,?,?) "
                        + "ON CONFLICT(uuid) DO UPDATE SET current_name = excluded.current_name, "
                        + "last_seen = excluded.last_seen")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            ps.setLong(3, wallClockMillis);
            ps.setLong(4, wallClockMillis);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StatsRepository.StorageException("记录账号失败", e);
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO account_name(uuid, name, first_seen, last_seen) VALUES (?,?,?,?) "
                        + "ON CONFLICT(uuid, name) DO UPDATE SET last_seen = excluded.last_seen")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            ps.setLong(3, wallClockMillis);
            ps.setLong(4, wallClockMillis);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StatsRepository.StorageException("记录账号历史名称失败", e);
        }
    }

    /** @return every account that ever used the given name */
    public List<AccountInfo> accountsByName(String name) {
        String sql = "SELECT a.uuid, a.current_name, a.first_seen, a.last_seen FROM account_name n "
                + "JOIN account a ON a.uuid = n.uuid WHERE n.name = ? COLLATE NOCASE ORDER BY a.last_seen DESC";
        List<AccountInfo> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(readAccount(rs));
                }
            }
        } catch (SQLException e) {
            throw new StatsRepository.StorageException("按名称查询账号失败", e);
        }
        return result;
    }

    /** @return the account with the given uuid, or {@code null} */
    public AccountInfo accountByUuid(UUID uuid) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT uuid, current_name, first_seen, last_seen FROM account WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readAccount(rs) : null;
            }
        } catch (SQLException e) {
            throw new StatsRepository.StorageException("查询账号失败", e);
        }
    }

    /** @return all names this account was ever seen with */
    public List<String> namesOf(UUID uuid) {
        String sql = "SELECT name FROM account_name WHERE uuid = ? ORDER BY first_seen ASC";
        List<String> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            throw new StatsRepository.StorageException("查询历史名称失败", e);
        }
        return result;
    }

    private AccountInfo readAccount(ResultSet rs) throws SQLException {
        return new AccountInfo(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getLong(3), rs.getLong(4));
    }

    // ------------------------------------------------------------------ exclusions

    /** @return whether the given account + identity is excluded from public boards */
    public boolean isExcluded(UUID uuid, Identity identity) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT excluded FROM exclusion WHERE uuid = ? AND identity = ?")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, identity.id());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) == 1;
            }
        } catch (SQLException e) {
            throw new StatsRepository.StorageException("查询排除标记失败", e);
        }
    }

    /** @return the exclusion reason, or {@code null} when the object is not excluded */
    public String exclusionReason(UUID uuid, Identity identity) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT reason FROM exclusion WHERE uuid = ? AND identity = ? AND excluded = 1")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, identity.id());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new StatsRepository.StorageException("查询排除原因失败", e);
        }
    }

    public void setExclusion(UUID uuid, Identity identity, boolean excluded, String reason, String operator,
                             long wallClockMillis) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO exclusion(uuid, identity, excluded, reason, operator, created_wall) "
                        + "VALUES (?,?,?,?,?,?) "
                        + "ON CONFLICT(uuid, identity) DO UPDATE SET excluded = excluded.excluded, "
                        + "reason = excluded.reason, operator = excluded.operator, "
                        + "created_wall = excluded.created_wall")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, identity.id());
            ps.setInt(3, excluded ? 1 : 0);
            ps.setString(4, reason);
            ps.setString(5, operator);
            ps.setLong(6, wallClockMillis);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StatsRepository.StorageException("写入排除标记失败", e);
        }
    }

    /** @return all currently excluded objects */
    public List<String> listExclusions() {
        String sql = "SELECT e.uuid, COALESCE(a.current_name, e.uuid), e.identity, e.reason, e.operator, "
                + "e.created_wall FROM exclusion e LEFT JOIN account a ON a.uuid = e.uuid "
                + "WHERE e.excluded = 1 ORDER BY e.created_wall DESC";
        List<String> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(rs.getString(2) + " | " + rs.getString(3) + " | " + rs.getString(4)
                        + " | " + rs.getString(5) + " | " + new java.util.Date(rs.getLong(6)));
            }
        } catch (SQLException e) {
            throw new StatsRepository.StorageException("查询排除列表失败", e);
        }
        return result;
    }

    // ------------------------------------------------------------------ adjustments

    /**
     * Adds an administrator adjustment to a scope.
     *
     * @return the id of the new adjustment row
     */
    public long addAdjustment(UUID uuid, Identity identity, String metric, String category, String dimension,
                              String gameMode, long delta, String reason, String operator, long wallClockMillis) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO adjustment(uuid, identity, metric, category, dimension, game_mode, delta, reason, "
                        + "operator, created_wall) VALUES (?,?,?,?,?,?,?,?,?,?)",
                PreparedStatement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, identity.id());
            ps.setString(3, metric);
            ps.setString(4, category == null ? "" : category);
            ps.setString(5, dimension == null ? "" : dimension);
            ps.setString(6, gameMode == null ? "" : gameMode);
            ps.setLong(7, delta);
            ps.setString(8, reason);
            ps.setString(9, operator);
            ps.setLong(10, wallClockMillis);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw new StatsRepository.StorageException("写入数据修正失败", e);
        }
    }

    /** @return the adjustments of one scope */
    public List<AdjustmentInfo> adjustmentsOf(UUID uuid, Identity identity, String metric) {
        String sql = "SELECT id, uuid, identity, metric, category, dimension, game_mode, delta, reason, "
                + "operator, created_wall FROM adjustment WHERE uuid = ? AND identity = ? "
                + (metric == null ? "" : "AND metric = ? ") + "ORDER BY created_wall DESC";
        List<AdjustmentInfo> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int index = 1;
            ps.setString(index++, uuid.toString());
            ps.setString(index++, identity.id());
            if (metric != null) {
                ps.setString(index++, metric);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new AdjustmentInfo(rs.getLong(1), UUID.fromString(rs.getString(2)),
                            Identity.byId(rs.getString(3)), rs.getString(4), rs.getString(5), rs.getString(6),
                            rs.getString(7), rs.getLong(8), rs.getString(9), rs.getString(10), rs.getLong(11)));
                }
            }
        } catch (SQLException e) {
            throw new StatsRepository.StorageException("查询数据修正失败", e);
        }
        return result;
    }

    /** @return the sum of all adjustments of a scope */
    public long adjustmentSum(UUID uuid, Identity identity, String metric, String category, String dimension,
                              String gameMode) {
        String sql = "SELECT COALESCE(SUM(delta), 0) FROM adjustment WHERE uuid = ? AND identity = ? "
                + "AND metric = ? AND category = ? AND dimension = ? AND game_mode = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, identity.id());
            ps.setString(3, metric);
            ps.setString(4, category == null ? "" : category);
            ps.setString(5, dimension == null ? "" : dimension);
            ps.setString(6, gameMode == null ? "" : gameMode);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw new StatsRepository.StorageException("汇总数据修正失败", e);
        }
    }

    // ------------------------------------------------------------------ sessions

    /** @return the sessions of one account + identity, newest first */
    public List<SessionInfo> sessionsOf(UUID uuid, Identity identity, int page, int perPage) {
        String sql = "SELECT id, boot_id, uuid, identity, start_wall, end_wall, end_reason, "
                + "actual_online_ms, collected_ms, last_dimension, last_x, last_y, last_z, owner_uuid, "
                + "owner_name, purpose, note, complete, gap_note FROM session "
                + "WHERE uuid = ? AND identity = ? ORDER BY start_wall DESC, id DESC";
        List<SessionInfo> all = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, identity.id());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    all.add(readSession(rs));
                }
            }
        } catch (SQLException e) {
            throw new StatsRepository.StorageException("查询会话列表失败", e);
        }
        int from = Math.min(Math.max(0, page - 1) * perPage, all.size());
        int to = Math.min(from + perPage, all.size());
        return new ArrayList<>(all.subList(from, to));
    }

    /** @return the number of sessions of one account + identity */
    public int sessionCount(UUID uuid, Identity identity) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM session WHERE uuid = ? AND identity = ?")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, identity.id());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new StatsRepository.StorageException("统计会话数量失败", e);
        }
    }

    /** @return a single session */
    public SessionInfo session(String sessionId) {
        String sql = "SELECT id, boot_id, uuid, identity, start_wall, end_wall, end_reason, "
                + "actual_online_ms, collected_ms, last_dimension, last_x, last_y, last_z, owner_uuid, "
                + "owner_name, purpose, note, complete, gap_note FROM session WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readSession(rs) : null;
            }
        } catch (SQLException e) {
            throw new StatsRepository.StorageException("查询会话失败", e);
        }
    }

    /** @return the per metric totals of one session */
    public List<String> sessionMetrics(String sessionId) {
        String sql = "SELECT metric, category, value FROM session_metric WHERE session_id = ? "
                + "AND value > 0 ORDER BY metric ASC, value DESC";
        List<String> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getLong(3));
                }
            }
        } catch (SQLException e) {
            throw new StatsRepository.StorageException("查询会话统计失败", e);
        }
        return result;
    }

    /**
     * @return the sessions that were still open when the server stopped unexpectedly
     */
    public List<SessionInfo> unfinishedSessions(String bootId) {
        String sql = "SELECT id, boot_id, uuid, identity, start_wall, end_wall, end_reason, "
                + "actual_online_ms, collected_ms, last_dimension, last_x, last_y, last_z, owner_uuid, "
                + "owner_name, purpose, note, complete, gap_note FROM session "
                + "WHERE end_wall IS NULL AND boot_id <> ? ORDER BY start_wall ASC";
        List<SessionInfo> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, bootId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(readSession(rs));
                }
            }
        } catch (SQLException e) {
            throw new StatsRepository.StorageException("查询遗留会话失败", e);
        }
        return result;
    }

    private SessionInfo readSession(ResultSet rs) throws SQLException {
        long endWall = rs.getLong(6);
        Long end = rs.wasNull() ? null : endWall;
        double lastX = rs.getDouble(11);
        Double lx = rs.wasNull() ? null : lastX;
        double lastY = rs.getDouble(12);
        Double ly = rs.wasNull() ? null : lastY;
        double lastZ = rs.getDouble(13);
        Double lz = rs.wasNull() ? null : lastZ;
        return new SessionInfo(rs.getString(1), rs.getString(2), UUID.fromString(rs.getString(3)),
                Identity.byId(rs.getString(4)), rs.getLong(5), end, SessionEndReason.byId(rs.getString(7)),
                rs.getLong(8), rs.getLong(9), rs.getString(10), lx, ly, lz, rs.getString(14), rs.getString(15),
                rs.getString(16), rs.getString(17), rs.getInt(18) == 1, rs.getString(19));
    }

    // ------------------------------------------------------------------ bot profiles

    public BotProfile botProfile(UUID uuid, Identity identity) {
        String sql = "SELECT uuid, identity, owner_uuid, owner_name, purpose, note, updated_wall "
                + "FROM bot_profile WHERE uuid = ? AND identity = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, identity.id());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new BotProfile(UUID.fromString(rs.getString(1)), Identity.byId(rs.getString(2)),
                        rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getLong(7));
            }
        } catch (SQLException e) {
            throw new StatsRepository.StorageException("查询假人档案失败", e);
        }
    }

    /** @return all bot profiles of the given identity */
    public List<BotProfile> botProfiles(Identity identity) {
        String sql = "SELECT uuid, identity, owner_uuid, owner_name, purpose, note, updated_wall "
                + "FROM bot_profile WHERE identity = ? ORDER BY updated_wall DESC";
        List<BotProfile> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, identity.id());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new BotProfile(UUID.fromString(rs.getString(1)), Identity.byId(rs.getString(2)),
                            rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getLong(7)));
                }
            }
        } catch (SQLException e) {
            throw new StatsRepository.StorageException("查询假人档案列表失败", e);
        }
        return result;
    }

    public void saveBotProfile(UUID uuid, Identity identity, String ownerUuid, String ownerName, String purpose,
                                String note, long wallClockMillis) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO bot_profile(uuid, identity, owner_uuid, owner_name, purpose, note, updated_wall) "
                        + "VALUES (?,?,?,?,?,?,?) "
                        + "ON CONFLICT(uuid, identity) DO UPDATE SET owner_uuid = excluded.owner_uuid, "
                        + "owner_name = excluded.owner_name, purpose = excluded.purpose, note = excluded.note, "
                        + "updated_wall = excluded.updated_wall")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, identity.id());
            ps.setString(3, ownerUuid);
            ps.setString(4, ownerName);
            ps.setString(5, purpose);
            ps.setString(6, note);
            ps.setLong(7, wallClockMillis);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StatsRepository.StorageException("保存假人档案失败", e);
        }
    }

    public void recordOwnerChange(UUID uuid, Identity identity, String ownerUuid, String ownerName,
                                   String operator, long wallClockMillis) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO bot_owner_history(uuid, identity, owner_uuid, owner_name, changed_wall, operator) "
                        + "VALUES (?,?,?,?,?,?)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, identity.id());
            ps.setString(3, ownerUuid);
            ps.setString(4, ownerName);
            ps.setLong(5, wallClockMillis);
            ps.setString(6, operator);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StatsRepository.StorageException("记录负责人变更失败", e);
        }
    }

    public void addTag(UUID uuid, Identity identity, String tag) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR IGNORE INTO bot_tag(uuid, identity, tag) VALUES (?,?,?)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, identity.id());
            ps.setString(3, tag);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StatsRepository.StorageException("添加标签失败", e);
        }
    }

    public void removeTag(UUID uuid, Identity identity, String tag) {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM bot_tag WHERE uuid = ? AND identity = ? AND tag = ?")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, identity.id());
            ps.setString(3, tag);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StatsRepository.StorageException("删除标签失败", e);
        }
    }

    public List<String> tagsOf(UUID uuid, Identity identity) {
        String sql = "SELECT tag FROM bot_tag WHERE uuid = ? AND identity = ? ORDER BY tag";
        List<String> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, identity.id());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            throw new StatsRepository.StorageException("查询标签失败", e);
        }
        return result;
    }

    // ------------------------------------------------------------------ display preferences

    /** The personal sidebar preference of a player. */
    public record DisplayPreference(String mode, String metric, String identityGroup) {
    }


    public void saveDisplayPreference(UUID uuid, String mode, String metric, String identityGroup,
                                      long wallClockMillis) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO display_pref(uuid, mode, metric, identity_group, updated_wall) VALUES (?,?,?,?,?) "
                        + "ON CONFLICT(uuid) DO UPDATE SET mode = excluded.mode, metric = excluded.metric, "
                        + "identity_group = excluded.identity_group, updated_wall = excluded.updated_wall")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, mode);
            ps.setString(3, metric);
            ps.setString(4, identityGroup);
            ps.setLong(5, wallClockMillis);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StatsRepository.StorageException("保存显示偏好失败", e);
        }
    }

    /**
     * @return all personal display preferences, for the in memory cache of the sidebar
     */
    public Map<UUID, DisplayPreference> allDisplayPreferences() {
        String sql = "SELECT uuid, mode, metric, identity_group FROM display_pref";
        Map<UUID, DisplayPreference> result = new java.util.LinkedHashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.put(UUID.fromString(rs.getString(1)),
                        new DisplayPreference(rs.getString(2), rs.getString(3), rs.getString(4)));
            }
        } catch (SQLException e) {
            throw new StatsRepository.StorageException("读取显示偏好失败", e);
        }
        return result;
    }

    // ------------------------------------------------------------------ audit

    public void audit(String action, String target, String detail, String operator, long wallClockMillis) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO audit(action, target, detail, operator, created_wall) VALUES (?,?,?,?,?)")) {
            ps.setString(1, action);
            ps.setString(2, target);
            ps.setString(3, detail);
            ps.setString(4, operator);
            ps.setLong(5, wallClockMillis);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StatsRepository.StorageException("写入审计日志失败", e);
        }
    }

    public List<String> auditEntries(int page, int perPage) {
        String sql = "SELECT created_wall, operator, action, target, detail FROM audit "
                + "ORDER BY created_wall DESC, id DESC";
        List<String> all = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                all.add(new java.util.Date(rs.getLong(1)) + " | " + rs.getString(2) + " | " + rs.getString(3)
                        + " | " + (rs.getString(4) == null ? "-" : rs.getString(4)) + " | "
                        + (rs.getString(5) == null ? "" : rs.getString(5)));
            }
        } catch (SQLException e) {
            throw new StatsRepository.StorageException("查询审计日志失败", e);
        }
        int from = Math.min(Math.max(0, page - 1) * perPage, all.size());
        int to = Math.min(from + perPage, all.size());
        return new ArrayList<>(all.subList(from, to));
    }

    public int auditCount() {
        try (PreparedStatement ps = connection.prepareStatement("SELECT COUNT(*) FROM audit");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            return 0;
        }
    }
}
