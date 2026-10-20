package dev.themisstats.command;

import dev.themisstats.ThemisStats;
import dev.themisstats.ThemisStatsRuntime;
import dev.themisstats.alert.AlertType;
import dev.themisstats.identity.Identity;
import dev.themisstats.identity.IdentityResolver;
import dev.themisstats.metric.Metric;
import dev.themisstats.storage.AccountRepository;
import dev.themisstats.storage.StatsRepository;
import dev.themisstats.storage.WatchRepository;
import dev.themisstats.util.Numbers;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The P1 commands for the run alerts and the session comparison.
 *
 * <p>{@code watch} stores a subscription, {@code unwatch} removes it and {@code compare} shows the
 * last two sessions of an object. The subscription is stored even while the alerts are switched
 * off, so an operator can prepare the subscriptions and switch the alerts on afterwards.</p>
 */
public final class WatchCommands {

    /**
     * The services are resolved when the command runs, because the command tree is built before
     * the world is loaded.
     */
    private dev.themisstats.ThemisStatsServices services() {
        return dev.themisstats.ThemisStatsRuntime.services();
    }

    public void register(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("watch")
                .then(Commands.argument("target", StringArgumentType.string())
                        .then(Commands.argument("identity", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestions.suggestIdentities(builder))
                                .executes(ctx -> watch(ctx, null, null))
                                .then(Commands.argument("alerts", StringArgumentType.word())
                                        .suggests((ctx, builder) -> {
                                            for (AlertType type : AlertType.values()) {
                                                builder.suggest(type.id());
                                            }
                                            return builder.buildFuture();
                                        })
                                        .executes(ctx -> watch(ctx,
                                                StringArgumentType.getString(ctx, "alerts"), null))
                                        .then(Commands.argument("stallMetric", StringArgumentType.word())
                                                .suggests((ctx, builder) -> SharedSuggestions.suggestMetrics(builder))
                                                .executes(ctx -> watch(ctx,
                                                        StringArgumentType.getString(ctx, "alerts"),
                                                        StringArgumentType.getString(ctx, "stallMetric"))))))))
                .then(Commands.literal("unwatch")
                        .then(Commands.argument("target", StringArgumentType.string())
                                .then(Commands.argument("identity", StringArgumentType.word())
                                        .suggests((ctx, builder) -> SharedSuggestions.suggestIdentities(builder))
                                        .executes(ctx -> unwatch(ctx)))))
                .then(Commands.literal("compare")
                        .then(Commands.argument("target", StringArgumentType.string())
                                .then(Commands.argument("identity", StringArgumentType.word())
                                        .suggests((ctx, builder) -> SharedSuggestions.suggestIdentities(builder))
                                        .executes(ctx -> compare(ctx, null))
                                        .then(Commands.argument("metric", StringArgumentType.word())
                                                .suggests((ctx, builder) -> SharedSuggestions.suggestMetrics(builder))
                                                .executes(ctx -> compare(ctx,
                                                        StringArgumentType.getString(ctx, "metric")))))));
    }

    // ------------------------------------------------------------------ watch

    private int watch(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx, String alertId,
                      String stallMetric) {
        CommandSourceStack source = ctx.getSource();
        if (services() == null) {
            source.sendFailure(CommandTexts.error("统计模块尚未就绪，请稍后再试。"));
            return 0;
        }
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(CommandTexts.error("关注需要由玩家执行。"));
            return 0;
        }
        String targetInput = StringArgumentType.getString(ctx, "target");
        Identity identity = Identity.byId(StringArgumentType.getString(ctx, "identity"));
        if (identity == Identity.UNKNOWN) {
            source.sendFailure(CommandTexts.error("未知身份组。"));
            return 0;
        }
        UUID target = resolveUuid(targetInput);
        if (target == null) {
            source.sendFailure(CommandTexts.error("没有找到该对象，它必须已经在本地被记录过。"));
            return 0;
        }
        if (!services().permissions().canViewBotDetails(source, target, identity)) {
            source.sendFailure(CommandTexts.error("你没有查看该对象运行细节的权限，无法关注它。"));
            return 0;
        }
        int mask;
        if (alertId != null) {
            AlertType type = AlertType.byId(alertId);
            if (type == null) {
                source.sendFailure(CommandTexts.error("未知提醒类型，可用：" + String.join("、",
                        AlertType.namesIn(AlertType.allMask()))));
                return 0;
            }
            mask = type.bit();
        } else {
            // Without an explicit kind every kind is subscribed, except the "no progress" alert:
            // that one can only be subscribed when the observed metric is named as well.
            mask = AlertType.allMask() & ~AlertType.METRIC_STALL.bit();
        }
        String metric = stallMetric;
        if (metric != null && Metric.byId(metric) == null) {
            source.sendFailure(CommandTexts.error("未知指标：" + metric));
            return 0;
        }
        if ((mask & AlertType.METRIC_STALL.bit) != 0 && metric == null) {
            source.sendFailure(CommandTexts.error("要启用「指定统计停滞」，必须写明要观察的指标。"));
            return 0;
        }
        WatchRepository.Subscription subscription = new WatchRepository.Subscription(target, identity,
                player.getUUID(), mask, 6.0D, 10.0D, metric, System.currentTimeMillis());
        final int finalMask = mask;
        services().runInBackground(() -> {
            services().watch().subscribe(subscription);
            services().alertEngine().refresh();
            source.getServer().execute(() -> {
                source.sendSuccess(() -> CommandTexts.ok("已关注 " + targetInput + "（" + identity.displayName()
                        + "）。提醒类型：" + String.join("、", AlertType.namesIn(finalMask))), false);
                if (!services().rules().botAlerts()) {
                    source.sendSuccess(() -> CommandTexts.hint("提醒总开关 themisStatsBotAlerts 当前是关闭的，"
                            + "订阅已保存，打开后立即生效。"), false);
                }
                if ((finalMask & AlertType.METRIC_STALL.bit) != 0) {
                    source.sendSuccess(() -> CommandTexts.hint("停滞提醒只会说明「观察期内指定指标无增长」，"
                            + "不会断言机器故障。"), false);
                }
            });
        });
        return 1;
    }

    // ------------------------------------------------------------------ unwatch

    private int unwatch(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (services() == null) {
            source.sendFailure(CommandTexts.error("统计模块尚未就绪，请稍后再试。"));
            return 0;
        }
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(CommandTexts.error("取消关注需要由玩家执行。"));
            return 0;
        }
        String targetInput = StringArgumentType.getString(ctx, "target");
        Identity identity = Identity.byId(StringArgumentType.getString(ctx, "identity"));
        UUID target = resolveUuid(targetInput);
        if (target == null) {
            source.sendFailure(CommandTexts.error("没有找到该对象。"));
            return 0;
        }
        services().runInBackground(() -> {
            services().watch().unsubscribe(target, identity, player.getUUID());
            services().alertEngine().refresh();
            source.getServer().execute(() ->
                    source.sendSuccess(() -> CommandTexts.ok("已取消关注 " + targetInput + "。"), false));
        });
        return 1;
    }

    // ------------------------------------------------------------------ compare

    private int compare(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx, String metricId) {
        CommandSourceStack source = ctx.getSource();
        if (services() == null) {
            source.sendFailure(CommandTexts.error("统计模块尚未就绪，请稍后再试。"));
            return 0;
        }
        String targetInput = StringArgumentType.getString(ctx, "target");
        Identity identity = Identity.byId(StringArgumentType.getString(ctx, "identity"));
        UUID target = resolveUuid(targetInput);
        if (target == null) {
            source.sendFailure(CommandTexts.error("没有找到该对象。"));
            return 0;
        }
        boolean self = source.getPlayer() != null && source.getPlayer().getUUID().equals(target);
        if (!self && !services().permissions().canViewBotDetails(source, target, identity)) {
            source.sendFailure(CommandTexts.error("你没有查看该对象运行细节的权限。"));
            return 0;
        }
        Metric metric = metricId == null ? null : Metric.byId(metricId);
        if (metricId != null && metric == null) {
            source.sendFailure(CommandTexts.error("未知指标：" + metricId));
            return 0;
        }
        var server = source.getServer();
        services().runInBackground(() -> {
            List<AccountRepository.SessionInfo> sessions =
                    services().accounts().sessionsOf(target, identity, 1, 2);
            List<Component> lines = new ArrayList<>();
            lines.add(CommandTexts.title("最近两次会话对比 · " + targetInput + " · " + identity.displayName()));
            if (sessions.size() < 2) {
                lines.add(CommandTexts.line("至少需要两次会话才能对比，目前只有 "
                        + sessions.size() + " 次。"));
            } else {
                var newer = sessions.get(0);
                var older = sessions.get(1);
                lines.add(CommandTexts.value("较新一次", newer.startWall() + " 开始，在线 "
                        + Numbers.duration(newer.actualOnlineMs())));
                lines.add(CommandTexts.value("较早一次", older.startWall() + " 开始，在线 "
                        + Numbers.duration(older.actualOnlineMs())));
                long difference = newer.actualOnlineMs() - older.actualOnlineMs();
                lines.add(CommandTexts.value("时长差", (difference >= 0 ? "+" : "") + Numbers.duration(Math.abs(difference))));
                if (metric != null) {
                    long newValue = sessionMetric(newer, metric);
                    long oldValue = sessionMetric(older, metric);
                    lines.add(CommandTexts.value("指标 " + metric.displayName(),
                            Numbers.full(oldValue) + " → " + Numbers.full(newValue)
                                    + "（" + (newValue - oldValue >= 0 ? "+" : "")
                                    + Numbers.full(Math.abs(newValue - oldValue)) + "）"));
                    lines.add(CommandTexts.value("每真实分钟速率",
                            rate(newValue, older.actualOnlineMs()) + " → " + rate(newValue, newer.actualOnlineMs())));
                }
                lines.add(CommandTexts.hint("速率按真实分钟计算，低 TPS 会让每分钟的实际次数变少；"
                        + "暂停采集的区间不计入分母。"));
            }
            server.execute(() -> {
                for (Component line : lines) {
                    source.sendSuccess(() -> line, false);
                }
            });
        });
        return 1;
    }

    private long sessionMetric(AccountRepository.SessionInfo session, Metric metric) {
        long total = 0;
        for (String raw : services().accounts().sessionMetrics(session.id())) {
            String[] parts = raw.split("\\|", 3);
            if (parts.length == 3 && metric.id().equals(parts[0])) {
                total += Long.parseLong(parts[2]);
            }
        }
        return total;
    }

    private String rate(long value, long millis) {
        if (millis <= 0) {
            return "—";
        }
        double perMinute = value * 60000.0D / millis;
        return String.format(java.util.Locale.ROOT, "%.2f 次/分钟", perMinute);
    }

    // ------------------------------------------------------------------ helpers

    private UUID resolveUuid(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(input.trim());
        } catch (IllegalArgumentException ignored) {
            // not a UUID, try the name
        }
        List<AccountRepository.AccountInfo> matches = services().accounts().accountsByName(input.trim());
        return matches.size() == 1 ? matches.get(0).uuid() : null;
    }
}
