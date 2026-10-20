package dev.themisstats.bot;

import carpet.fakes.ServerPlayerInterface;
import dev.themisstats.config.RuleState;
import dev.themisstats.identity.Identity;
import dev.themisstats.identity.IdentityResolver;
import dev.themisstats.session.Session;
import dev.themisstats.session.SessionManager;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Samples the run state of the online bots.
 *
 * <p>The sampler reads a small, fixed set of fields of the online bots every
 * {@code themisStatsBotSampleSeconds} seconds. It never scans the surroundings of a bot, never
 * loads chunks and never touches a block in the world.</p>
 *
 * <p>Three kinds of information are kept apart and are never mixed:</p>
 * <ul>
 *   <li><b>online</b> - the object is in the server right now;</li>
 *   <li><b>action plan</b> - the actions that were configured, which says nothing about whether
 *       they succeed;</li>
 *   <li><b>observed behaviour</b> - the statistics that actually changed, which is the only
 *       evidence that something happened.</li>
 * </ul>
 */
public final class BotMonitor {

    private final RuleState rules;
    private final SessionManager sessions;
    private final Map<UUID, BotSnapshot> lastKnown = new ConcurrentHashMap<>();
    private long lastSampleMillis;
    private int lastObservedCount = -1;

    public BotMonitor(RuleState rules, SessionManager sessions) {
        this.rules = rules;
        this.sessions = sessions;
    }

    /**
     * Takes a sample of the online bots, if the sample interval has elapsed.
     *
     * @param server the server
     * @return {@code true} when a sample was taken
     */
    public boolean sampleIfDue(net.minecraft.server.MinecraftServer server) {
        if (!rules.botMonitor() || server == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        long interval = Math.max(1, rules.botSampleSeconds()) * 1000L;
        if (now - lastSampleMillis < interval) {
            return false;
        }
        return sample(server);
    }

    /**
     * Takes a sample right now, regardless of the interval.
     *
     * <p>This is used when a player asks for the bot list, so that the answer is not based on a
     * stale snapshot.</p>
     */
    public boolean sample(net.minecraft.server.MinecraftServer server) {
        if (!rules.botMonitor() || server == null) {
            return false;
        }
        lastSampleMillis = System.currentTimeMillis();
        int observed = 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Identity identity = IdentityResolver.resolve(player);
            if (!identity.botLike()) {
                continue;
            }
            lastKnown.put(player.getUUID(), capture(player, identity));
            observed++;
        }
        if (observed != lastObservedCount) {
            lastObservedCount = observed;
            dev.themisstats.ThemisStats.LOGGER.info("[ThemisStats] 假人状态采样：观察到 {} 个在线假人", observed);
        }
        return true;
    }

    private BotSnapshot capture(ServerPlayer player, Identity identity) {
        Session session = sessions.sessionOf(player);
        List<String> actionPlan = List.of();
        if (player instanceof ServerPlayerInterface carrier) {
            try {
                actionPlan = CarpetActionAdapter.describe(carrier.getActionPack());
            } catch (RuntimeException | LinkageError e) {
                actionPlan = List.of("动作计划：当前版本不可观察");
            }
        }
        ItemStack mainHand = player.getMainHandItem();
        ItemStack offHand = player.getOffhandItem();
        Integer durability = null;
        if (!mainHand.isEmpty() && mainHand.getMaxDamage() > 0) {
            int remaining = mainHand.getMaxDamage() - mainHand.getDamageValue();
            durability = (int) Math.round(100.0D * remaining / mainHand.getMaxDamage());
        }
        return new BotSnapshot(player.getUUID(), identity, player.getGameProfile().name(), true,
                player.level().dimension().identifier().toString(), player.getX(), player.getY(), player.getZ(),
                player.getHealth(), player.getFoodData().getFoodLevel(),
                describeItem(mainHand), describeItem(offHand), durability, actionPlan,
                System.currentTimeMillis(),
                session == null ? 0L : session.onlineMillis(),
                session == null ? null : session.id());
    }

    private static String describeItem(ItemStack stack) {
        if (stack.isEmpty()) {
            return "空手";
        }
        String name = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        int count = stack.getCount();
        return count > 1 ? name + " x" + count : name;
    }

    /** @return the last known snapshot of an object, or {@code null} */
    public BotSnapshot snapshot(UUID uuid, Identity identity) {
        BotSnapshot snapshot = lastKnown.get(uuid);
        if (snapshot == null) {
            return null;
        }
        if (identity != null && snapshot.identity() != identity) {
            return null;
        }
        return snapshot;
    }

    /** @return the last known snapshots of all observed bots */
    public List<BotSnapshot> snapshots() {
        return new ArrayList<>(lastKnown.values());
    }

    /** Stops the observation. The already collected history is kept. */
    public void stop() {
        lastKnown.clear();
        lastSampleMillis = 0;
    }
    /** Removes the snapshot of an object that is gone for good. */
    public void forget(UUID uuid) {
        lastKnown.remove(uuid);
    }

    /** @return how many bots are currently online */
    public int onlineCount(net.minecraft.server.MinecraftServer server) {
        int count = 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (IdentityResolver.resolve(player).botLike()) {
                count++;
            }
        }
        return count;
    }


}
