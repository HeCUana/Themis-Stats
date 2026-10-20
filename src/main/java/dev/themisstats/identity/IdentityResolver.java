package dev.themisstats.identity;

import carpet.patches.EntityPlayerMPFake;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Decides which {@link Identity} a server side player object has.
 *
 * <p>The decision is taken from Carpet's own object state, exactly the way Carpet's own scripting
 * API does it ({@code carpet.script.external.Carpet#playerType}):</p>
 * <ul>
 *   <li>{@code player instanceof EntityPlayerMPFake} - the object is a Carpet fake. The
 *       {@code isAShadow} flag then separates {@link Identity#SHADOW} from {@link Identity#BOT}.</li>
 *   <li>otherwise, if the object's network channel is an embedded (in-process) channel, the object
 *       is a fake-like object created by something else. This build cannot identify it, so it is
 *       reported as {@link Identity#UNKNOWN} instead of being guessed to be a human.</li>
 *   <li>otherwise the object is a real client connection: {@link Identity#HUMAN}.</li>
 * </ul>
 *
 * <p>Names, skins, IP addresses and scoreboard teams are never consulted.</p>
 */
public final class IdentityResolver {

    private IdentityResolver() {
    }

    /**
     * @param player the server side player object
     * @return the identity of the given object, never {@code null}
     */
    public static Identity resolve(ServerPlayer player) {
        if (player == null) {
            return Identity.UNKNOWN;
        }
        if (player instanceof EntityPlayerMPFake fake) {
            return fake.isAShadow ? Identity.SHADOW : Identity.BOT;
        }
        if (hasEmbeddedChannel(player)) {
            // A fake-like object that this build's adapter cannot identify.
            return Identity.UNKNOWN;
        }
        return Identity.HUMAN;
    }

    /**
     * @param player the server side player object
     * @return whether the object's network channel is an in-process channel, which means it is not
     *         backed by a real client connection
     */
    private static boolean hasEmbeddedChannel(ServerPlayer player) {
        try {
            if (player.connection == null || player.connection.connection == null) {
                return false;
            }
            return player.connection.connection.channel instanceof EmbeddedChannel;
        } catch (RuntimeException | LinkageError e) {
            // Never let a classification problem break the server; treat it as "cannot confirm".
            return true;
        }
    }

    /**
     * @param player the server side player object
     * @return whether the object is a Carpet fake or shadow (any non-human identity we can name)
     */
    public static boolean isBotLike(ServerPlayer player) {
        return resolve(player).botLike();
    }
}
