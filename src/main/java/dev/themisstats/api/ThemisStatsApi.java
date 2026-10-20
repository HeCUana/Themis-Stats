package dev.themisstats.api;

import dev.themisstats.ThemisStatsRuntime;
import dev.themisstats.identity.Identity;
import dev.themisstats.metric.Metric;
import dev.themisstats.storage.StatsRepository;

import java.util.List;
import java.util.UUID;

/**
 * The public query interface of Themis Stats.
 *
 * <p>This is the interface another mod or a script uses to read the collected statistics. It never
 * writes and it never changes the collected data, so calling it is safe from anywhere.</p>
 *
 * <p><b>Threads.</b> The methods read the database and therefore block. They must not be called
 * from the game thread; use the background executor of the services instead.</p>
 *
 * <pre>{@code
 * ThemisStatsApi.Result result = ThemisStatsApi.top(Metric.MINING, Identity.BOT, 1, 10);
 * for (ThemisStatsApi.Entry entry : result.entries()) {
 *     System.out.println(entry.name() + ": " + entry.value());
 * }
 * }</pre>
 */
public final class ThemisStatsApi {

    /** One entry of a leaderboard. */
    public record Entry(UUID uuid, String name, long value, long rank) {
    }

    /** The result of a query. */
    public record Result(List<Entry> entries, int totalObjects, long publicTotal) {
        public Result {
            entries = List.copyOf(entries);
        }
    }

    private ThemisStatsApi() {
    }

    /** @return whether the statistics module is running */
    public static boolean available() {
        return ThemisStatsRuntime.services() != null;
    }

    /**
     * @param metric  the metric
     * @param identity the identity group
     * @param page    the 1 based page
     * @param perPage how many entries per page
     * @return the leaderboard of the public scope
     */
    public static Result top(Metric metric, Identity identity, int page, int perPage) {
        var services = ThemisStatsRuntime.services();
        if (services == null) {
            return new Result(List.of(), 0, 0L);
        }
        var scope = StatsRepository.Scope.of(metric, identity, services.rules().publicModes());
        var board = services.stats().board(scope, page, perPage);
        List<Entry> entries = new java.util.ArrayList<>(board.size());
        for (var entry : board) {
            entries.add(new Entry(entry.uuid(), entry.name(), entry.value(), entry.rank()));
        }
        return new Result(entries, services.stats().boardSize(scope),
                services.stats().publicTotal(scope));
    }

    /**
     * @return the public value of one object, using the same formula as the leaderboard
     */
    public static long valueOf(UUID uuid, Metric metric, Identity identity) {
        var services = ThemisStatsRuntime.services();
        if (services == null) {
            return 0L;
        }
        return services.stats().valueOf(uuid,
                StatsRepository.Scope.of(metric, identity, services.rules().publicModes()));
    }

    /**
     * @return the per block, per entity or per reason breakdown of one object
     */
    public static java.util.Map<String, Long> breakdown(UUID uuid, Metric metric, Identity identity) {
        var services = ThemisStatsRuntime.services();
        if (services == null) {
            return java.util.Map.of();
        }
        return services.stats().categoryBreakdown(uuid, identity, metric);
    }

    /**
     * @return the number of online objects per identity
     */
    public static java.util.Map<Identity, Integer> onlineCounts() {
        var services = ThemisStatsRuntime.services();
        if (services == null) {
            return java.util.Map.of();
        }
        return services.sessions().onlineCounts();
    }
}
