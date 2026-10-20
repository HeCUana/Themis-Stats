package dev.themisstats.metric;

import dev.themisstats.ThemisStatsRuntime;
import dev.themisstats.session.Session;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

/**
 * The bridge between the game events and the metric pipeline.
 *
 * <p>Every method here is called from a mixin on the server thread. The method reads the game
 * objects, captures the identity and the scope of the event <em>right now</em>, and hands an
 * immutable increment to the pipeline. Nothing is looked up again later.</p>
 */
public final class MetricHooks {

    private MetricHooks() {
    }

    /**
     * @param gameMode the game mode the behaviour happened in
     * @return the mode id used in the database
     */
    public static String modeOf(GameType gameMode) {
        return gameMode == null ? "unknown" : gameMode.getName().toLowerCase(java.util.Locale.ROOT);
    }

    /** Records a metric for a player, using the player's current session. */
    public static void record(ServerPlayer player, Metric metric, long amount, String category) {
        if (player == null || amount <= 0) {
            return;
        }
        var services = ThemisStatsRuntime.services();
        if (services == null) {
            return;
        }
        Session session = services.sessions().sessionOf(player);
        if (session == null || !session.isCollecting()) {
            // Collection is paused for this session (themisStatsRecording is off).
            return;
        }
        services.pipeline().record(session, metric, amount, category,
                player.level().dimension().identifier().toString(),
                modeOf(player.gameMode.getGameModeForPlayer()));
    }
}
