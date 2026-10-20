package dev.themisstats.scarpet;

import carpet.script.Context;
import carpet.script.Expression;
import carpet.script.Fluff;
import carpet.script.LazyValue;
import carpet.script.Token;
import carpet.script.value.ListValue;
import carpet.script.value.NumericValue;
import carpet.script.value.StringValue;
import carpet.script.value.Value;
import dev.themisstats.ThemisStatsRuntime;
import dev.themisstats.identity.Identity;
import dev.themisstats.metric.Metric;
import dev.themisstats.storage.StatsRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The query functions of Themis Stats for Carpet's Scarpet.
 *
 * <p>They are registered through Carpet's own extension hook, so they behave like every other
 * Scarpet function:</p>
 * <pre>
 * themis_stats('mining', 'bot')            -> 整个身份组的公开累计
 * themis_stats_of('mining', 'bot', 'bot_a') -> 单个对象的公开累计
 * themis_stats_top('mining', 'bot', 1)      -> 第一页的 [名字, 数值] 列表
 * </pre>
 *
 * <p>All three use the same public formula as the leaderboard, so a script never sees a different
 * number than the chat command.</p>
 */
public final class ThemisStatsScarpet {

    private ThemisStatsScarpet() {
    }

    /**
     * Registers the functions in the given expression.
     */
    public static void register(Expression expression) {
        expression.addCustomFunction("themis_stats", new Fluff.AbstractLazyFunction(2, "themis_stats") {
            @Override
            public LazyValue lazyEval(Context c, Context.Type t, Expression expr, Token token,
                                      List<LazyValue> lazyParams) {
                List<Value> args = unpackArgs(lazyParams, c, t);
                if (args.size() < 2) {
                    throw new carpet.script.exception.InternalExpressionException(
                            "'themis_stats' needs a metric and an identity group");
                }
                Metric metric = metricOf(args.get(0));
                Identity identity = identityOf(args.get(1));
                long total = total(metric, identity);
                return (ctx, type) -> new NumericValue(total);
            }

            @Override
            public boolean pure() {
                // the value comes from the live database, so it can change between two evaluations
                return false;
            }

            @Override
            public boolean transitive() {
                return false;
            }
        });

        expression.addCustomFunction("themis_stats_of",
                new Fluff.AbstractLazyFunction(3, "themis_stats_of") {
                    @Override
                    public LazyValue lazyEval(Context c, Context.Type t, Expression expr, Token token,
                                              List<LazyValue> lazyParams) {
                        List<Value> args = unpackArgs(lazyParams, c, t);
                        if (args.size() < 3) {
                            throw new carpet.script.exception.InternalExpressionException(
                                    "'themis_stats_of' needs a metric, an identity group and a name");
                        }
                        Metric metric = metricOf(args.get(0));
                        Identity identity = identityOf(args.get(1));
                        long value = valueOf(metric, identity, args.get(2).getString());
                        return (ctx, type) -> new NumericValue(value);
                    }

                    @Override
                    public boolean pure() {
                        // the value comes from the live database, so it can change between two evaluations
                        return false;
                    }

                    @Override
                    public boolean transitive() {
                        return false;
                    }
                });

        expression.addCustomFunction("themis_stats_top",
                new Fluff.AbstractLazyFunction(-1, "themis_stats_top") {
                    @Override
                    public LazyValue lazyEval(Context c, Context.Type t, Expression expr, Token token,
                                              List<LazyValue> lazyParams) {
                        List<Value> args = unpackArgs(lazyParams, c, t);
                        if (args.size() < 2) {
                            throw new carpet.script.exception.InternalExpressionException(
                                    "'themis_stats_top' needs a metric and an identity group");
                        }
                        Metric metric = metricOf(args.get(0));
                        Identity identity = identityOf(args.get(1));
                        int page = args.size() >= 3 ? (int) Double.parseDouble(args.get(2).getString()) : 1;
                        List<Value> rows = top(metric, identity, page);
                        return (ctx, type) -> ListValue.wrap(rows);
                    }

                    @Override
                    public boolean pure() {
                        // the value comes from the live database, so it can change between two evaluations
                        return false;
                    }

                    @Override
                    public boolean transitive() {
                        return false;
                    }
                });
    }

    private static Metric metricOf(Value value) {
        Metric metric = Metric.byId(value.getString().toLowerCase(Locale.ROOT));
        if (metric == null) {
            throw new carpet.script.exception.InternalExpressionException(
                    "unknown metric: " + value.getString());
        }
        return metric;
    }

    private static Identity identityOf(Value value) {
        Identity identity = Identity.byId(value.getString().toLowerCase(Locale.ROOT));
        if (identity == Identity.UNKNOWN) {
            throw new carpet.script.exception.InternalExpressionException(
                    "unknown identity group: " + value.getString());
        }
        return identity;
    }

    private static long total(Metric metric, Identity identity) {
        var services = ThemisStatsRuntime.services();
        if (services == null) {
            return 0L;
        }
        return services.stats().publicTotal(
                StatsRepository.Scope.of(metric, identity, services.rules().publicModes()));
    }

    private static long valueOf(Metric metric, Identity identity, String name) {
        var services = ThemisStatsRuntime.services();
        if (services == null) {
            return 0L;
        }
        UUID uuid = null;
        try {
            uuid = UUID.fromString(name);
        } catch (IllegalArgumentException ignored) {
            var matches = services.accounts().accountsByName(name);
            if (matches.size() == 1) {
                uuid = matches.get(0).uuid();
            }
        }
        if (uuid == null) {
            return 0L;
        }
        return services.stats().valueOf(uuid,
                StatsRepository.Scope.of(metric, identity, services.rules().publicModes()));
    }

    private static List<Value> top(Metric metric, Identity identity, int page) {
        List<Value> rows = new ArrayList<>();
        var services = ThemisStatsRuntime.services();
        if (services == null) {
            return rows;
        }
        var scope = StatsRepository.Scope.of(metric, identity, services.rules().publicModes());
        for (var entry : services.stats().board(scope, Math.max(1, page), 10)) {
            rows.add(new StringValue(entry.name()));
            rows.add(new NumericValue(entry.value()));
        }
        return rows;
    }
}
