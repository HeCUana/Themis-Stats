package dev.themisstats.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.themisstats.ThemisStatsServices;
import dev.themisstats.identity.Identity;
import dev.themisstats.metric.Metric;
import dev.themisstats.storage.AccountRepository;
import dev.themisstats.storage.StatsRepository;
import dev.themisstats.util.Numbers;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * The {@code /themisstats} command tree.
 *
 * <p>All queries answer privately by default. A result is only sent when the permission is still
 * valid at the moment the result is delivered, so a rule or permission change takes effect
 * immediately and a cached page can never leak data that is no longer allowed.</p>
 */
public final class ThemisStatsCommand {

    /** How many entries one page of a leaderboard shows. */
    private static final int PAGE_SIZE = 10;

    /** The maximum number of entries a single chat query may show. */
    private static final int MAX_PAGE_SIZE = 20;

    private volatile ThemisStatsServices services;
    private final ConfirmTokens tokens = new ConfirmTokens();
    private final AdminCommands adminCommands;

    public ThemisStatsCommand() {
        this.adminCommands = new AdminCommands(tokens);
    }

    /**
     * The services become available when the world is loaded, which happens after the command
     * dispatcher was built. The command therefore resolves them at execution time.
     */
    public void setServices(ThemisStatsServices services) {
        this.services = services;
        this.adminCommands.setServices(services);
    }

    private ThemisStatsServices services() {
        return services;
    }

    public ConfirmTokens tokens() {
        return tokens;
    }

    // ------------------------------------------------------------------ registration

    public void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext buildContext) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("themisstats")
                .executes(this::openMenu)
                .then(Commands.literal("help").executes(this::help))
                .then(Commands.literal("me").executes(this::me))
                .then(Commands.literal("player")
                        .then(Commands.argument("target", StringArgumentType.string())
                                .executes(ctx -> player(ctx, null))
                                .then(Commands.argument("identity", StringArgumentType.word())
                                        .suggests((ctx, builder) -> SharedSuggestions.suggestIdentities(builder))
                                        .executes(ctx -> player(ctx,
                                                Identity.byId(StringArgumentType.getString(ctx, "identity")))))))
                .then(Commands.literal("top")
                        .then(Commands.argument("metric", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestions.suggestMetrics(builder))
                                .executes(ctx -> top(ctx, null, 1))
                                .then(Commands.argument("identity", StringArgumentType.word())
                                        .suggests((ctx, builder) -> SharedSuggestions.suggestIdentities(builder))
                                        .executes(ctx -> top(ctx,
                                                Identity.byId(StringArgumentType.getString(ctx, "identity")), 1))
                                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                                .executes(ctx -> top(ctx, null,
                                                        IntegerArgumentType.getInteger(ctx, "page")))))
                                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                        .executes(ctx -> top(ctx, null, IntegerArgumentType.getInteger(ctx, "page"))))))
                .then(Commands.literal("rank")
                        .then(Commands.argument("metric", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestions.suggestMetrics(builder))
                                .executes(ctx -> rank(ctx, null))
                                .then(Commands.argument("identity", StringArgumentType.word())
                                        .suggests((ctx, builder) -> SharedSuggestions.suggestIdentities(builder))
                                        .executes(ctx -> rank(ctx,
                                                Identity.byId(StringArgumentType.getString(ctx, "identity")))))))
                .then(Commands.literal("detail")
                        .then(Commands.argument("target", StringArgumentType.string())
                                .then(Commands.argument("identity", StringArgumentType.word())
                                        .suggests((ctx, builder) -> SharedSuggestions.suggestIdentities(builder))
                                        .then(Commands.argument("metric", StringArgumentType.word())
                                                .suggests((ctx, builder) -> SharedSuggestions.suggestMetrics(builder))
                                                .executes(this::detail)))))
                .then(Commands.literal("board")
                        .executes(ctx -> boardUsage(ctx))
                        .then(Commands.literal("off").executes(this::boardOff))
                        .then(Commands.literal("default").executes(this::boardDefault))
                        .then(Commands.literal("carousel")
                                .executes(ctx -> boardSet(ctx, "carousel", null, null))
                                .then(Commands.argument("identity", StringArgumentType.word())
                                        .suggests((ctx, builder) -> SharedSuggestions.suggestIdentities(builder))
                                        .executes(ctx -> boardSet(ctx, "carousel", null,
                                                StringArgumentType.getString(ctx, "identity")))))
                        .then(Commands.literal("fixed")
                                .then(Commands.argument("metric", StringArgumentType.word())
                                        .suggests((ctx, builder) -> SharedSuggestions.suggestMetrics(builder))
                                        .executes(ctx -> boardSet(ctx, "fixed",
                                                StringArgumentType.getString(ctx, "metric"), null))
                                        .then(Commands.argument("identity", StringArgumentType.word())
                                                .suggests((ctx, builder) -> SharedSuggestions.suggestIdentities(builder))
                                                .executes(ctx -> boardSet(ctx, "fixed",
                                                        StringArgumentType.getString(ctx, "metric"),
                                                        StringArgumentType.getString(ctx, "identity")))))))
                .then(Commands.literal("bots")
                        .executes(ctx -> bots(ctx, 1))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(ctx -> bots(ctx, IntegerArgumentType.getInteger(ctx, "page")))))
                .then(Commands.literal("bot")
                        .then(Commands.argument("target", StringArgumentType.greedyString())
                                .executes(this::bot)))
                .then(Commands.literal("sessions")
                        .then(Commands.argument("target", StringArgumentType.string())
                                .then(Commands.argument("identity", StringArgumentType.word())
                                        .suggests((ctx, builder) -> SharedSuggestions.suggestIdentities(builder))
                                        .executes(ctx -> sessions(ctx, 1))
                                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                                .executes(ctx -> sessions(ctx,
                                                        IntegerArgumentType.getInteger(ctx, "page")))))))
                .then(Commands.literal("session")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(this::session)))
                .then(Commands.literal("explain")
                        .then(Commands.argument("metric", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestions.suggestMetrics(builder))
                                .executes(this::explain)));
        // 工程累计：<工程名> [指标] [页码]
        // The project name is a single word: a greedy string would swallow the metric behind it.
        var projectName = Commands.argument("project", StringArgumentType.word());
        var projectMetric = Commands.argument("metric", StringArgumentType.word())
                .suggests((ctx, builder) -> SharedSuggestions.suggestMetrics(builder));
        var projectPage = Commands.argument("page", IntegerArgumentType.integer(1));
        root.then(Commands.literal("project")
                .then(projectName
                        .executes(ctx -> project(ctx, null, 1))
                        .then(projectMetric
                                .executes(ctx -> project(ctx,
                                        StringArgumentType.getString(ctx, "metric"), 1))
                                .then(projectPage
                                        .executes(ctx -> project(ctx,
                                                StringArgumentType.getString(ctx, "metric"),
                                                IntegerArgumentType.getInteger(ctx, "page")))))));

        // 细分榜：<指标> <分类> [身份] [页码]
        var fineMetric = Commands.argument("metric", StringArgumentType.word())
                .suggests((ctx, builder) -> SharedSuggestions.suggestMetrics(builder));
        var fineCategory = Commands.argument("category", TokenArgumentType.INSTANCE);
        var fineIdentity = Commands.argument("identity", StringArgumentType.word())
                .suggests((ctx, builder) -> SharedSuggestions.suggestIdentities(builder));
        var finePage = Commands.argument("page", IntegerArgumentType.integer(1));
        root.then(Commands.literal("fine")
                .then(fineMetric.then(fineCategory
                        .executes(ctx -> fine(ctx, Identity.HUMAN, 1))
                        .then(fineIdentity
                                .executes(ctx -> fine(ctx,
                                        resolveIdentity(StringArgumentType.getString(ctx, "identity"),
                                                Identity.HUMAN), 1))
                                .then(finePage
                                        .executes(ctx -> fine(ctx,
                                                resolveIdentity(StringArgumentType.getString(ctx, "identity"),
                                                        Identity.HUMAN),
                                                IntegerArgumentType.getInteger(ctx, "page"))))))));

        adminCommands.register(root);
        new WatchCommands().register(root);

        var rootNode = dispatcher.register(root);

        // The optional alias is only registered when nothing else uses the name.
        boolean taken = dispatcher.getRoot().getChildren().stream()
                .anyMatch(node -> node.getName().equalsIgnoreCase("tstats"));
        if (!taken) {
            dispatcher.register(Commands.literal("tstats").redirect(rootNode));
        }
    }

    // ------------------------------------------------------------------ helpers

    private void asyncQuery(CommandSourceStack source, BooleanSupplier allowed, Supplier<List<Component>> work) {
        var server = source.getServer();
        if (server == null || services() == null) {
            source.sendFailure(CommandTexts.error("统计模块尚未就绪，请稍后再试。"));
            return;
        }
        services().runInBackground(() -> {
            List<Component> result;
            try {
                result = work.get();
            } catch (RuntimeException e) {
                result = List.of(CommandTexts.error("查询失败：" + e.getMessage()));
            }
            List<Component> finalResult = result;
            server.execute(() -> {
                // The permission is checked again right before the result is sent.
                if (!allowed.getAsBoolean()) {
                    source.sendFailure(CommandTexts.error("权限或规则已变化，结果未发送。"));
                    return;
                }
                for (Component component : finalResult) {
                    source.sendSuccess(() -> component, false);
                }
            });
        });
    }

    /** The resolved target of a query. */
    private record Target(UUID uuid, String name, List<AccountRepository.AccountInfo> ambiguous) {
    }

    /**
     * Resolves a name or a UUID to exactly one account.
     *
     * @return the target, or {@code null} when the input was empty
     */
    private Target resolveTarget(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        String trimmed = input.trim();
        try {
            UUID uuid = UUID.fromString(trimmed);
            AccountRepository.AccountInfo account = services().accounts().accountByUuid(uuid);
            return new Target(uuid, account == null ? trimmed : account.currentName(), List.of());
        } catch (IllegalArgumentException ignored) {
            // Not a UUID, treat it as a name.
        }
        List<AccountRepository.AccountInfo> matches = services().accounts().accountsByName(trimmed);
        if (matches.isEmpty()) {
            return new Target(null, trimmed, List.of());
        }
        if (matches.size() > 1) {
            return new Target(null, trimmed, matches);
        }
        AccountRepository.AccountInfo account = matches.get(0);
        return new Target(account.uuid(), account.currentName(), List.of());
    }


    /**
     * Resolves the target of a query and runs the query in the background.
     *
     * <p>The name lookup is a database read, so it must never run on the game thread.</p>
     */
    private void asyncTargetQuery(CommandSourceStack source, String input,
                                  java.util.function.Function<Target, BooleanSupplier> allowedFactory,
                                  java.util.function.Function<Target, List<Component>> work) {
        var server = source.getServer();
        if (server == null || services() == null) {
            source.sendFailure(CommandTexts.error("统计模块尚未就绪，请稍后再试。"));
            return;
        }
        services().runInBackground(() -> {
            List<Component> result;
            BooleanSupplier allowed = () -> true;
            try {
                Target target = resolveTarget(input);
                if (target == null) {
                    result = List.of(CommandTexts.error("请指定要查询的对象。"));
                } else if (!target.ambiguous().isEmpty()) {
                    List<Component> lines = new ArrayList<>();
                    lines.add(CommandTexts.error("名称「" + input + "」对应多个账号，请使用 UUID 指定："));
                    for (AccountRepository.AccountInfo account : target.ambiguous()) {
                        lines.add(CommandTexts.hint("  " + account.currentName() + " = " + account.uuid()));
                    }
                    result = lines;
                } else if (target.uuid() == null) {
                    result = List.of(CommandTexts.error("没有找到名为「" + input + "」的本地账号。"));
                } else {
                    allowed = allowedFactory.apply(target);
                    result = work.apply(target);
                }
            } catch (RuntimeException e) {
                result = List.of(CommandTexts.error("查询失败：" + e.getMessage()));
            }
            List<Component> finalResult = result;
            BooleanSupplier finalAllowed = allowed;
            server.execute(() -> {
                // The permission is checked again right before the result is sent.
                if (!finalAllowed.getAsBoolean()) {
                    source.sendFailure(CommandTexts.error("权限或规则已变化，结果未发送。"));
                    return;
                }
                for (Component component : finalResult) {
                    source.sendSuccess(() -> component, false);
                }
            });
        });
    }

    private Identity resolveIdentity(String identityId, Identity fallback) {
        if (identityId == null) {
            return fallback;
        }
        Identity identity = Identity.byId(identityId);
        return identity == Identity.UNKNOWN ? fallback : identity;    }

    private static StatsRepository.Scope scopeOf(Metric metric, Identity identity) {
        return StatsRepository.Scope.of(metric, identity);
    }

    // ------------------------------------------------------------------ commands

    private int openMenu(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        source.sendSuccess(() -> CommandTexts.title("Themis Stats 统计"), false);
        source.sendSuccess(() -> CommandTexts.line("我的数据、公开排行、假人列表与显示设置"), false);
        source.sendSuccess(() -> CommandTexts.hint("/themisstats me | top | rank | bots | board | explain | help"), false);
        return 1;
    }

    private int help(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        source.sendSuccess(() -> CommandTexts.title("Themis Stats 用法"), false);
        source.sendSuccess(() -> CommandTexts.line("/themisstats me - 查看本人资料"), false);
        source.sendSuccess(() -> CommandTexts.line("/themisstats player <对象> [身份] - 查询指定对象"), false);
        source.sendSuccess(() -> CommandTexts.line("/themisstats top <指标> [身份] [页码] - 公开累计榜"), false);
        source.sendSuccess(() -> CommandTexts.line("/themisstats rank <指标> [身份] - 查看名次与差距"), false);
        source.sendSuccess(() -> CommandTexts.line("/themisstats detail <对象> <身份> <指标> - 细分类累计"), false);
        source.sendSuccess(() -> CommandTexts.line("/themisstats board off|fixed <指标> [身份]|carousel [身份]|default - 侧边栏"), false);
        source.sendSuccess(() -> CommandTexts.line("/themisstats bots [页码] - 假人列表"), false);
        source.sendSuccess(() -> CommandTexts.line("/themisstats bot <对象> - 假人档案与状态"), false);
        source.sendSuccess(() -> CommandTexts.line("/themisstats sessions <对象> <身份> [页码] - 会话列表"), false);
        source.sendSuccess(() -> CommandTexts.line("/themisstats session <会话ID> - 会话详情"), false);
        source.sendSuccess(() -> CommandTexts.line("/themisstats explain <指标> - 统计口径"), false);
        source.sendSuccess(() -> CommandTexts.hint("全服规则请使用 /carpet list themisStats"), false);
        return 1;
    }

    private int me(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(CommandTexts.error("该命令只能由玩家执行。"));
            return 0;
        }
        UUID uuid = player.getUUID();
        asyncQuery(source, () -> true, () -> {
            List<Component> lines = new ArrayList<>();
            lines.add(CommandTexts.title("我的数据"));
            lines.add(CommandTexts.value("名称", player.getGameProfile().name()));
            lines.add(CommandTexts.value("账号", uuid.toString()));
            List<String> names = services().accounts().namesOf(uuid);
            if (names.size() > 1) {
                lines.add(CommandTexts.value("历史名称", String.join("、", names)));
            }
            AccountRepository.AccountInfo account = services().accounts().accountByUuid(uuid);
            if (account != null) {
                lines.add(CommandTexts.value("采集起点", new java.util.Date(account.firstSeen()).toString()));
            }
            for (Identity identity : new Identity[]{Identity.HUMAN, Identity.BOT, Identity.SHADOW}) {
                long total = 0;
                List<String> parts = new ArrayList<>();
                for (Metric metric : Metric.values()) {
                    long value = services().stats().valueOf(uuid, scopeOf(metric, identity));
                    if (value > 0) {
                        total += value;
                        parts.add(metric.displayName() + " " + CommandTexts.format(metric, value));
                    }
                }
                if (!parts.isEmpty()) {
                    lines.add(CommandTexts.value(identity.displayName() + "累计", String.join("，", parts)));
                }
            }
            var session = services().sessions().sessionOf(player);
            if (session != null) {
                lines.add(CommandTexts.value("本次在线", Numbers.duration(session.onlineMillis())));
                lines.add(CommandTexts.value("有效采集", Numbers.duration(session.collectedMillis())));
                lines.add(CommandTexts.hint("本次会话统计为实时视图，尚未提交的增量已单独标注。"));
            }
            String exclusion = services().accounts().exclusionReason(uuid, Identity.HUMAN);
            if (exclusion != null) {
                lines.add(CommandTexts.error("你已被标记为测试／排除对象：" + exclusion));
            }
            return lines;
        });
        return 1;
    }

    private int player(CommandContext<CommandSourceStack> ctx, Identity identity) {
        CommandSourceStack source = ctx.getSource();
        String input = StringArgumentType.getString(ctx, "target");
        Identity effective = resolveIdentity(identity == null ? null : identity.id(), Identity.HUMAN);
        asyncTargetQuery(source, input,
                target -> () -> source.getPlayer() != null
                        && source.getPlayer().getUUID().equals(target.uuid())
                        || services().permissions().canQueryPublic(source),
                target -> {
            List<Component> lines = new ArrayList<>();
            lines.add(CommandTexts.title("玩家数据 · " + target.name()));
            lines.add(CommandTexts.value("账号", target.uuid().toString()));
            for (Identity group : new Identity[]{Identity.HUMAN, Identity.BOT, Identity.SHADOW}) {
                if (group == Identity.SHADOW && !services().permissions().isAdmin(source)) {
                    continue;
                }
                List<String> parts = new ArrayList<>();
                for (Metric metric : Metric.values()) {
                    long value = services().stats().valueOf(target.uuid(), scopeOf(metric, group));
                    if (value > 0) {
                        parts.add(metric.displayName() + " " + CommandTexts.format(metric, value));
                    }
                }
                if (!parts.isEmpty()) {
                    lines.add(CommandTexts.value(group.displayName() + "累计", String.join("，", parts)));
                }
            }
            if (effective == Identity.SHADOW && !services().permissions().isAdmin(source)
                    && !(source.getPlayer() != null
                    && source.getPlayer().getUUID().equals(target.uuid()))) {
                lines.add(CommandTexts.hint("Shadow 数据仅本人与管理员可见。"));
            }
            return lines;
                });
        return 1;
    }

    private int top(CommandContext<CommandSourceStack> ctx, Identity identity, int page) {
        CommandSourceStack source = ctx.getSource();
        String metricId = StringArgumentType.getString(ctx, "metric");
        Metric metric = Metric.byId(metricId);
        if (metric == null) {
            source.sendFailure(CommandTexts.error("未知指标：" + metricId + "，可用指标：" + Metric.idList()));
            return 0;
        }
        Identity group = resolveIdentity(identity == null ? null : identity.id(), Identity.HUMAN);
        if (!services().permissions().isBoardOpen(group)) {
            source.sendFailure(CommandTexts.error(group.displayName() + "公共榜当前已关闭。"));
            return 0;
        }
        BooleanSupplier allowed = () -> services().permissions().canQueryPublic(source);
        asyncQuery(source, allowed, () -> {
            List<Component> lines = new ArrayList<>();
            List<StatsRepository.BoardEntry> entries = services().stats().board(scopeOf(metric, group), page,
                    Math.min(PAGE_SIZE, MAX_PAGE_SIZE));
            int total = services().stats().boardSize(scopeOf(metric, group));
            lines.add(CommandTexts.title("排行榜 · " + metric.displayName() + " · " + group.displayName()));
            lines.add(CommandTexts.hint(metric.isModeClassified()
                    ? "口径：生存＋冒险模式；数值不含未保存增量"
                    : "口径：" + group.displayName() + "实际在线时长；数值不含未保存增量"));
            if (entries.isEmpty()) {
                lines.add(CommandTexts.line("暂无数据。"));
                return lines;
            }
            for (StatsRepository.BoardEntry entry : entries) {
                lines.add(Component.literal("§8#" + entry.rank() + " §f" + entry.name() + " §8- §a"
                        + CommandTexts.format(metric, entry.value())));
            }
            lines.add(CommandTexts.hint("第 " + page + " 页，共 " + total + " 个对象；全服总计 "
                    + Numbers.full(services().stats().publicTotal(scopeOf(metric, group)))));
            return lines;
        });
        return 1;
    }

    private int rank(CommandContext<CommandSourceStack> ctx, Identity identity) {
        CommandSourceStack source = ctx.getSource();
        String metricId = StringArgumentType.getString(ctx, "metric");
        Metric metric = Metric.byId(metricId);
        if (metric == null) {
            source.sendFailure(CommandTexts.error("未知指标：" + metricId + "，可用指标：" + Metric.idList()));
            return 0;
        }
        Identity group = resolveIdentity(identity == null ? null : identity.id(), Identity.HUMAN);
        ServerPlayer player = source.getPlayer();
        UUID uuid = player == null ? null : player.getUUID();
        if (uuid == null) {
            source.sendFailure(CommandTexts.error("该命令只能由玩家执行。"));
            return 0;
        }
        asyncQuery(source, () -> true, () -> {
            List<Component> lines = new ArrayList<>();
            StatsRepository.RankInfo info = services().stats().rankOf(uuid, scopeOf(metric, group));
            lines.add(CommandTexts.title("我的名次 · " + metric.displayName() + " · " + group.displayName()));
            if (info.value() <= 0) {
                lines.add(CommandTexts.line("你还没有该指标的正数记录，暂无排名。"));
                return lines;
            }
            lines.add(CommandTexts.value("名次", "#" + info.rank() + " / " + info.totalRanked()));
            lines.add(CommandTexts.value("累计", CommandTexts.format(metric, info.value())));
            if (!services().permissions().canQueryPublic(source)) {
                lines.add(CommandTexts.hint("公共查询已关闭，因此不显示相邻名次。"));
                return lines;
            }
            for (StatsRepository.BoardEntry entry : info.above()) {
                lines.add(Component.literal("§8↑ §7#" + info.rank() + " 之前：§f" + entry.name() + " §8- §a"
                        + CommandTexts.format(metric, entry.value())));
            }
            for (StatsRepository.BoardEntry entry : info.below()) {
                lines.add(Component.literal("§8↓ §7#" + info.rank() + " 之后：§f" + entry.name() + " §8- §a"
                        + CommandTexts.format(metric, entry.value())));
            }
            return lines;
        });
        return 1;
    }

    private int detail(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String input = StringArgumentType.getString(ctx, "target");
        Identity group = resolveIdentity(StringArgumentType.getString(ctx, "identity"), Identity.HUMAN);
        Metric metric = Metric.byId(StringArgumentType.getString(ctx, "metric"));
        if (metric == null) {
            source.sendFailure(CommandTexts.error("未知指标。"));
            return 0;
        }
        asyncTargetQuery(source, input,
                target -> () -> source.getPlayer() != null
                        && source.getPlayer().getUUID().equals(target.uuid())
                        || services().permissions().canQueryPublic(source),
                target -> {
            List<Component> lines = new ArrayList<>();
            lines.add(CommandTexts.title("细分类 · " + target.name() + " · " + metric.displayName()));
            var breakdown = services().stats().categoryBreakdown(target.uuid(), group, metric);
            if (breakdown.isEmpty()) {
                lines.add(CommandTexts.line("该对象没有该指标的分类累计。"));
                return lines;
            }
            int index = 1;
            for (var entry : breakdown.entrySet()) {
                lines.add(Component.literal("§8" + index++ + ". §f" + entry.getKey() + " §8- §a"
                        + Numbers.full(entry.getValue())));
                if (index > 20) {
                    break;
                }
            }
            return lines;
                });
        return 1;
    }

    private int boardUsage(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        source.sendSuccess(() -> CommandTexts.title("侧边栏设置"), false);
        source.sendSuccess(() -> CommandTexts.line("/themisstats board off - 关闭"), false);
        source.sendSuccess(() -> CommandTexts.line("/themisstats board fixed <指标> [身份] - 固定榜单"), false);
        source.sendSuccess(() -> CommandTexts.line("/themisstats board carousel [身份] - 自动轮播"), false);
        source.sendSuccess(() -> CommandTexts.line("/themisstats board default - 恢复服务器默认"), false);
        return 1;
    }

    private int boardOff(CommandContext<CommandSourceStack> ctx) {
        return saveBoard(ctx, "off", null, null);
    }

    private int boardDefault(CommandContext<CommandSourceStack> ctx) {
        return saveBoard(ctx, "default", null, null);
    }

    private int boardSet(CommandContext<CommandSourceStack> ctx, String mode, String metric, String identity) {
        if (metric != null && Metric.byId(metric) == null) {
            ctx.getSource().sendFailure(CommandTexts.error("未知指标：" + metric));
            return 0;
        }
        return saveBoard(ctx, mode, metric, identity);
    }

    private int saveBoard(CommandContext<CommandSourceStack> ctx, String mode, String metric, String identity) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(CommandTexts.error("该命令只能由玩家执行。"));
            return 0;
        }
        if (!services().rules().allowPlayerChoice()) {
            source.sendFailure(CommandTexts.error("服务器已禁止个人覆盖侧边栏偏好（themisStatsAllowPlayerChoice=false）。"));
            return 0;
        }
        if ("default".equals(mode)) {
            services().sidebar().savePreference(player.getUUID(), "default", null, null);
            source.sendSuccess(() -> CommandTexts.ok("已恢复服务器默认侧边栏设置。"), false);
            return 1;
        }
        String group = identity;
        if (group == null) {
            group = services().sidebar().resolveDisplay(player)[2];
        }
        services().sidebar().savePreference(player.getUUID(), mode, metric, group);
        source.sendSuccess(() -> CommandTexts.ok("侧边栏设置已保存。"), false);
        return 1;
    }

    private int bots(CommandContext<CommandSourceStack> ctx, int page) {
        CommandSourceStack source = ctx.getSource();
        // Refresh the observation right now, so that the answer is not based on a stale snapshot.
        if (services() != null && source.getServer() != null) {
            services().botMonitor().sampleIfDue(source.getServer());
        }
        asyncQuery(source, () -> services().permissions().canQueryPublic(source), () -> {
            List<Component> lines = new ArrayList<>();
            lines.add(CommandTexts.title("假人列表"));
            var snapshots = services().botMonitor().snapshots();
            snapshots.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
            int from = Math.min(Math.max(0, page - 1) * PAGE_SIZE, snapshots.size());
            int to = Math.min(from + PAGE_SIZE, snapshots.size());
            if (snapshots.isEmpty()) {
                lines.add(CommandTexts.line("当前没有已观察到的假人。"));
                return lines;
            }
            for (var snapshot : snapshots.subList(from, to)) {
                lines.add(Component.literal("§8- §f" + snapshot.name() + " §7["
                        + snapshot.identity().displayName() + "] §8"
                        + (snapshot.online() ? "§a在线" : "§8离线（最后已知）")));
            }
            lines.add(CommandTexts.hint("共 " + snapshots.size() + " 个对象，第 " + page + " 页。"));
            return lines;
        });
        return 1;
    }

    private int bot(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String input = StringArgumentType.getString(ctx, "target");
        if (services() != null && source.getServer() != null) {
            services().botMonitor().sampleIfDue(source.getServer());
        }
        asyncTargetQuery(source, input,
                target -> () -> services().permissions().canViewBotDetails(source, target.uuid(), groupOf(target))
                        || services().permissions().canQueryPublic(source),
                target -> {
            // The identity of the object is taken from the live observation, and only falls back
            // to the stored profile. A bot is never reported as a Shadow by accident.
            Identity group = groupOf(target);
            var profile = services().accounts().botProfile(target.uuid(), group);
            boolean maySeeDetails = services().permissions().canViewBotDetails(source, target.uuid(), group);
            List<Component> lines = new ArrayList<>();
            lines.add(CommandTexts.title("假人档案 · " + target.name()));
            lines.add(CommandTexts.value("身份", group.displayName()));
            if (profile != null) {
                lines.add(CommandTexts.value("负责人", profile.ownerName() == null ? "未设置" : profile.ownerName()));
                lines.add(CommandTexts.value("用途", profile.purpose() == null ? "未设置" : profile.purpose()));
                if (profile.note() != null && !profile.note().isBlank()) {
                    lines.add(CommandTexts.value("备注", profile.note()));
                }
            }
            var tags = services().accounts().tagsOf(target.uuid(), group);
            if (!tags.isEmpty()) {
                lines.add(CommandTexts.value("标签", String.join("、", tags)));
            }
            var snapshot = services().botMonitor().snapshot(target.uuid(), group);
            if (snapshot == null) {
                lines.add(CommandTexts.line("状态：未观察到（可能尚未采样或已超出观察范围）"));
            } else if (maySeeDetails) {
                lines.add(CommandTexts.value("状态", snapshot.online() ? "在线" : "离线（最后已知）"));
                lines.add(CommandTexts.value("维度／坐标", snapshot.dimension() + " "
                        + String.format("%.1f / %.1f / %.1f", snapshot.x(), snapshot.y(), snapshot.z())));
                lines.add(CommandTexts.value("生命／饥饿", String.format("%.1f / %d", snapshot.health(),
                        snapshot.food())));
                lines.add(CommandTexts.value("主手／副手", snapshot.mainHand() + " / " + snapshot.offHand()));
                if (snapshot.mainHandDurabilityPercent() != null) {
                    lines.add(CommandTexts.value("主手耐久", snapshot.mainHandDurabilityPercent() + "%"));
                }
                for (String action : snapshot.actionPlan()) {
                    lines.add(CommandTexts.line(action));
                }
                lines.add(CommandTexts.hint("观测时间：" + new java.util.Date(snapshot.observedAtMillis())));
            } else {
                lines.add(CommandTexts.line("精确位置与装备仅负责人和管理员可见。"));
                lines.add(CommandTexts.hint("在线状态：" + (snapshot.online() ? "在线" : "离线（最后已知）")));
            }
            return lines;
                });
        return 1;
    }

    /**
     * @return the identity of the given object, taken from the live observation
     */
    private Identity groupOf(Target target) {
        var liveSnapshot = services().botMonitor().snapshot(target.uuid(), null);
        if (liveSnapshot != null) {
            return liveSnapshot.identity();
        }
        if (services().accounts().botProfile(target.uuid(), Identity.BOT) != null) {
            return Identity.BOT;
        }
        if (services().accounts().botProfile(target.uuid(), Identity.SHADOW) != null) {
            return Identity.SHADOW;
        }
        return Identity.BOT;
    }

    private int sessions(CommandContext<CommandSourceStack> ctx, int page) {
        CommandSourceStack source = ctx.getSource();
        String input = StringArgumentType.getString(ctx, "target");
        Identity group = resolveIdentity(StringArgumentType.getString(ctx, "identity"), Identity.BOT);
        if (services() != null && source.getServer() != null) {
            services().botMonitor().sampleIfDue(source.getServer());
        }
        asyncTargetQuery(source, input,
                target -> () -> (source.getPlayer() != null
                        && source.getPlayer().getUUID().equals(target.uuid()))
                        || services().permissions().isAdmin(source)
                        || services().permissions().canViewBotDetails(source, target.uuid(), group),
                target -> {
            List<Component> lines = new ArrayList<>();
            lines.add(CommandTexts.title("会话列表 · " + target.name() + " · " + group.displayName()));
            var sessions = services().accounts().sessionsOf(target.uuid(), group, page, PAGE_SIZE);
            if (sessions.isEmpty()) {
                lines.add(CommandTexts.line("没有记录到会话。"));
                return lines;
            }
            for (var session : sessions) {
                lines.add(Component.literal("§8- §f" + session.id().substring(0, 8) + " §7"
                        + new java.util.Date(session.startWall()) + " §8- §7在线 "
                        + Numbers.duration(session.actualOnlineMs()) + " §8- §7结束情况 §f"
                        + (session.endReason() == null ? "进行中" : session.endReason().displayName())));
            }
            lines.add(CommandTexts.hint("共 " + services().accounts().sessionCount(target.uuid(), group)
                    + " 次会话，第 " + page + " 页。"));
            return lines;
                });
        return 1;
    }

    private int session(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String id = StringArgumentType.getString(ctx, "id");
        asyncQuery(source, () -> true, () -> {
            List<Component> lines = new ArrayList<>();
            var session = services().accounts().session(id);
            if (session == null) {
                lines.add(CommandTexts.error("没有找到该会话。"));
                return lines;
            }
            lines.add(CommandTexts.title("会话详情 · " + session.id().substring(0, 8)));
            lines.add(CommandTexts.value("开始", new java.util.Date(session.startWall()).toString()));
            lines.add(CommandTexts.value("结束", session.endWall() == null ? "进行中"
                    : new java.util.Date(session.endWall()).toString()));
            lines.add(CommandTexts.value("结束情况", session.endReason() == null ? "进行中"
                    : session.endReason().displayName()));
            lines.add(CommandTexts.value("实际在线", Numbers.duration(session.actualOnlineMs())));
            lines.add(CommandTexts.value("有效采集", Numbers.duration(session.collectedMs())));
            if (session.ownerName() != null) {
                lines.add(CommandTexts.value("当时负责人", session.ownerName()));
            }
            if (session.purpose() != null) {
                lines.add(CommandTexts.value("当时用途", session.purpose()));
            }
            if (!session.complete()) {
                lines.add(CommandTexts.error("数据完整性：存在缺口 " + (session.gapNote() == null ? "" : session.gapNote())));
            }
            for (String raw : services().accounts().sessionMetrics(session.id())) {
                String[] parts = raw.split("\\|", 3);
                if (parts.length == 3) {
                    Metric metric = Metric.byId(parts[0]);
                    lines.add(CommandTexts.value(metric == null ? parts[0] : metric.displayName(),
                            parts[1].isEmpty() ? Numbers.full(Long.parseLong(parts[2]))
                                    : parts[1] + " × " + Numbers.full(Long.parseLong(parts[2]))));
                }
            }
            return lines;
        });
        return 1;
    }

    /**
     * The fine grained leaderboard: one block or entity type, optionally one dimension.
     */
    /**
     * The total of a project: the objects that belong to it and the sum of their values.
     */
    private int project(CommandContext<CommandSourceStack> ctx, String metricId, int page) {
        CommandSourceStack source = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "project");
        Metric metric = Metric.MINING;
        if (metricId != null) {
            Metric parsed = Metric.byId(metricId);
            if (parsed == null) {
                source.sendFailure(CommandTexts.error("未知指标：" + metricId));
                return 0;
            }
            metric = parsed;
        }
        final Metric scope = metric;
        if (!services().permissions().canQueryPublic(source)) {
            source.sendFailure(CommandTexts.error("公开查询当前已关闭。"));
            return 0;
        }
        asyncQuery(source, () -> services().permissions().canQueryPublic(source), () -> {
            List<Component> lines = new ArrayList<>();
            var members = services().accounts().projectMembers(name);
            lines.add(CommandTexts.title("工程 · " + name + " · " + scope.displayName()));
            if (members.isEmpty()) {
                lines.add(CommandTexts.line("这个工程还没有成员。用 "
                        + "/themisstats admin project <对象> " + name + " 归入。"));
                return lines;
            }
            long total = 0L;
            List<String> rows = new ArrayList<>();
            for (var member : members) {
                long value = services().stats().valueOf(member.uuid(),
                        StatsRepository.Scope.of(scope, member.identity(),
                                services().rules().publicModes()));
                total += value;
                rows.add(member.uuid() + "|" + member.identity().id() + "|" + value);
            }
            for (String row : rows) {
                String[] parts = row.split("\\|");
                if (parts.length < 3) {
                    continue;
                }
                var account = services().accounts().accountByUuid(java.util.UUID.fromString(parts[0]));
                lines.add(CommandTexts.value(account == null ? parts[0] : account.currentName(),
                        CommandTexts.format(scope, Long.parseLong(parts[2]))));
            }
            lines.add(CommandTexts.value("工程合计", CommandTexts.format(scope, total)));
            lines.add(CommandTexts.hint("共 " + members.size() + " 个成员；工程累计只统计已采集的数据，"
                    + "不代表机器的实际产量。"));
            return lines;
        });
        return 1;
    }

    private int fine(CommandContext<CommandSourceStack> ctx, Identity group, int page) {
        CommandSourceStack source = ctx.getSource();
        String metricId = StringArgumentType.getString(ctx, "metric");
        Metric metric = Metric.byId(metricId);
        if (metric == null) {
            source.sendFailure(CommandTexts.error("未知指标：" + metricId));
            return 0;
        }
        if (!metric.supportsFineBoards()) {
            source.sendFailure(CommandTexts.error("指标 " + metric.displayName() + " 没有分类细目，无法生成细分榜。"));
            return 0;
        }
        String category = StringArgumentType.getString(ctx, "category");
        int pageNumber = page;
        if (!services().permissions().canQueryPublic(source)) {
            source.sendFailure(CommandTexts.error("公开查询当前已关闭。"));
            return 0;
        }
        if (!services().permissions().isBoardOpen(group)) {
            source.sendFailure(CommandTexts.error(group.displayName() + "公共榜当前已关闭。"));
            return 0;
        }
        List<String> modes = services().rules().publicModes();
        asyncQuery(source, () -> services().permissions().canQueryPublic(source), () -> {
            List<Component> lines = new ArrayList<>();
            var scope = StatsRepository.Scope.of(metric, group, modes, "", category);
            var entries = services().stats().board(scope, pageNumber, PAGE_SIZE);
            lines.add(CommandTexts.title("细分榜 · " + metric.displayName() + " · " + category
                    + " · " + group.displayName()));
            lines.add(CommandTexts.hint("维度：全部；模式：" + String.join("、", modes)
                    + "（完整筛选请用 /themisstats admin board）"));
            if (entries.isEmpty()) {
                lines.add(CommandTexts.line("该范围内暂无数据。"));
                return lines;
            }
            for (var entry : entries) {
                lines.add(Component.literal("§8#" + entry.rank() + " §f" + entry.name() + " §8- §a"
                        + CommandTexts.format(metric, entry.value())));
            }
            lines.add(CommandTexts.hint("第 " + pageNumber + " 页，共 "
                    + services().stats().boardSize(scope) + " 个对象。"));
            return lines;
        });
        return 1;
    }

    private int explain(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String metricId = StringArgumentType.getString(ctx, "metric");
        Metric metric = Metric.byId(metricId);
        if (metric == null) {
            source.sendFailure(CommandTexts.error("未知指标：" + metricId + "，可用指标：" + Metric.idList()));
            return 0;
        }
        source.sendSuccess(() -> CommandTexts.title("统计口径 · " + metric.displayName()), false);
        source.sendSuccess(() -> CommandTexts.value("指标 ID", metric.id()), false);
        source.sendSuccess(() -> CommandTexts.value("单位", metric.unit().display()), false);
        source.sendSuccess(() -> CommandTexts.value("计算口径", explainText(metric)), false);
        source.sendSuccess(() -> CommandTexts.value("细分维度", metric.hasCategory()
                ? "方块／实体注册 ID、维度、模式" : "维度、模式"), false);
        source.sendSuccess(() -> CommandTexts.value("公开模式范围", metric.isModeClassified()
                ? "生存＋冒险（创造与旁观仅详细查询可见）" : "按实际采集范围，不按模式过滤"), false);
        source.sendSuccess(() -> CommandTexts.hint("数据从 Mod 启用采集后开始记录，不导入原版历史统计。"), false);
        return 1;
    }

    private String explainText(Metric metric) {
        return switch (metric) {
            case ONLINE -> "会话实际存在时长中采集启用的部分；停机时间与暂停采集段不计入";
            case MINING -> "由玩家行为成功破坏的方块数量，徒手也可统计；取消、拒绝、未破坏不计，命令与爆炸不归给玩家";
            case PLACEMENT -> "一次成功的玩家固体方块放置操作计 1；门与床一次算 1，活塞、生长与液体不混入";
            case KILLS -> "服务端最终归属的击杀事件，每个目标死亡只记一次；玩家击杀单独分类";
            case DEATHS -> "玩家真正发生的死亡；普通移除或离线不计";
            case VILLAGER_TRADES -> "一次成功执行的交易计 1，一次获得多个物品仍是 1 次";
            case FISHING -> "一次成功钓获计 1；空甩竿与空收竿不计";
            case ELYTRA_DISTANCE -> "鞘翅滑翔时的连续有效位移；传送、跨维度与单 tick 超过 20 方块的跳变不计";
            case DAMAGE_DEALT -> "实际从目标普通生命上扣除的伤害；护甲、抵抗与吸收生命先被扣除，过量伤害按剩余生命计，不累计请求伤害";
            case DAMAGE_TAKEN -> "实际从自身普通生命上扣除的伤害；吸收生命与图腾触发不计";
            case MOVEMENT -> "自身移动产生的有效位移，按步行、游泳、乘骑、飞行分类；被活塞或潜影盒推动不算";
            case CRAFTED -> "成功领取的制作产物数量；这是领取量，不代表产量";
            case PICKED_UP -> "实际进入背包的物品数量，不以掉落实体的堆数计算；背包满时只计真正拿走的部分";
            case DROPPED -> "玩家主动丢弃的物品数量；死亡掉落不计入";
            case PIGLIN_BARTERS -> "与猪灵完成的一次交换；无法明确归属到玩家时不计";
        };
    }
}

