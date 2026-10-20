package dev.themisstats.alert;

import dev.themisstats.ThemisStats;
import dev.themisstats.ThemisStatsRuntime;
import dev.themisstats.config.RuleState;
import dev.themisstats.identity.Identity;
import dev.themisstats.identity.IdentityResolver;
import dev.themisstats.metric.Metric;
import dev.themisstats.session.SessionEndReason;
import dev.themisstats.storage.StatsRepository;
import dev.themisstats.storage.WatchRepository;
import dev.themisstats.util.Numbers;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The run alerts.
 *
 * <p>The rules the specification sets are implemented literally:</p>
 * <ul>
 *   <li>the global switch is off by default, but subscriptions may be stored while it is off;</li>
 *   <li>a per object alert only goes to the subscribers that have the permission;</li>
 *   <li>the same object and the same reason are cooled down for ten minutes;</li>
 *   <li>after the condition is over again, exactly one recovery notice is sent;</li>
 *   <li>a newly online object gets a grace period of sixty seconds;</li>
 *   <li>the "no progress" alert only says that the statistic did not grow - it never claims that a
 *       machine is broken;</li>
 *   <li>while the collection is paused, the observation is unavailable or the server is frozen, the
 *       "no progress" judgement is suspended and the window is rebuilt afterwards;</li>
 *   <li>a low tick rate reduces the number of actions per real minute, so the alert always names
 *       the observation window;</li>
 *   <li>an alert never clears the inventory, never refills tools, never teleports the object and
 *       never touches its actions.</li>
 * </ul>
 *
 * <p><b>Threads.</b> The game thread only reads the health and the durability of the player object
 * and sends the messages. Everything that touches the database runs in the background: the
 * subscriptions, the owners, the alert states and the alert log are cached in memory and refreshed
 * by {@link #refresh()}, so that the game thread never waits for a disk write.</p>
 */
public final class AlertEngine {

    /** The default cooldown of one object and one reason. */
    public static final long COOLDOWN_MILLIS = 10 * 60 * 1000L;

    /** The grace period a newly online object gets. */
    public static final long GRACE_PERIOD_MILLIS = 60 * 1000L;

    /** How long the "no progress" window has to be without a change. */
    public static final long STALL_WINDOW_MILLIS = 5 * 60 * 1000L;

    /** How many samples in a row have to confirm the low health before the alert fires. */
    public static final int LOW_HEALTH_CONFIRMATIONS = 2;

    /** How often the "no progress" window is looked at. */
    public static final long STALL_CHECK_INTERVAL_MILLIS = 30 * 1000L;

    /** The reading of one object, taken on the game thread. */
    private record Reading(ServerPlayer player, Identity identity, double health, Integer durabilityPercent,
                           String mainHandId) {
    }

    private final RuleState rules;
    private final WatchRepository watch;

    /** The subscriptions, refreshed in the background. */
    private final Map<String, List<WatchRepository.Subscription>> subscriptions = new ConcurrentHashMap<>();
    /** The registered owners of the watched objects, refreshed in the background. */
    private final Map<UUID, UUID> owners = new ConcurrentHashMap<>();
    /** The alert states, refreshed in the background. */
    private final Map<String, WatchRepository.AlertState> alertStates = new ConcurrentHashMap<>();
    /** When an alert was last sent, per object and reason. */
    private final Map<String, Long> lastFireWall = new ConcurrentHashMap<>();
    /** Whether an alert is currently active, per object and reason. */
    private final Map<String, Boolean> activeFlags = new ConcurrentHashMap<>();
    /** The last confirmed low health reading, per object. */
    private final Map<String, Integer> lowHealthConfirmations = new ConcurrentHashMap<>();
    /** The last confirmed low durability reading, per object. */
    private final Map<String, Integer> lowDurabilityConfirmations = new ConcurrentHashMap<>();
    /** When an object came online, per object. */
    private final Map<String, Long> onlineSince = new ConcurrentHashMap<>();
    /** The value of the watched statistic when the window was opened. */
    private final Map<String, Long> stallValues = new ConcurrentHashMap<>();
    /** Whether the save failure was already reported. */
    private volatile boolean saveFailureReported;
    /** When the "no progress" window was looked at the last time. */
    private volatile long lastStallCheck;

    public AlertEngine(RuleState rules, WatchRepository watch) {
        this.rules = rules;
        this.watch = watch;
    }

    // ------------------------------------------------------------------ refresh

    /**
     * Reloads the subscriptions, the owners and the alert states. This is a database read and
     * therefore runs in the background.
     */
    public void refresh() {
        var services = ThemisStatsRuntime.services();
        if (services == null) {
            return;
        }
        services.runInBackground(() -> {
            try {
                List<WatchRepository.Subscription> all = watch.allSubscriptions();
                Map<String, List<WatchRepository.Subscription>> loaded = new HashMap<>();
                Map<UUID, UUID> loadedOwners = new HashMap<>();
                Map<String, WatchRepository.AlertState> loadedStates = new HashMap<>();
                for (WatchRepository.Subscription subscription : all) {
                    loaded.computeIfAbsent(key(subscription.target(), subscription.identity()),
                            k -> new ArrayList<>()).add(subscription);
                    if (!loadedOwners.containsKey(subscription.target())) {
                        UUID owner = services.permissions().ownerOf(subscription.target());
                        if (owner != null) {
                            loadedOwners.put(subscription.target(), owner);
                        }
                    }
                    for (AlertType type : AlertType.values()) {
                        if ((subscription.alerts() & type.bit) == 0) {
                            continue;
                        }
                        WatchRepository.AlertState state = watch.alertState(subscription.target(),
                                subscription.identity(), type.id());
                        if (state != null) {
                            loadedStates.put(stateKey(subscription.target(), subscription.identity(), type.id()),
                                    state);
                        }
                    }
                }
                subscriptions.keySet().retainAll(loaded.keySet());
                subscriptions.putAll(loaded);
                owners.keySet().retainAll(loadedOwners.keySet());
                owners.putAll(loadedOwners);
                alertStates.keySet().retainAll(loadedStates.keySet());
                alertStates.putAll(loadedStates);
                // The cooldown survives a restart: the database holds the last time an alert went out.
                for (Map.Entry<String, WatchRepository.AlertState> entry : loadedStates.entrySet()) {
                    Long fired = entry.getValue().lastFiredWall();
                    if (fired == null) {
                        continue;
                    }
                    Long known = lastFireWall.get(entry.getKey());
                    if (known == null || known < fired) {
                        lastFireWall.put(entry.getKey(), fired);
                    }
                }
            } catch (RuntimeException e) {
                ThemisStats.LOGGER.error("[ThemisStats] 读取告警订阅失败", e);
            }
        });
    }

    // ------------------------------------------------------------------ evaluation

    /**
     * Reads the values of the online objects on the game thread and evaluates the alerts.
     *
     * <p>This method never touches the database: it only reads the in memory caches and sends the
     * messages. The writes go to the background.</p>
     */
    public void evaluateOnGameThread(List<ServerPlayer> players) {
        if (!rules.botAlerts()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (ServerPlayer player : players) {
            Identity identity = IdentityResolver.resolve(player);
            if (!identity.botLike()) {
                continue;
            }
            String key = key(player.getUUID(), identity);
            onlineSince.putIfAbsent(key, now);
            if (now - onlineSince.get(key) < GRACE_PERIOD_MILLIS) {
                // The object is still in its grace period.
                continue;
            }
            Reading reading = read(player, identity);
            evaluateLowHealth(reading, key, now);
            evaluateLowDurability(reading, key, now);
        }
        evaluateSaveFailure(now);
        evaluateStall(players, now);
    }

    private Reading read(ServerPlayer player, Identity identity) {
        var stack = player.getMainHandItem();
        Integer durability = null;
        String itemId = null;
        if (!stack.isEmpty() && stack.getMaxDamage() > 0) {
            durability = (int) Math.round(100.0D * (stack.getMaxDamage() - stack.getDamageValue())
                    / stack.getMaxDamage());
            itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        }
        return new Reading(player, identity, player.getHealth(), durability, itemId);
    }

    // ------------------------------------------------------------------ low health

    private void evaluateLowHealth(Reading reading, String key, long now) {
        double threshold = thresholdFor(reading, AlertType.LOW_HEALTH, 6.0D);
        boolean low = reading.health() <= threshold;
        int confirmations;
        if (low) {
            confirmations = lowHealthConfirmations.merge(key, 1, Integer::sum);
        } else {
            confirmations = lowHealthConfirmations.getOrDefault(key, 0);
            lowHealthConfirmations.put(key, 0);
        }
        if (!low) {
            if (confirmations > 0) {
                // The object was low before, so the condition may have been over again.
                sendRecovery(reading, AlertType.LOW_HEALTH, now);
            }
            return;
        }
        if (confirmations < LOW_HEALTH_CONFIRMATIONS) {
            return;
        }
        String message = String.format("【Themis Stats】%s 的生命值已连续两次低于 %.1f（当前 %.1f）",
                reading.player().getGameProfile().name(), threshold, reading.health());
        fire(reading, AlertType.LOW_HEALTH, message, now);
    }

    // ------------------------------------------------------------------ low durability

    private void evaluateLowDurability(Reading reading, String key, long now) {
        double threshold = thresholdFor(reading, AlertType.LOW_DURABILITY, 10.0D);
        boolean low = reading.durabilityPercent() != null && reading.durabilityPercent() <= threshold;
        int confirmations;
        if (low) {
            confirmations = lowDurabilityConfirmations.merge(key, 1, Integer::sum);
        } else {
            confirmations = lowDurabilityConfirmations.getOrDefault(key, 0);
            lowDurabilityConfirmations.put(key, 0);
        }
        if (!low) {
            if (confirmations > 0) {
                sendRecovery(reading, AlertType.LOW_DURABILITY, now);
            }
            return;
        }
        String message = String.format("【Themis Stats】%s 的主手工具 %s 剩余耐久 %d%%（阈值 %.0f%%）",
                reading.player().getGameProfile().name(), reading.mainHandId(),
                reading.durabilityPercent(), threshold);
        fire(reading, AlertType.LOW_DURABILITY, message, now);
    }

    // ------------------------------------------------------------------ no progress

    /**
     * Looks at the observation window of the "no progress" alert.
     *
     * <p>The metric values are read in the background, because that is a database read. The message
     * itself goes back to the game thread.</p>
     */
    private void evaluateStall(List<ServerPlayer> players, long now) {
        if (!rules.recording()) {
            // The collection is paused: the observation is not valid, so the window is rebuilt.
            stallValues.clear();
            lastStallCheck = now;
            return;
        }
        if (now - lastStallCheck < STALL_CHECK_INTERVAL_MILLIS) {
            return;
        }
        lastStallCheck = now;
        var services = ThemisStatsRuntime.services();
        if (services == null) {
            return;
        }
        services.runInBackground(() -> {
            for (ServerPlayer player : players) {
                Identity identity = IdentityResolver.resolve(player);
                if (!identity.botLike()) {
                    continue;
                }
                for (WatchRepository.Subscription subscription : subscriptionsOf(player.getUUID(), identity)) {
                    if ((subscription.alerts() & AlertType.METRIC_STALL.bit) == 0) {
                        continue;
                    }
                    String metricId = subscription.stallMetric();
                    if (metricId == null || metricId.isBlank()) {
                        continue;
                    }
                    Metric metric = Metric.byId(metricId);
                    if (metric == null || !stallPurposeEnabled(player, identity)) {
                        continue;
                    }
                    String key = key(player.getUUID(), identity);
                    String windowKey = key + "|" + metricId;
                    long value = services.stats().valueOf(player.getUUID(),
                            StatsRepository.Scope.of(metric, identity, List.of(), "", ""));
                    Long previous = stallValues.get(windowKey);
                    if (previous == null || value != previous) {
                        stallValues.put(windowKey, value);
                        watch.saveStallWindow(new WatchRepository.StallWindow(player.getUUID(), identity,
                                metricId, now, value));
                        continue;
                    }
                    WatchRepository.StallWindow window = watch.stallWindow(player.getUUID(), identity,
                            metricId);
                    long start = window == null ? now : window.windowStartWall();
                    if (now - start < STALL_WINDOW_MILLIS) {
                        continue;
                    }
                    long minutes = Math.max(1, (now - start) / 60000L);
                    String message = String.format(
                            "【Themis Stats】%s 在最近 %d 分钟的有效采集窗口内，指定指标「%s」没有增长（当前 %s）。"
                                    + "这只说明观察期内该指标无增长，不能据此判断机器是否故障。",
                            player.getGameProfile().name(), minutes, metric.displayName(),
                            Numbers.full(value));
                    stallValues.put(windowKey, value);
                    watch.saveStallWindow(new WatchRepository.StallWindow(player.getUUID(), identity,
                            metricId, now, value));
                    // The message goes back to the game thread.
                    var server = services.server();
                    if (server != null) {
                        server.execute(() -> sendToSubscribers(player.getUUID(), identity,
                                AlertType.METRIC_STALL, message, now));
                    }
                }
            }
        });
    }

    /**
     * @return whether the purpose of the object is enabled for the "no progress" alert
     */
    private boolean stallPurposeEnabled(ServerPlayer player, Identity identity) {
        var services = ThemisStatsRuntime.services();
        if (services == null) {
            return false;
        }
        var profile = services.accounts().botProfile(player.getUUID(), identity);
        String purpose = profile == null || profile.purpose() == null ? "" : profile.purpose();
        List<String> enabled = rules.stallPurposes();
        if (enabled.contains("any")) {
            return true;
        }
        return enabled.contains(purpose);
    }

    // ------------------------------------------------------------------ save failure

    private void evaluateSaveFailure(long now) {
        var services = ThemisStatsRuntime.services();
        if (services == null) {
            return;
        }
        if (services.writer().isDegraded()) {
            if (!saveFailureReported) {
                saveFailureReported = true;
                String message = "【Themis Stats】统计数据无法提交：" + services.writer().describe()
                        + "。统计模块已进入降级状态，期间的增量可能存在缺口。";
                // The alert log is written in the background.
                services.runInBackground(() ->
                        watch.logAlert(null, null, AlertType.SAVE_FAILURE.id, null, message, now));
                ThemisStats.LOGGER.warn("[ThemisStats] {}", message);
                for (ServerPlayer admin : services.permissions().admins(services)) {
                    admin.sendSystemMessage(Component.literal(message).withStyle(ChatFormatting.RED));
                }
            }
        } else {
            saveFailureReported = false;
        }
    }

    // ------------------------------------------------------------------ offline

    /**
     * Reports that a watched object went offline.
     */
    public void reportOffline(UUID target, Identity identity, String name, SessionEndReason reason) {
        if (!rules.botAlerts()) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean normal = reason == SessionEndReason.SERVER_SHUTDOWN;
        String message = normal
                ? "【Themis Stats】" + name + " 已离线（服务器正常关闭）。"
                : "【Themis Stats】" + name + " 已离线，原因：" + reason.displayName()
                + "。如为管理员操作请忽略。";
        sendToSubscribers(target, identity, AlertType.UNEXPECTED_OFFLINE, message, now);
    }

    // ------------------------------------------------------------------ sending

    private List<WatchRepository.Subscription> subscriptionsOf(UUID target, Identity identity) {
        return subscriptions.getOrDefault(key(target, identity), List.of());
    }

    /**
     * Sends an alert to every subscriber that is allowed to receive it.
     *
     * <p>This method runs on the game thread and does not touch the database. The alert log and the
     * alert state are written in the background.</p>
     */
    private void fire(Reading reading, AlertType type, String message, long now) {
        sendToSubscribers(reading.player().getUUID(), reading.identity(), type, message, now);
    }

    private void sendToSubscribers(UUID target, Identity identity, AlertType type, String message, long now) {
        var services = ThemisStatsRuntime.services();
        if (services == null || services.server() == null) {
            return;
        }
        String stateKey = stateKey(target, identity, type.id());
        Long last = lastFireWall.get(stateKey);
        if (last != null && now - last < COOLDOWN_MILLIS) {
            // Still in the cooldown of the last notice.
            return;
        }
        List<WatchRepository.Subscription> targets = subscriptionsOf(target, identity);
        if (targets.isEmpty()) {
            return;
        }
        UUID owner = owners.get(target);
        List<UUID> notified = new ArrayList<>();
        for (WatchRepository.Subscription subscription : targets) {
            if ((subscription.alerts() & type.bit) == 0) {
                continue;
            }
            ServerPlayer subscriber = services.server().getPlayerList().getPlayer(subscription.subscriber());
            if (subscriber == null) {
                continue;
            }
            if (!services.permissions().canReceiveAlerts(subscriber, target, identity, owner)) {
                continue;
            }
            subscriber.sendSystemMessage(Component.literal(message).withStyle(ChatFormatting.GOLD));
            notified.add(subscription.subscriber());
        }
        if (notified.isEmpty()) {
            return;
        }
        lastFireWall.put(stateKey, now);
        activeFlags.put(stateKey, Boolean.TRUE);
        // The alert log and the alert state are written in the background.
        services.runInBackground(() -> {
            for (UUID subscriber : notified) {
                watch.logAlert(target, identity, type.id(), subscriber, message, now);
            }
            watch.markFired(target, identity, type.id(), now);
        });
    }

    /**
     * Sends exactly one recovery notice after the condition is over again.
     */
    private void sendRecovery(Reading reading, AlertType type, long now) {
        var services = ThemisStatsRuntime.services();
        if (services == null || services.server() == null) {
            return;
        }
        UUID target = reading.player().getUUID();
        Identity identity = reading.identity();
        String stateKey = stateKey(target, identity, type.id());
        if (!Boolean.TRUE.equals(activeFlags.get(stateKey))) {
            // Nothing was announced, so there is nothing to recover from.
            return;
        }
        activeFlags.remove(stateKey);
        String message = "【Themis Stats】" + reading.player().getGameProfile().name() + " 的「"
                + type.displayName() + "」状态已恢复。";
        UUID owner = owners.get(target);
        List<UUID> notified = new ArrayList<>();
        for (WatchRepository.Subscription subscription : subscriptionsOf(target, identity)) {
            if ((subscription.alerts() & type.bit) == 0) {
                continue;
            }
            ServerPlayer subscriber = services.server().getPlayerList().getPlayer(subscription.subscriber());
            if (subscriber == null) {
                continue;
            }
            if (!services.permissions().canReceiveAlerts(subscriber, target, identity, owner)) {
                continue;
            }
            subscriber.sendSystemMessage(Component.literal(message).withStyle(ChatFormatting.GREEN));
            notified.add(subscription.subscriber());
        }
        if (notified.isEmpty()) {
            return;
        }
        services.runInBackground(() -> {
            for (UUID subscriber : notified) {
                watch.logAlert(target, identity, type.id() + "_recovered", subscriber, message, now);
            }
            watch.markRecovered(target, identity, type.id(), now);
        });
    }

    private double thresholdFor(Reading reading, AlertType type, double fallback) {
        for (WatchRepository.Subscription subscription : subscriptionsOf(reading.player().getUUID(),
                reading.identity())) {
            if ((subscription.alerts() & type.bit) == 0) {
                continue;
            }
            return type == AlertType.LOW_HEALTH ? subscription.lowHealth() : subscription.lowDurability();
        }
        return fallback;
    }

    /** @return the purposes that are enabled for the "no progress" alert */
    public List<String> stallPurposes() {
        return rules.stallPurposes();
    }

    private static String key(UUID target, Identity identity) {
        return target + "|" + identity.id();
    }

    private static String stateKey(UUID target, Identity identity, String reason) {
        return target + "|" + identity.id() + "|" + reason;
    }
}
