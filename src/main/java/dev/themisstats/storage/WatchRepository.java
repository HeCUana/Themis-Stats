package dev.themisstats.storage;

import dev.themisstats.identity.Identity;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The run alerts: the subscriptions, the alert state for the cooldown and the recovery notices,
 * the observation windows of the "no progress" alert and the summary preference of the owners.
 */
public final class WatchRepository {

    /** One subscription of one subscriber to one object. */
    public record Subscription(UUID target, Identity identity, UUID subscriber, int alerts,
                               double lowHealth, double lowDurability, String stallMetric, long createdWall) {
    }

    /** The state of one alert kind of one object, used for the cooldown and the recovery notice. */
    public record AlertState(UUID target, Identity identity, String reason, boolean active,
                             Long lastFiredWall, Long lastRecoveredWall) {
    }

    /** The observation window of the "no progress" alert. */
    public record StallWindow(UUID target, Identity identity, String metric, long windowStartWall,
                              long lastValue) {
    }

    private final Connection connection;

    public WatchRepository(Connection connection) {
        this.connection = connection;
    }

    // ------------------------------------------------------------------ subscriptions

    /**
     * Creates or updates a subscription.
     */
    public void subscribe(Subscription subscription) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO watch_subscription(target_uuid, target_identity, subscriber_uuid, alerts, "
                        + "low_health, low_durability, stall_metric, created_wall) VALUES (?,?,?,?,?,?,?,?) "
                        + "ON CONFLICT(target_uuid, target_identity, subscriber_uuid) DO UPDATE SET "
                        + "alerts = excluded.alerts, low_health = excluded.low_health, "
                        + "low_durability = excluded.low_durability, stall_metric = excluded.stall_metric")) {
            ps.setString(1, subscription.target().toString());
            ps.setString(2, subscription.identity().id());
            ps.setString(3, subscription.subscriber().toString());
            ps.setInt(4, subscription.alerts());
            ps.setDouble(5, subscription.lowHealth());
            ps.setDouble(6, subscription.lowDurability());
            ps.setString(7, subscription.stallMetric());
            ps.setLong(8, subscription.createdWall());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StatsRepository.StorageException("保存关注订阅失败", e);
        }
    }

    /** Removes a subscription. */
    public void unsubscribe(UUID target, Identity identity, UUID subscriber) {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM watch_subscription WHERE target_uuid = ? AND target_identity = ? "
                        + "AND subscriber_uuid = ?")) {
            ps.setString(1, target.toString());
            ps.setString(2, identity.id());
            ps.setString(3, subscriber.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StatsRepository.StorageException("取消关注失败", e);
        }
    }

    /** @return the subscription of one subscriber and one object, or {@code null} */
    public Subscription subscription(UUID target, Identity identity, UUID subscriber) {
        String sql = "SELECT target_uuid, target_identity, subscriber_uuid, alerts, low_health, "
                + "low_durability, stall_metric, created_wall FROM watch_subscription "
                + "WHERE target_uuid = ? AND target_identity = ? AND subscriber_uuid = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, target.toString());
            ps.setString(2, identity.id());
            ps.setString(3, subscriber.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readSubscription(rs) : null;
            }
        } catch (SQLException e) {
            throw new StatsRepository.StorageException("查询关注订阅失败", e);
        }
    }

    /** @return every subscription of one object */
    public List<Subscription> subscriptionsOf(UUID target, Identity identity) {
        String sql = "SELECT target_uuid, target_identity, subscriber_uuid, alerts, low_health, "
                + "low_durability, stall_metric, created_wall FROM watch_subscription "
                + "WHERE target_uuid = ? AND target_identity = ?";
        List<Subscription> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, target.toString());
            ps.setString(2, identity.id());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(readSubscription(rs));
                }
            }
        } catch (SQLException e) {
            throw new StatsRepository.StorageException("查询关注订阅失败", e);
        }
        return result;
    }

    /** @return every subscription of one subscriber */
    public List<Subscription> subscriptionsOfSubscriber(UUID subscriber) {
        String sql = "SELECT target_uuid, target_identity, subscriber_uuid, alerts, low_health, "
                + "low_durability, stall_metric, created_wall FROM watch_subscription "
                + "WHERE subscriber_uuid = ?";
        List<Subscription> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, subscriber.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(readSubscription(rs));
                }
            }
        } catch (SQLException e) {
            throw new StatsRepository.StorageException("查询关注订阅失败", e);
        }
        return result;
    }

    private Subscription readSubscription(ResultSet rs) throws SQLException {
        return new Subscription(UUID.fromString(rs.getString(1)), Identity.byId(rs.getString(2)),
                UUID.fromString(rs.getString(3)), rs.getInt(4), rs.getDouble(5), rs.getDouble(6),
                rs.getString(7), rs.getLong(8));
    }

    /** @return every subscription */
    public List<Subscription> allSubscriptions() {
        String sql = "SELECT target_uuid, target_identity, subscriber_uuid, alerts, low_health, "
                + "low_durability, stall_metric, created_wall FROM watch_subscription";
        List<Subscription> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(readSubscription(rs));
            }
        } catch (SQLException e) {
            throw new StatsRepository.StorageException("查询关注订阅失败", e);
        }
        return result;
    }
    // ------------------------------------------------------------------ alert state

    /** @return the alert state of one object and one reason, or {@code null} */
    public AlertState alertState(UUID target, Identity identity, String reason) {
        String sql = "SELECT target_uuid, target_identity, reason, active, last_fired_wall, "
                + "last_recovered_wall FROM alert_state WHERE target_uuid = ? AND target_identity = ? "
                + "AND reason = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, target.toString());
            ps.setString(2, identity.id());
            ps.setString(3, reason);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                long fired = rs.getLong(5);
                Long firedAt = rs.wasNull() ? null : fired;
                long recovered = rs.getLong(6);
                Long recoveredAt = rs.wasNull() ? null : recovered;
                return new AlertState(UUID.fromString(rs.getString(1)), Identity.byId(rs.getString(2)),
                        rs.getString(3), rs.getInt(4) == 1, firedAt, recoveredAt);
            }
        } catch (SQLException e) {
            throw new StatsRepository.StorageException("查询告警状态失败", e);
        }
    }

    /** Records that an alert fired. */
    public void markFired(UUID target, Identity identity, String reason, long wallClockMillis) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO alert_state(target_uuid, target_identity, reason, active, last_fired_wall) "
                        + "VALUES (?,?,?,1,?) "
                        + "ON CONFLICT(target_uuid, target_identity, reason) DO UPDATE SET "
                        + "active = 1, last_fired_wall = excluded.last_fired_wall")) {
            ps.setString(1, target.toString());
            ps.setString(2, identity.id());
            ps.setString(3, reason);
            ps.setLong(4, wallClockMillis);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StatsRepository.StorageException("写入告警状态失败", e);
        }
    }

    /** Records that the condition is over again, so that one recovery notice can be sent. */
    public void markRecovered(UUID target, Identity identity, String reason, long wallClockMillis) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO alert_state(target_uuid, target_identity, reason, active, last_recovered_wall) "
                        + "VALUES (?,?,?,0,?) "
                        + "ON CONFLICT(target_uuid, target_identity, reason) DO UPDATE SET "
                        + "active = 0, last_recovered_wall = excluded.last_recovered_wall")) {
            ps.setString(1, target.toString());
            ps.setString(2, identity.id());
            ps.setString(3, reason);
            ps.setLong(4, wallClockMillis);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StatsRepository.StorageException("写入告警状态失败", e);
        }
    }

    /** Writes an entry into the alert log. */
    public void logAlert(UUID target, Identity identity, String reason, UUID subscriber, String message,
                         long wallClockMillis) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO alert_log(target_uuid, target_identity, reason, subscriber_uuid, message, created_wall) "
                        + "VALUES (?,?,?,?,?,?)")) {
            ps.setString(1, target == null ? null : target.toString());
            ps.setString(2, identity == null ? null : identity.id());
            ps.setString(3, reason);
            ps.setString(4, subscriber == null ? null : subscriber.toString());
            ps.setString(5, message);
            ps.setLong(6, wallClockMillis);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StatsRepository.StorageException("写入告警日志失败", e);
        }
    }

    /** @return the most recent alert log entries */
    public List<String> recentAlerts(int limit) {
        String sql = "SELECT created_wall, target_uuid, reason, subscriber_uuid, message FROM alert_log "
                + "ORDER BY created_wall DESC, id DESC LIMIT ?";
        List<String> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new java.util.Date(rs.getLong(1)) + " | " + rs.getString(2) + " | "
                            + rs.getString(3) + " | " + rs.getString(5));
                }
            }
        } catch (SQLException e) {
            throw new StatsRepository.StorageException("查询告警日志失败", e);
        }
        return result;
    }

    // ------------------------------------------------------------------ stall windows

    /** @return the observation window of one object and metric, or {@code null} */
    public StallWindow stallWindow(UUID target, Identity identity, String metric) {
        String sql = "SELECT target_uuid, target_identity, metric, window_start_wall, last_value "
                + "FROM stall_window WHERE target_uuid = ? AND target_identity = ? AND metric = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, target.toString());
            ps.setString(2, identity.id());
            ps.setString(3, metric);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new StallWindow(UUID.fromString(rs.getString(1)), Identity.byId(rs.getString(2)),
                        rs.getString(3), rs.getLong(4), rs.getLong(5));
            }
        } catch (SQLException e) {
            throw new StatsRepository.StorageException("查询停滞窗口失败", e);
        }
    }

    /** Stores the observation window of one object and metric. */
    public void saveStallWindow(StallWindow window) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO stall_window(target_uuid, target_identity, metric, window_start_wall, last_value) "
                        + "VALUES (?,?,?,?,?) "
                        + "ON CONFLICT(target_uuid, target_identity, metric) DO UPDATE SET "
                        + "window_start_wall = excluded.window_start_wall, last_value = excluded.last_value")) {
            ps.setString(1, window.target().toString());
            ps.setString(2, window.identity().id());
            ps.setString(3, window.metric());
            ps.setLong(4, window.windowStartWall());
            ps.setLong(5, window.lastValue());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StatsRepository.StorageException("保存停滞窗口失败", e);
        }
    }

    // ------------------------------------------------------------------ summary preference

    /** @return whether the player wants to receive the session end summary */
    public boolean summaryEnabled(UUID uuid) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT enabled FROM summary_pref WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) == 1;
            }
        } catch (SQLException e) {
            throw new StatsRepository.StorageException("查询摘要偏好失败", e);
        }
    }

    /** Stores the summary preference of a player. */
    public void saveSummaryEnabled(UUID uuid, boolean enabled) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO summary_pref(uuid, enabled) VALUES (?,?) "
                        + "ON CONFLICT(uuid) DO UPDATE SET enabled = excluded.enabled")) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, enabled ? 1 : 0);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StatsRepository.StorageException("保存摘要偏好失败", e);
        }
    }

    /** @return every player that wants to receive a session end summary */
    public List<UUID> summarySubscribers() {
        String sql = "SELECT uuid FROM summary_pref WHERE enabled = 1";
        List<UUID> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(UUID.fromString(rs.getString(1)));
            }
        } catch (SQLException e) {
            throw new StatsRepository.StorageException("查询摘要订阅失败", e);
        }
        return result;
    }

    /** @return the import log entries */
    public List<String> importLog() {
        String sql = "SELECT created_wall, source, imported, skipped, operator FROM import_log "
                + "ORDER BY created_wall DESC, id DESC";
        List<String> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(new java.util.Date(rs.getLong(1)) + " | " + rs.getString(2) + " | 导入 "
                        + rs.getInt(3) + " 跳过 " + rs.getInt(4) + " | " + rs.getString(5));
            }
        } catch (SQLException e) {
            throw new StatsRepository.StorageException("查询导入日志失败", e);
        }
        return result;
    }

    /** Writes an entry into the import log. */
    public void logImport(String source, int imported, int skipped, String operator, long wallClockMillis) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO import_log(source, imported, skipped, operator, created_wall) VALUES (?,?,?,?,?)")) {
            ps.setString(1, source);
            ps.setInt(2, imported);
            ps.setInt(3, skipped);
            ps.setString(4, operator);
            ps.setLong(5, wallClockMillis);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StatsRepository.StorageException("写入导入日志失败", e);
        }
    }

    /** @return an unmodifiable view for the tests */
    public Map<String, Long> statistics() {
        Map<String, Long> result = new java.util.LinkedHashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT reason, COUNT(*) FROM alert_log GROUP BY reason");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.put(rs.getString(1), rs.getLong(2));
            }
        } catch (SQLException e) {
            throw new StatsRepository.StorageException("统计告警失败", e);
        }
        return result;
    }
}
