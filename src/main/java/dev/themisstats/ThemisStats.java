package dev.themisstats;

import carpet.CarpetServer;
import dev.themisstats.config.ThemisStatsExtension;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Themis Stats (忒弥斯统计) - server-side statistics, leaderboards and bot run observation
 * for Carpet powered generator servers.
 *
 * <p>This mod is a <strong>hard Carpet dependency</strong>. It is installed on the server only;
 * vanilla clients can use every P0 feature without installing anything.</p>
 *
 * <p>Entrypoint contract: Fabric Loader runs {@code main} entrypoints before {@code server}
 * entrypoints, therefore registering the {@link ThemisStatsExtension} here guarantees that
 * Carpet's {@code CarpetServer#onGameStarted()} sees our extension and can invoke
 * {@code onGameStarted()} on it. That hook is where our rules are registered into Carpet's
 * <em>main</em> {@code /carpet} settings manager (not into a separate extension manager).</p>
 */
public final class ThemisStats implements ModInitializer {

    public static final String MOD_ID = "themisstats";
    public static final String MOD_NAME = "Themis Stats";
    public static final String DISPLAY_NAME = "Themis Stats（忒弥斯统计）";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** The mod version, as declared in fabric.mod.json. */
    public static final String VERSION = FabricLoader.getInstance()
            .getModContainer(MOD_ID)
            .map(container -> container.getMetadata().getVersion().getFriendlyString())
            .orElse("unknown");

    /** The single registered Carpet extension. */
    private static ThemisStatsExtension extension;

    @Override
    public void onInitialize() {
        extension = new ThemisStatsExtension();
        CarpetServer.manageExtension(extension);

        // A respawn replaces the player object. The old session is settled and a new one starts,
        // while the accumulated totals stay continuous.
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            ThemisStatsServices services = ThemisStatsRuntime.services();
            if (services != null) {
                services.sessions().onPlayerRespawn(oldPlayer, newPlayer);
            }
        });

        LOGGER.info("[{}] v{} initialised, registered as a Carpet extension", MOD_NAME, VERSION);
    }

    /** @return the registered Carpet extension, or {@code null} before the mod is initialised */
    public static ThemisStatsExtension extension() {
        return extension;
    }
}
