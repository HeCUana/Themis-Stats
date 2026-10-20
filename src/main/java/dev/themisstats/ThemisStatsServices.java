package dev.themisstats;

import dev.themisstats.bot.BotMonitor;
import dev.themisstats.config.RuleState;
import dev.themisstats.display.SidebarService;
import dev.themisstats.metric.ElytraTracker;
import dev.themisstats.metric.MetricPipeline;
import dev.themisstats.permission.PermissionService;
import dev.themisstats.session.SessionManager;
import dev.themisstats.storage.AccountRepository;
import dev.themisstats.storage.BackupService;
import dev.themisstats.storage.BatchWriter;
import dev.themisstats.storage.Database;
import dev.themisstats.storage.Exporter;
import dev.themisstats.storage.StatsRepository;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The services of one running world.
 *
 * <p>The instance exists for exactly one server lifetime. Everything that touches the database
 * runs on the background executor, everything that touches a Minecraft object runs on the server
 * thread.</p>
 */
public final class ThemisStatsServices implements AutoCloseable {

    private final Path worldDirectory;
    private final net.minecraft.server.MinecraftServer server;
    private final String bootId;
    private final Database database;
    private final BatchWriter writer;
    private final StatsRepository stats;
    private final AccountRepository accounts;
    private final BackupService backup;
    private final Exporter exporter;
    private final MetricPipeline pipeline;
    private final SessionManager sessions;
    private final PermissionService permissions;
    private final dev.themisstats.storage.WatchRepository watch;
    private final dev.themisstats.alert.AlertEngine alertEngine;
    private final RuleState rules;
    private final ElytraTracker elytra;
    private final SidebarService sidebar;
    private final BotMonitor botMonitor;
    private final ExecutorService databaseExecutor;
    private final long startedAtMillis = System.currentTimeMillis();

    public ThemisStatsServices(Path worldDirectory, RuleState rules,
                               net.minecraft.server.MinecraftServer server) throws Database.DatabaseUnavailableException {
        this.worldDirectory = worldDirectory;
        this.server = server;
        this.rules = rules;
        this.bootId = UUID.randomUUID().toString();
        this.database = Database.open(worldDirectory);
        this.writer = new BatchWriter(database.connection());
        this.stats = new StatsRepository(database.connection());
        this.accounts = new AccountRepository(database.connection());
        this.backup = new BackupService(database.connection(), database.dataDirectory());
        this.exporter = new Exporter(database.connection(), database.dataDirectory().resolve("exports"));
        this.pipeline = new MetricPipeline(writer);
        this.sessions = new SessionManager(pipeline, accounts, bootId);
        this.permissions = new PermissionService(rules, accounts);
        this.watch = new dev.themisstats.storage.WatchRepository(database.connection());
        this.alertEngine = new dev.themisstats.alert.AlertEngine(rules, watch);
        this.elytra = new ElytraTracker();
        this.sidebar = new SidebarService(rules, permissions, accounts, this);
        this.botMonitor = new BotMonitor(rules, sessions);
        this.databaseExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ThemisStats-DB");
            thread.setDaemon(true);
            return thread;
        });
    }

    public Path worldDirectory() {
        return worldDirectory;
    }

    public String bootId() {
        return bootId;
    }

    public long startedAtMillis() {
        return startedAtMillis;
    }

    public Database database() {
        return database;
    }

    public BatchWriter writer() {
        return writer;
    }

    public StatsRepository stats() {
        return stats;
    }

    public AccountRepository accounts() {
        return accounts;
    }

    public BackupService backup() {
        return backup;
    }

    public Exporter exporter() {
        return exporter;
    }

    public MetricPipeline pipeline() {
        return pipeline;
    }

    public SessionManager sessions() {
        return sessions;
    }

    public PermissionService permissions() {
        return permissions;
    }

    /** @return the run alert subscriptions and states */
    public dev.themisstats.storage.WatchRepository watch() {
        return watch;
    }

    /** @return the run alert engine */
    public dev.themisstats.alert.AlertEngine alertEngine() {
        return alertEngine;
    }

    /** @return the server the services belong to */
    public net.minecraft.server.MinecraftServer server() {
        return server;
    }

    public RuleState rules() {
        return rules;
    }

    public ElytraTracker elytra() {
        return elytra;
    }

    public SidebarService sidebar() {
        return sidebar;
    }

    public BotMonitor botMonitor() {
        return botMonitor;
    }

    /** @return the executor that runs all database work off the game thread */
    public ExecutorService databaseExecutor() {
        return databaseExecutor;
    }

    /** Runs a database task off the game thread. */
    public void runInBackground(Runnable task) {
        databaseExecutor.execute(() -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                ThemisStats.LOGGER.error("[ThemisStats] 后台任务失败", e);
            }
        });
    }

    @Override
    public void close() {
        sidebar.releaseAll(server);
        botMonitor.stop();
        pipeline.flush();
        writer.close();
        databaseExecutor.shutdown();
        try {
            databaseExecutor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        database.close();
    }
}
