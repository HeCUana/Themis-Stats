package dev.themisstats.config;

import carpet.CarpetExtension;
import carpet.CarpetServer;
import dev.themisstats.ThemisStats;
import dev.themisstats.ThemisStatsRuntime;
import dev.themisstats.ThemisStatsServices;
import dev.themisstats.command.ThemisStatsCommand;
import dev.themisstats.identity.Identity;
import dev.themisstats.metric.Metric;
import dev.themisstats.session.SessionEndReason;
import dev.themisstats.storage.StatsRepository;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.DisplaySlot;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Carpet extension entry point of Themis Stats.
 *
 * <p><strong>Rule registration.</strong> The 18 Themis Stats rules are registered into Carpet's
 * <em>main</em> settings manager - the very same manager that backs {@code /carpet} - inside
 * {@link #onGameStarted()}. This is deliberately <em>not</em> done through
 * {@link CarpetExtension#extensionSettingsManager()}: returning a custom manager would create a
 * second, competing rule command instead of extending {@code /carpet}. Because the main manager
 * has the identifier {@code carpet}, the rules are persisted in {@code <world>/carpet.conf} and
 * therefore honour Carpet's own {@code setDefault} / {@code removeDefault} semantics.</p>
 *
 * <p><strong>Ordering.</strong> Carpet calls {@code CarpetServer#onGameStarted()} from its
 * {@code server} entrypoint, which Fabric Loader runs after every {@code main} entrypoint. The
 * extension is therefore already registered in {@code CarpetServer.extensions} by the time this
 * method runs, and the Chinese rule texts are available through
 * {@link #canHasTranslations(String)}.</p>
 */
public final class ThemisStatsExtension implements CarpetExtension {

    private final RuleState ruleState = new RuleState();
    private final ThemisStatsCommand command = new ThemisStatsCommand();

    private ThemisStatsServices services;
    private int tickCounter;
    private long lastSidebarRefresh;
    private final Map<String, List<StatsRepository.BoardEntry>> boardCache = new ConcurrentHashMap<>();
    private final Map<UUID, Long> carouselState = new ConcurrentHashMap<>();

    @Override
    public void onGameStarted() {
        if (CarpetServer.settingsManager == null) {
            throw new IllegalStateException("Themis Stats requires Carpet: CarpetServer.settingsManager is null");
        }
        // Register the 18 rules into Carpet's MAIN settings manager => /carpet manages them.
        CarpetServer.settingsManager.parseSettingsClass(ThemisStatsRules.class);
        // Observe rule changes so that caches and visibility are invalidated immediately.
        CarpetServer.settingsManager.registerRuleObserver(this.ruleState::onRuleChanged);
        this.ruleState.captureInitialState();
        ThemisStats.LOGGER.info("[ThemisStats] registered the themisStats rule category ({} rules) in the /carpet manager",
                RuleState.ruleNames().size());
    }

    @Override
    public Map<String, String> canHasTranslations(String lang) {
        return RuleTexts.forLanguage(lang);
    }

    @Override
    public void onServerLoaded(MinecraftServer server) {
        Path worldDirectory = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
        try {
            services = new ThemisStatsServices(worldDirectory, ruleState, server);
            ThemisStatsRuntime.install(services);
            command.setServices(services);
            recoverUnfinishedSessions();
            // The collection switch may have been turned off before the server started.
            services.sessions().setCollecting(ruleState.recording());
            ThemisStats.LOGGER.info("[ThemisStats] statistics module ready, database {}", services.database().databaseFile());
        } catch (dev.themisstats.storage.Database.DatabaseUnavailableException e) {
            // The statistics module is disabled, the server keeps running.
            ThemisStats.LOGGER.error("[ThemisStats] 统计模块已停用：{}", e.getMessage());
        }
    }

    @Override
    public void onServerClosed(MinecraftServer server) {
        if (services == null) {
            return;
        }
        try {
            // A normal shutdown settles the sessions, so the online time is not lost.
            services.sessions().settleAll(SessionEndReason.SERVER_SHUTDOWN);
            services.pipeline().flush();
            services.writer().flush(15_000L);
        } finally {
            ThemisStatsRuntime.uninstall();
            services.close();
            services = null;
            ThemisStats.LOGGER.info("[ThemisStats] statistics module stopped");
        }
    }

    @Override
    public void onTick(MinecraftServer server) {
        if (services == null) {
            return;
        }
        tickCounter++;
        // Every second: the bot sample interval and the session accounting.
        if (tickCounter % 20 == 0) {
            services.sessions().liveSessions().forEach(live -> {
                var session = live.session();
                session.advanceTo(System.nanoTime());
            });
            services.botMonitor().sampleIfDue(server);
            // The alerts read the health and the durability on the game thread; everything that
            // needs the database runs in the background.
            services.alertEngine().evaluateOnGameThread(server.getPlayerList().getPlayers());
            refreshSidebars(server);
        }
        // Every five seconds: hand the buffered increments over to the background writer.
        if (tickCounter % 100 == 0) {
            services.pipeline().flush();
            // The personal display preferences are read in the background, the game thread only
            // reads the in memory cache.
            services.runInBackground(services.sidebar()::refreshPreferences);
            // The alert subscriptions are reloaded in the background as well.
            services.alertEngine().refresh();
        }
    }

    @Override
    public void onPlayerLoggedIn(ServerPlayer player) {
        if (services == null) {
            return;
        }
        services.sessions().onPlayerLogin(player);
    }

    @Override
    public void onPlayerLoggedOut(ServerPlayer player) {
        if (services == null) {
            return;
        }
        services.sessions().onPlayerLogout(player);
        services.elytra().forget(player.getUUID());
        services.botMonitor().forget(player.getUUID());
        services.sidebar().release(player);
        carouselState.remove(player.getUUID());
    }

    @Override
    public void registerCommands(com.mojang.brigadier.CommandDispatcher<CommandSourceStack> dispatcher,
                                 CommandBuildContext buildContext) {
        command.register(dispatcher, buildContext);
        // The command tree is serialised for every player that joins. An argument type that the
        // vanilla client does not know would make the serialisation throw and disconnect the
        // player, so the tree is checked once, here.
        dev.themisstats.command.ArgumentTypeSelfCheck.verify(dispatcher);
    }

    /**
     * Registers the query functions for Carpet's Scarpet.
     *
     * <p>This is Carpet's own extension hook, so the functions behave like every other Scarpet
     * function and are available in every script of the server.</p>
     */
    @Override
    public void scarpetApi(carpet.script.CarpetExpression expression) {
        dev.themisstats.scarpet.ThemisStatsScarpet.register(expression.getExpr());
    }

    @Override
    public String version() {
        return ThemisStats.VERSION;
    }

    /** @return the live rule state, shared by every consumer */
    public RuleState ruleState() {
        return ruleState;
    }

    // ------------------------------------------------------------------ internals

    /**
     * Sessions that were still open when the server stopped unexpectedly are marked as an
     * abnormal termination. Their data up to the last checkpoint is kept, and the downtime
     * between the sessions is never counted as online time.
     */
    private void recoverUnfinishedSessions() {
        var unfinished = services.accounts().unfinishedSessions(services.bootId());
        for (var session : unfinished) {
            services.accounts().audit("session.recovered", session.id(),
                    "异常中断的遗留会话已标记", "系统", System.currentTimeMillis());
        }
        if (!unfinished.isEmpty()) {
            ThemisStats.LOGGER.warn("[ThemisStats] 发现 {} 个未正常结束的会话，已标记为异常中断", unfinished.size());
        }
    }

    /**
     * Refreshes the sidebars of all players.
     *
     * <p>The board is read in the background and shared by every player that asked for the same
     * scope, so that a server with many sidebar users does not run one database query per
     * player.</p>
     */
    private void refreshSidebars(MinecraftServer server) {
        if (services == null) {
            return;
        }
        if (!ruleState.sidebar()) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                services.sidebar().release(player);
            }
            return;
        }
        long now = System.currentTimeMillis();
        long refreshMillis = Math.max(1, ruleState.refreshSeconds()) * 1000L;
        boolean due = now - lastSidebarRefresh >= refreshMillis;
        if (due) {
            lastSidebarRefresh = now;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            String[] display = services.sidebar().resolveDisplay(player);
            String mode = display[0];
            if ("off".equals(mode)) {
                services.sidebar().release(player);
                continue;
            }
            if (!services.permissions().canQueryPublic(player.createCommandSourceStack())) {
                // PublicQuery is off: the public sidebar is closed for ordinary players.
                services.sidebar().release(player);
                continue;
            }
            if (isSidebarTaken(server)) {
                // Another sidebar provider owns the display slot: we yield instead of fighting.
                services.sidebar().release(player);
                continue;
            }
            String metricId = display[1];
            String groupId = display[2];
            if ("carousel".equals(mode)) {
                metricId = carouselMetric(player, metricId, groupId, now);
            }
            String scopeKey = metricId + "|" + groupId;
            List<StatsRepository.BoardEntry> entries = boardCache.get(scopeKey);
            if (entries == null) {
                if (!due) {
                    continue;
                }
                Metric metric = Metric.byId(metricId);
                Identity identity = Identity.byId(groupId);
                if (metric == null || identity == Identity.UNKNOWN) {
                    continue;
                }
                final String key = scopeKey;
                services.runInBackground(() -> {
                    List<StatsRepository.BoardEntry> loaded = services.stats().ranked(
                            StatsRepository.Scope.of(metric, identity));
                    boardCache.put(key, loaded);
                });
                continue;
            }
            services.sidebar().apply(player, services.sidebar().buildContent(metricId, groupId, entries,
                    player.getUUID()));
        }
    }

    /**
     * @return whether another provider already uses the sidebar display slot of the world
     */
    private boolean isSidebarTaken(MinecraftServer server) {
        var objective = server.getScoreboard().getDisplayObjective(DisplaySlot.SIDEBAR);
        return objective != null && !objective.getName().startsWith("themisstats");
    }

    /**
     * @return the metric the carousel currently shows for the given player
     */
    private String carouselMetric(ServerPlayer player, String currentMetric, String groupId, long now) {
        long interval = Math.max(5, ruleState.carouselSeconds()) * 1000L;
        Long last = carouselState.get(player.getUUID());
        if (last != null && now - last < interval && currentMetric != null) {
            return currentMetric;
        }
        carouselState.put(player.getUUID(), now);
        List<Metric> metrics = new ArrayList<>();
        for (Metric metric : Metric.values()) {
            if (metric.isModeClassified() || !Identity.HUMAN.id().equals(groupId)) {
                metrics.add(metric);
            }
        }
        if (metrics.isEmpty()) {
            return currentMetric;
        }
        int index = metrics.indexOf(Metric.byId(currentMetric));
        Metric next = metrics.get((index + 1) % metrics.size());
        return next.id();
    }
}
