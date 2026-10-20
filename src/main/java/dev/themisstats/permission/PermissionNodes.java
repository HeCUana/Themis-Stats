package dev.themisstats.permission;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The permission nodes.
 *
 * <p>The first release did not depend on a permission mod. The nodes are defined here anyway, so
 * that the check has one clear place and a permission mod can be plugged in later without
 * touching the commands.</p>
 *
 * <p>Without a permission mod the nodes are resolved by the operator level: a player with at
 * least the administrator level has every node, an ordinary player only has the query nodes.</p>
 */
public final class PermissionNodes {

    /** The prefix of every node of this mod. */
    public static final String PREFIX = "themisstats.";

    /** Reading the public statistics. */
    public static final String QUERY = PREFIX + "query";

    /** Reading the detailed run information of an object. */
    public static final String VIEW_DETAILS = PREFIX + "view_details";

    /** Changing the own sidebar preference. */
    public static final String SIDEBAR = PREFIX + "sidebar";

    /** Subscribing to the run alerts of an object. */
    public static final String WATCH = PREFIX + "watch";

    /** Exporting the statistics. */
    public static final String EXPORT = PREFIX + "export";

    /** Correcting the data and managing the objects. */
    public static final String MANAGE = PREFIX + "manage";

    /** The nodes an ordinary player has by default. */
    public static final List<String> DEFAULT_PLAYER_NODES = List.of(QUERY, SIDEBAR);

    /** The nodes an administrator has by default. */
    public static final List<String> DEFAULT_ADMIN_NODES =
            List.of(QUERY, VIEW_DETAILS, SIDEBAR, WATCH, EXPORT, MANAGE);

    /**
     * A permission mod can be plugged in here. Without one, the operator level decides.
     */
    public interface Provider {
        /**
         * @return whether the player has the node
         */
        boolean has(ServerPlayer player, String node);
    }

    private static volatile Provider provider = new PermissionNodes.OperatorLevelProvider();

    private PermissionNodes() {
    }

    /** Installs a permission provider, for example from a permission mod. */
    public static void setProvider(Provider newProvider) {
        provider = newProvider == null ? new OperatorLevelProvider() : newProvider;
    }

    public static Provider provider() {
        return provider;
    }

    /** @return whether the player has the node */
    public static boolean has(ServerPlayer player, String node) {
        if (player == null) {
            return false;
        }
        return provider.has(player, node);
    }

    /**
     * The default provider: the operator level decides.
     */
    public static final class OperatorLevelProvider implements Provider {
        @Override
        public boolean has(ServerPlayer player, String node) {
            CommandSourceStack source = player.createCommandSourceStack();
            if (net.minecraft.commands.Commands.LEVEL_GAMEMASTERS.check(source.permissions())) {
                return DEFAULT_ADMIN_NODES.contains(node);
            }
            return DEFAULT_PLAYER_NODES.contains(node);
        }
    }

    /** @return the node name in the canonical lower case form */
    public static String normalize(String node) {
        return node == null ? "" : node.toLowerCase(Locale.ROOT);
    }
}
