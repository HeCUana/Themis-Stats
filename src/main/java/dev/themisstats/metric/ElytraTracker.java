package dev.themisstats.metric;

import dev.themisstats.ThemisStatsRuntime;
import dev.themisstats.session.Session;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Measures the elytra glide distance.
 *
 * <p>The measurement rules live in {@link ElytraDistanceCalculator}, which is a pure state machine
 * and therefore testable on its own. This class only feeds the positions of the players into it and
 * forwards the resulting distance to the metric pipeline.</p>
 */
public final class ElytraTracker {

    private final Map<UUID, ElytraDistanceCalculator> calculators = new ConcurrentHashMap<>();

    /** Called once per server tick for every player. */
    public void tick(ServerPlayer player) {
        var services = ThemisStatsRuntime.services();
        if (services == null) {
            return;
        }
        Session session = services.sessions().sessionOf(player);
        if (session == null) {
            return;
        }
        String dimension = player.level().dimension().identifier().toString();
        var position = player.position();
        ElytraDistanceCalculator calculator =
                calculators.computeIfAbsent(player.getUUID(), uuid -> new ElytraDistanceCalculator());
        long milliBlocks = calculator.tick(dimension, position.x, position.y, position.z, player.isFallFlying());
        if (milliBlocks > 0) {
            // The distance is stored in thousandths of a block so that it stays an exact
            // 64 bit integer.
            services.pipeline().record(session, Metric.ELYTRA_DISTANCE, milliBlocks, null,
                    dimension, MetricHooks.modeOf(player.gameMode.getGameModeForPlayer()));
        }
    }

    /** Removes the tracker state of a player that went offline. */
    public void forget(UUID uuid) {
        calculators.remove(uuid);
    }
}
