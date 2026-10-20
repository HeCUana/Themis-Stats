package dev.themisstats.display;

import dev.themisstats.config.RuleState;
import dev.themisstats.identity.Identity;
import dev.themisstats.metric.Metric;
import dev.themisstats.permission.PermissionService;
import dev.themisstats.storage.AccountRepository;
import dev.themisstats.storage.StatsRepository;
import dev.themisstats.util.Numbers;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.numbers.BlankFormat;
import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetScorePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The personal sidebar.
 *
 * <p><b>Per player resources.</b> Every player gets their <em>own</em> scoreboard, and only that
 * player receives the packets. No team is created, no player is added to a team, and no global
 * scoreboard of the world is touched. Two players can therefore look at two different boards at
 * the same time.</p>
 *
 * <p><b>64 bit values.</b> The vanilla sidebar score is a 32 bit integer, so the business value is
 * never pushed into it. The score is only a <em>sort key</em>; the real value is written into the
 * entry text, and the objective uses the {@code blank} number format so that the score column stays
 * empty. A value of 9 000 000 000 is therefore shown exactly, and the ranking never overflows.</p>
 *
 * <p><b>No repeated content.</b> The rendered content is compared with the content that was sent
 * last; identical content is not sent again.</p>
 */
public final class SidebarService {

    private static final String OBJECTIVE_NAME = "themisstats_sidebar";


    /** The personal sidebar of one player. */
    private static final class PlayerSidebar {
        private final net.minecraft.world.scores.Scoreboard scoreboard = new net.minecraft.world.scores.Scoreboard();
        private final Objective objective;
        private final Map<Integer, ScoreHolder> holders = new LinkedHashMap<>();
        private String lastSignature = "";
        private SidebarContent.Content pending;
        private String mode = "off";
        private String metric = "mining";
        private String group = "human";
        private long lastCarouselSwitch;

        PlayerSidebar() {
            this.objective = scoreboard.addObjective(OBJECTIVE_NAME, ObjectiveCriteria.DUMMY,
                    Component.literal("Themis Stats"), ObjectiveCriteria.RenderType.INTEGER, false,
                    BlankFormat.INSTANCE);
            scoreboard.setDisplayObjective(DisplaySlot.SIDEBAR, objective);
        }
    }

    private final RuleState rules;
    private final PermissionService permissions;
    private final AccountRepository accounts;
    private final Map<UUID, PlayerSidebar> sidebars = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, AccountRepository.DisplayPreference> preferences =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final dev.themisstats.ThemisStatsServices services;

    public SidebarService(RuleState rules, PermissionService permissions,
                          AccountRepository accounts, dev.themisstats.ThemisStatsServices services) {
        this.rules = rules;
        this.permissions = permissions;
        this.accounts = accounts;
        this.services = services;
    }

    // ------------------------------------------------------------------ preferences

    /**
     * Resolves the effective display settings of a player.
     *
     * <p>The personal preference is only used when the server allows personal choices. Otherwise
     * the server default is enforced, while the personal preference is kept for later.</p>
     *
     * <p>This runs on the game thread (it is called for every player on every refresh), so it only
     * reads the in memory preference cache. The cache itself is filled in the background.</p>
     */
    public String[] resolveDisplay(ServerPlayer player) {
        String serverMode = rules.defaultDisplay();
        String serverMetric = rules.defaultBoard();
        String serverGroup = rules.defaultGroup();
        if (!rules.allowPlayerChoice()) {
            return new String[]{serverMode, serverMetric, effectiveGroup(serverGroup)};
        }
        var preference = preferences.get(player.getUUID());
        if (preference == null) {
            return new String[]{serverMode, serverMetric, effectiveGroup(serverGroup)};
        }
        String mode = preference.mode() == null ? serverMode : preference.mode();
        String metric = preference.metric() == null ? serverMetric : preference.metric();
        String group = preference.identityGroup() == null ? serverGroup : preference.identityGroup();
        if (Metric.byId(metric) == null) {
            metric = serverMetric;
        }
        return new String[]{mode, metric, effectiveGroup(group)};
    }

    /**
     * Reloads the personal display preferences. This is a database read and therefore runs in the
     * background.
     */
    public void refreshPreferences() {
        try {
            preferences.clear();
            preferences.putAll(accounts.allDisplayPreferences());
        } catch (RuntimeException e) {
            dev.themisstats.ThemisStats.LOGGER.error("[ThemisStats] 读取显示偏好失败", e);
        }
    }

    /**
     * Stores a personal display preference. The write happens in the background, the in memory
     * cache is updated immediately so that the next refresh already uses it.
     */
    public void savePreference(java.util.UUID uuid, String mode, String metric, String group) {
        preferences.put(uuid, new AccountRepository.DisplayPreference(mode, metric, group));
        services.runInBackground(() -> accounts.saveDisplayPreference(uuid, mode, metric, group,
                System.currentTimeMillis()));
    }

    /**
     * @return the identity group that may actually be shown, falling back to an open group
     */
    public String effectiveGroup(String group) {
        Identity identity = Identity.byId(group);
        if (permissions.isBoardOpen(identity)) {
            return identity.id();
        }
        if (permissions.isBoardOpen(Identity.HUMAN)) {
            return Identity.HUMAN.id();
        }
        if (permissions.isBoardOpen(Identity.BOT)) {
            return Identity.BOT.id();
        }
        return "none";
    }

    // ------------------------------------------------------------------ rendering

    /**
     * Builds the content of the sidebar of one player from a board snapshot.
     */
    public SidebarContent.Content buildContent(String metricId, String groupId,
                                               List<StatsRepository.BoardEntry> entries, UUID viewer) {
        return SidebarContent.build(metricId, groupId, entries, viewer);
    }

    /**
     * Sends the given content to the player, but only when it changed.
     *
     * @return {@code true} when something was sent
     */
    public boolean apply(ServerPlayer player, SidebarContent.Content content) {
        if (content == null) {
            return false;
        }
        PlayerSidebar sidebar = sidebars.computeIfAbsent(player.getUUID(), uuid -> new PlayerSidebar());
        StringBuilder signature = new StringBuilder(content.header());
        for (SidebarContent.Line line : content.lines()) {
            signature.append('|').append(line.text());
        }
        String newSignature = signature.toString();
        if (newSignature.equals(sidebar.lastSignature)) {
            return false;
        }
        sidebar.lastSignature = newSignature;

        player.connection.send(new ClientboundSetObjectivePacket(sidebar.objective,
                ClientboundSetObjectivePacket.METHOD_ADD));
        player.connection.send(new ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR,
                sidebar.objective));
        // Remove the lines that are not needed any more.
        for (Integer key : new ArrayList<>(sidebar.holders.keySet())) {
            if (key >= content.lines().size()) {
                player.connection.send(new ClientboundSetScorePacket(sidebar.holders.get(key).getScoreboardName(),
                        sidebar.objective.getName(), 0, Optional.empty(), Optional.empty()));
                sidebar.holders.remove(key);
            }
        }
        for (int i = 0; i < content.lines().size(); i++) {
            SidebarContent.Line line = content.lines().get(i);
            ScoreHolder holder = sidebar.holders.computeIfAbsent(i, key -> new LineHolder(
                    OBJECTIVE_NAME + "_" + player.getUUID().toString().substring(0, 8) + "_" + key));
            var access = sidebar.scoreboard.getOrCreatePlayerScore(holder, sidebar.objective);
            access.set(line.sortKey());
            access.display(Component.literal(line.text()));
            player.connection.send(new ClientboundSetScorePacket(holder.getScoreboardName(),
                    sidebar.objective.getName(), line.sortKey(), Optional.of(Component.literal(line.text())),
                    Optional.of(BlankFormat.INSTANCE)));
        }
        return true;
    }

    /** Removes the sidebar of one player and releases the resources this mod owns. */
    public void release(ServerPlayer player) {
        PlayerSidebar sidebar = sidebars.remove(player.getUUID());
        if (sidebar == null) {
            return;
        }
        try {
            player.connection.send(new ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, null));
            player.connection.send(new ClientboundSetObjectivePacket(sidebar.objective,
                    ClientboundSetObjectivePacket.METHOD_REMOVE));
        } catch (RuntimeException ignored) {
            // The player may already be gone; nothing to release then.
        }
    }

    /**
     * Removes the sidebars of all players, e.g. when the server stops.
     *
     * <p>Only the resources this mod owns are revoked: the objective of this mod and the display
     * slot it held. The scoreboard of the world and the objectives of other mods are never
     * touched.</p>
     */
    public void releaseAll(net.minecraft.server.MinecraftServer server) {
        for (UUID uuid : new ArrayList<>(sidebars.keySet())) {
            PlayerSidebar sidebar = sidebars.remove(uuid);
            if (sidebar == null || server == null) {
                continue;
            }
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (player.getUUID().equals(uuid)) {
                    release(player);
                    break;
                }
            }
        }
    }

    /** A score entry whose visible text is completely under our control. */
    private static final class LineHolder implements ScoreHolder {
        private final String name;

        LineHolder(String name) {
            this.name = name;
        }

        @Override
        public String getScoreboardName() {
            return name;
        }
    }
}
