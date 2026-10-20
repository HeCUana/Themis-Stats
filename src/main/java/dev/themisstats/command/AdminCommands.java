package dev.themisstats.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.themisstats.ThemisStatsServices;
import dev.themisstats.identity.Identity;
import dev.themisstats.metric.Metric;
import dev.themisstats.storage.AccountRepository;
import dev.themisstats.storage.Exporter;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The administrative part of {@code /themisstats}.
 *
 * <p>All of it needs at least the operator permission level 2 (or the console). Every change that
 * touches already collected data is shown as a preview first and only applied after an explicit
 * confirmation with a token that is bound to the operator, to the parameters and to a 60 second
 * expiry.</p>
 */
public final class AdminCommands {

    private static final int PAGE_SIZE = 10;
    private static final int MAX_NOTE_LENGTH = 120;
    private static final int MAX_TAG_LENGTH = 24;
    private static final int MAX_TAGS = 8;

    private volatile ThemisStatsServices services;
    private final ConfirmTokens tokens;

    public AdminCommands(ConfirmTokens tokens) {
        this.tokens = tokens;
    }

    public void setServices(ThemisStatsServices services) {
        this.services = services;
    }

    private ThemisStatsServices services() {
        return services;
    }

    public void register(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("admin")
                .requires(source -> net.minecraft.commands.Commands.LEVEL_GAMEMASTERS.check(source.permissions()))
                .then(Commands.literal("status").executes(this::status))
                .then(Commands.literal("flush").executes(this::flush))
                .then(Commands.literal("backup").executes(this::backup))
                .then(Commands.literal("board")
                        .then(boardMetric.then(boardIdentity.then(boardModes
                                .executes(ctx -> adminBoard(ctx, "", "", 1))
                                .then(boardCategory
                                        .executes(ctx -> adminBoard(ctx,
                                                StringArgumentType.getString(ctx, "category"), "", 1))
                                        .then(boardDimension
                                                .executes(ctx -> adminBoard(ctx,
                                                        StringArgumentType.getString(ctx, "category"),
                                                        StringArgumentType.getString(ctx, "dimension"), 1))
                                                .then(boardPage
                                                        .executes(ctx -> adminBoard(ctx,
                                                                StringArgumentType.getString(ctx, "category"),
                                                                StringArgumentType.getString(ctx, "dimension"),
                                                                IntegerArgumentType.getInteger(ctx,
                                                                        "page"))))))))))
                .then(Commands.literal("import")
                        .then(Commands.argument("file", StringArgumentType.greedyString())
                                .executes(ctx -> importData(ctx))))
                .then(Commands.literal("imported")
                        .then(Commands.argument("metric", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestions.suggestMetrics(builder))
                                .then(Commands.argument("identity", StringArgumentType.word())
                                        .suggests((ctx, builder) -> SharedSuggestions.suggestIdentities(builder))
                                        .executes(ctx -> importedBoard(ctx)))))
                .then(Commands.literal("audit")
                        .executes(ctx -> audit(ctx, 1))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(ctx -> audit(ctx, IntegerArgumentType.getInteger(ctx, "page")))))
                .then(Commands.literal("export")
                        .then(Commands.argument("metric", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestions.suggestMetrics(builder))
                                .then(Commands.argument("identity", StringArgumentType.word())
                                        .suggests((ctx, builder) -> SharedSuggestions.suggestIdentities(builder))
                                        .then(Commands.argument("format", StringArgumentType.word())
                                                .suggests((ctx, builder) -> SharedSuggestions.suggestFormats(builder))
                                                .executes(this::export)))))
                .then(Commands.literal("bot")
                        .then(Commands.literal("owner")
                                .then(Commands.argument("target", StringArgumentType.string())
                                        .then(Commands.argument("owner", StringArgumentType.greedyString())
                                                .executes(this::setOwner))))
                        .then(Commands.literal("role")
                                .then(Commands.argument("target", StringArgumentType.string())
                                        .then(Commands.argument("purpose", StringArgumentType.word())
                                                .suggests((ctx, builder) -> SharedSuggestions.suggestPurposes(builder))
                                                .executes(this::setRole))))
                        .then(Commands.literal("note")
                                .then(Commands.argument("target", StringArgumentType.string())
                                        .then(Commands.argument("note", StringArgumentType.greedyString())
                                                .executes(this::setNote))))
                        .then(Commands.literal("tag")
                                .then(Commands.argument("target", StringArgumentType.string())
                                        .then(Commands.argument("operation", StringArgumentType.word())
                                                .suggests((ctx, builder) -> SharedSuggestions.suggestTagOperations(builder))
                                                .then(Commands.argument("tag", StringArgumentType.word())
                                                        .executes(this::setTag))))))
                .then(Commands.literal("project")
                        .then(Commands.argument("target", StringArgumentType.string())
                                .then(Commands.argument("project", StringArgumentType.greedyString())
                                        .executes(this::setProject))))
                .then(Commands.literal("exclude")
                        .then(Commands.argument("target", StringArgumentType.string())
                                .then(Commands.argument("identity", StringArgumentType.word())
                                        .suggests((ctx, builder) -> SharedSuggestions.suggestIdentities(builder))
                                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                                .executes(this::exclude)))))
                .then(Commands.literal("include")
                        .then(Commands.argument("target", StringArgumentType.string())
                                .then(Commands.argument("identity", StringArgumentType.word())
                                        .suggests((ctx, builder) -> SharedSuggestions.suggestIdentities(builder))
                                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                                .executes(this::include)))))
                .then(Commands.literal("adjust")
                        .then(Commands.argument("target", StringArgumentType.string())
                                .then(Commands.argument("identity", StringArgumentType.word())
                                        .suggests((ctx, builder) -> SharedSuggestions.suggestIdentities(builder))
                                        .then(Commands.argument("metric", StringArgumentType.word())
                                                .suggests((ctx, builder) -> SharedSuggestions.suggestMetrics(builder))
                                                .then(Commands.argument("operation", StringArgumentType.word())
                                                        .suggests((ctx, builder) -> SharedSuggestions.suggestOperations(builder))
                                                        .then(Commands.argument("value", StringArgumentType.word())
                                                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                                                        .executes(ctx -> adjust(ctx, null, null, null)))))))))
                .then(Commands.literal("confirm")
                        .then(Commands.argument("token", StringArgumentType.word())
                                .executes(this::confirm))));
    }

    // ------------------------------------------------------------------ helpers

    private String operator(CommandSourceStack source) {
        return source.getTextName();
    }

    private UUID operatorUuid(CommandSourceStack source) {
        return source.getPlayer() == null ? null : source.getPlayer().getUUID();
    }

    /** A resolved account, or {@code null} when the name is unknown or ambiguous. */
    private record Resolved(UUID uuid, String name, boolean ambiguous) {
    }

    private Resolved resolve(String input, CommandSourceStack source) {
        String trimmed = input == null ? "" : input.trim();
        try {
            UUID uuid = UUID.fromString(trimmed);
            AccountRepository.AccountInfo account = services().accounts().accountByUuid(uuid);
            return new Resolved(uuid, account == null ? trimmed : account.currentName(), false);
        } catch (IllegalArgumentException ignored) {
            // not a UUID
        }
        List<AccountRepository.AccountInfo> matches = services().accounts().accountsByName(trimmed);
        if (matches.isEmpty()) {
            return new Resolved(null, trimmed, false);
        }
        if (matches.size() > 1) {
            return new Resolved(null, trimmed, true);
        }
        return new Resolved(matches.get(0).uuid(), matches.get(0).currentName(), false);
    }

    private boolean requireTarget(CommandSourceStack source, String input) {
        Resolved resolved = resolve(input, source);
        if (resolved.ambiguous()) {
            source.sendFailure(CommandTexts.error("名称「" + input + "」对应多个账号，请使用 UUID 指定。"));
            return false;
        }
        if (resolved.uuid() == null) {
            source.sendFailure(CommandTexts.error("没有找到名为「" + input + "」的本地账号。"));
            return false;
        }
        return true;
    }


    /**
     * Runs an administrative database operation in the background and reports the result on the
     * game thread.
     *
     * <p>Every administrative command touches the database, and the game thread must never wait
     * for the disk.</p>
     */
    private void runAdmin(CommandSourceStack source, java.util.function.Supplier<List<Component>> work) {
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
                result = List.of(CommandTexts.error("操作失败：" + e.getMessage()));
            }
            List<Component> finalResult = result;
            server.execute(() -> {
                for (Component component : finalResult) {
                    source.sendSuccess(() -> component, false);
                }
            });
        });
    }

    // ------------------------------------------------------------------ commands

    private int status(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        var writer = services().writer();
        var rules = services().rules();
        source.sendSuccess(() -> CommandTexts.title("Themis Stats 运行状态"), false);
        source.sendSuccess(() -> CommandTexts.value("模块版本", dev.themisstats.ThemisStats.VERSION), false);
        source.sendSuccess(() -> CommandTexts.value("数据库版本", String.valueOf(services().database().schemaVersion())), false);
        source.sendSuccess(() -> CommandTexts.value("统计口径版本", String.valueOf(dev.themisstats.storage.Schema.VERSION)), false);
        source.sendSuccess(() -> CommandTexts.value("采集开关", rules.recording() ? "开启" : "暂停"), false);
        source.sendSuccess(() -> CommandTexts.value("观察开关", rules.botMonitor() ? "开启" : "关闭"), false);
        source.sendSuccess(() -> CommandTexts.value("公共查询", rules.publicQuery() ? "开启" : "关闭"), false);
        source.sendSuccess(() -> CommandTexts.value("侧边栏", rules.sidebar() ? "开启" : "关闭"), false);
        source.sendSuccess(() -> CommandTexts.value("最后成功提交", writer.lastSuccessMillis() == 0 ? "尚未提交"
                : new java.util.Date(writer.lastSuccessMillis()).toString()), false);
        source.sendSuccess(() -> CommandTexts.value("写入状态", writer.describe()), false);
        source.sendSuccess(() -> CommandTexts.value("待保存批次", String.valueOf(writer.backlog())), false);
        source.sendSuccess(() -> CommandTexts.value("已提交批次／增量", writer.committedBatches() + " / "
                + writer.committedIncrements()), false);
        source.sendSuccess(() -> CommandTexts.value("内存待写入", String.valueOf(services().pipeline().buffered())), false);
        source.sendSuccess(() -> CommandTexts.value("丢弃增量", String.valueOf(services().pipeline().droppedIncrements())), false);
        source.sendSuccess(() -> CommandTexts.value("当前在线", onlineSummary()), false);
        source.sendSuccess(() -> CommandTexts.hint("启动时间：" + new java.util.Date(services().startedAtMillis())), false);
        return 1;
    }

    private String onlineSummary() {
        var counts = services().sessions().onlineCounts();
        return "真人 " + counts.getOrDefault(Identity.HUMAN, 0)
                + "，假人 " + counts.getOrDefault(Identity.BOT, 0)
                + "，Shadow " + counts.getOrDefault(Identity.SHADOW, 0)
                + "，未知 " + counts.getOrDefault(Identity.UNKNOWN, 0);
    }

    private int flush(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        int flushed = services().pipeline().flush();
        boolean done = services().writer().flush(10_000L);
        source.sendSuccess(() -> CommandTexts.ok("已提交 " + flushed + " 条待保存增量。"), false);
        if (!done) {
            source.sendSuccess(() -> CommandTexts.error("写入队列未能及时清空，请查看 /themisstats admin status。"), false);
        }
        return 1;
    }

    private int backup(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        services().pipeline().flush();
        boolean flushed = services().writer().flush(10_000L);
        var result = services().backup().create("管理员请求", flushed);
        if (result.success()) {
            source.sendSuccess(() -> CommandTexts.ok(result.message()), false);
            source.sendSuccess(() -> CommandTexts.hint("备份只包含 Themis Stats 统计数据，不包含世界与玩家数据。"), false);
            services().accounts().audit("backup", result.fileName(), "统计数据库备份", operator(source),
                    System.currentTimeMillis());
        } else {
            source.sendFailure(CommandTexts.error(result.message()));
        }
        return result.success() ? 1 : 0;
    }

    private int audit(CommandContext<CommandSourceStack> ctx, int page) {
        CommandSourceStack source = ctx.getSource();
        runAdmin(source, () -> {
            var entries = services().accounts().auditEntries(page, PAGE_SIZE);
            List<Component> lines = new ArrayList<>();
            lines.add(CommandTexts.title("管理操作日志 · 第 " + page + " 页"));
            if (entries.isEmpty()) {
                lines.add(CommandTexts.line("没有记录。"));
                return lines;
            }
            for (String entry : entries) {
                lines.add(CommandTexts.line(entry));
            }
            lines.add(CommandTexts.hint("共 " + services().accounts().auditCount() + " 条记录。"));
            return lines;
        });
        return 1;
    }

    private int export(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        Metric metric = Metric.byId(StringArgumentType.getString(ctx, "metric"));
        if (metric == null) {
            source.sendFailure(CommandTexts.error("未知指标。"));
            return 0;
        }
        Identity identity = Identity.byId(StringArgumentType.getString(ctx, "identity"));
        if (identity == Identity.UNKNOWN) {
            source.sendFailure(CommandTexts.error("未知身份组。"));
            return 0;
        }
        String format = StringArgumentType.getString(ctx, "format").toLowerCase(java.util.Locale.ROOT);
        if (!format.equals("csv") && !format.equals("json")) {
            source.sendFailure(CommandTexts.error("导出格式只支持 csv 或 json。"));
            return 0;
        }
        source.sendSuccess(() -> CommandTexts.ok("导出任务已提交，正在后台处理……"), false);
        var scope = new Exporter.ExportScope(metric.id() + " · " + identity.id(), metric, identity,
                metric.isModeClassified() ? List.of("survival", "adventure") : List.of());
        services().runInBackground(() -> {
            var result = services().exporter().export(format, scope, operator(source));
            source.getServer().execute(() -> {
                if (result.success()) {
                    source.sendSuccess(() -> CommandTexts.ok(result.message()), false);
                    services().accounts().audit("export", result.fileName(), scope.description(),
                            operator(source), System.currentTimeMillis());
                } else {
                    source.sendFailure(CommandTexts.error(result.message()));
                }
            });
        });
        return 1;
    }

    private int setOwner(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String targetInput = StringArgumentType.getString(ctx, "target");
        if (!requireTarget(source, targetInput)) {
            return 0;
        }
        Resolved target = resolve(targetInput, source);
        String ownerInput = StringArgumentType.getString(ctx, "owner");
        runAdmin(source, () -> {
            Resolved owner = resolve(ownerInput, source);
            if (owner.uuid() == null) {
                return List.of(CommandTexts.error("负责人必须是本服已记录的玩家，请使用玩家名或 UUID。"));
            }
            for (Identity identity : new Identity[]{Identity.BOT, Identity.SHADOW}) {
                var existing = services().accounts().botProfile(target.uuid(), identity);
                services().accounts().saveBotProfile(target.uuid(), identity, owner.uuid().toString(),
                        owner.name(), existing == null ? null : existing.purpose(),
                        existing == null ? null : existing.note(), System.currentTimeMillis());
                services().accounts().recordOwnerChange(target.uuid(), identity, owner.uuid().toString(),
                        owner.name(), operator(source), System.currentTimeMillis());
            }
            services().accounts().audit("bot.owner", target.name(), "负责人 -> " + owner.name(),
                    operator(source), System.currentTimeMillis());
            return List.of(CommandTexts.ok("负责人已设置为 " + owner.name()
                    + "。原负责人不再拥有该对象的详细访问权限，历史会话的负责人快照保持不变。"));
        });
        return 1;
    }

    private int setRole(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String targetInput = StringArgumentType.getString(ctx, "target");
        if (!requireTarget(source, targetInput)) {
            return 0;
        }
        Resolved target = resolve(targetInput, source);
        String purpose = StringArgumentType.getString(ctx, "purpose").toLowerCase(java.util.Locale.ROOT);
        if (!List.of("loader", "mining", "combat", "use", "custom").contains(purpose)) {
            source.sendFailure(CommandTexts.error("用途只支持 loader、mining、combat、use、custom。"));
            return 0;
        }
        runAdmin(source, () -> {
            for (Identity identity : new Identity[]{Identity.BOT, Identity.SHADOW}) {
                var existing = services().accounts().botProfile(target.uuid(), identity);
                services().accounts().saveBotProfile(target.uuid(), identity,
                        existing == null ? null : existing.ownerUuid(),
                        existing == null ? null : existing.ownerName(),
                        purpose, existing == null ? null : existing.note(), System.currentTimeMillis());
            }
            services().accounts().audit("bot.role", target.name(), "用途 -> " + purpose, operator(source),
                    System.currentTimeMillis());
            return List.of(CommandTexts.ok("用途已设置为 " + purpose
                    + "。该标记不会修改 Carpet 的实际动作。"));
        });
        return 1;
    }

    private int setNote(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String targetInput = StringArgumentType.getString(ctx, "target");
        if (!requireTarget(source, targetInput)) {
            return 0;
        }
        Resolved target = resolve(targetInput, source);
        String note = StringArgumentType.getString(ctx, "note");
        if (note.length() > MAX_NOTE_LENGTH) {
            source.sendFailure(CommandTexts.error("备注最长 " + MAX_NOTE_LENGTH + " 个字符。"));
            return 0;
        }
        String cleaned = note.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "").trim();
        runAdmin(source, () -> {
            for (Identity identity : new Identity[]{Identity.BOT, Identity.SHADOW}) {
                var existing = services().accounts().botProfile(target.uuid(), identity);
                services().accounts().saveBotProfile(target.uuid(), identity,
                        existing == null ? null : existing.ownerUuid(),
                        existing == null ? null : existing.ownerName(),
                        existing == null ? null : existing.purpose(), cleaned, System.currentTimeMillis());
            }
            services().accounts().audit("bot.note", target.name(), cleaned, operator(source),
                    System.currentTimeMillis());
            return List.of(CommandTexts.ok("备注已更新。"));
        });
        return 1;
    }

    private int setTag(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String targetInput = StringArgumentType.getString(ctx, "target");
        if (!requireTarget(source, targetInput)) {
            return 0;
        }
        Resolved target = resolve(targetInput, source);
        String operation = SharedSuggestions.lower(StringArgumentType.getString(ctx, "operation"));
        String tag = StringArgumentType.getString(ctx, "tag").trim();
        if (tag.isEmpty() || tag.length() > MAX_TAG_LENGTH) {
            source.sendFailure(CommandTexts.error("标签长度必须在 1 到 " + MAX_TAG_LENGTH + " 之间。"));
            return 0;
        }
        if (!operation.equals("add") && !operation.equals("remove")) {
            source.sendFailure(CommandTexts.error("标签操作只支持 add 或 remove。"));
            return 0;
        }
        runAdmin(source, () -> {
            for (Identity identity : new Identity[]{Identity.BOT, Identity.SHADOW}) {
                if (operation.equals("add")) {
                    if (services().accounts().tagsOf(target.uuid(), identity).size() >= MAX_TAGS) {
                        return List.of(CommandTexts.error("每个对象最多 " + MAX_TAGS + " 个标签。"));
                    }
                    services().accounts().addTag(target.uuid(), identity, tag);
                } else {
                    services().accounts().removeTag(target.uuid(), identity, tag);
                }
            }
            services().accounts().audit("bot.tag", target.name(), operation + " " + tag, operator(source),
                    System.currentTimeMillis());
            return List.of(CommandTexts.ok("标签已更新。"));
        });
        return 1;
    }

    private int exclude(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String targetInput = StringArgumentType.getString(ctx, "target");
        if (!requireTarget(source, targetInput)) {
            return 0;
        }
        Resolved target = resolve(targetInput, source);
        Identity identity = Identity.byId(StringArgumentType.getString(ctx, "identity"));
        if (identity == Identity.UNKNOWN) {
            source.sendFailure(CommandTexts.error("未知身份组。"));
            return 0;
        }
        String reason = StringArgumentType.getString(ctx, "reason");
        if (reason.isBlank()) {
            source.sendFailure(CommandTexts.error("必须填写排除原因。"));
            return 0;
        }
        runAdmin(source, () -> {
            long affected = services().stats().valueOf(target.uuid(),
                    dev.themisstats.storage.StatsRepository.Scope.of(Metric.MINING, identity));
            services().accounts().setExclusion(target.uuid(), identity, true, reason, operator(source),
                    System.currentTimeMillis());
            services().accounts().audit("exclude", target.name(), identity.id() + " | " + reason,
                    operator(source), System.currentTimeMillis());
            return List.of(
                    CommandTexts.ok("已排除 " + target.name() + "（" + identity.displayName()
                            + "）。统计数据没有删除，只是不再进入公开榜与公开总计。"),
                    CommandTexts.hint("影响范围：该对象在该身份下的全部累计（当前挖掘累计 "
                            + affected + "）。撤销请使用 /themisstats admin include。"));
        });
        return 1;
    }

    private int include(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String targetInput = StringArgumentType.getString(ctx, "target");
        if (!requireTarget(source, targetInput)) {
            return 0;
        }
        Resolved target = resolve(targetInput, source);
        Identity identity = Identity.byId(StringArgumentType.getString(ctx, "identity"));
        if (identity == Identity.UNKNOWN) {
            source.sendFailure(CommandTexts.error("未知身份组。"));
            return 0;
        }
        String reason = StringArgumentType.getString(ctx, "reason");
        runAdmin(source, () -> {
            services().accounts().setExclusion(target.uuid(), identity, false, reason, operator(source),
                    System.currentTimeMillis());
            services().accounts().audit("include", target.name(), identity.id() + " | " + reason,
                    operator(source), System.currentTimeMillis());
            return List.of(CommandTexts.ok("已撤销排除，" + target.name() + " 按现有累计重新参与排名。"));
        });
        return 1;
    }

    private int adjust(CommandContext<CommandSourceStack> ctx, String category, String dimension, String gameMode) {
        CommandSourceStack source = ctx.getSource();
        String targetInput = StringArgumentType.getString(ctx, "target");
        if (!requireTarget(source, targetInput)) {
            return 0;
        }
        Resolved target = resolve(targetInput, source);
        Identity identity = Identity.byId(StringArgumentType.getString(ctx, "identity"));
        Metric metric = Metric.byId(StringArgumentType.getString(ctx, "metric"));
        if (identity == Identity.UNKNOWN || metric == null) {
            source.sendFailure(CommandTexts.error("未知身份组或指标。"));
            return 0;
        }
        String operation = StringArgumentType.getString(ctx, "operation");
        String valueText = StringArgumentType.getString(ctx, "value");
        long value;
        try {
            value = Long.parseLong(valueText);
        } catch (NumberFormatException e) {
            source.sendFailure(CommandTexts.error("数值必须是整数。"));
            return 0;
        }
        String reason = StringArgumentType.getString(ctx, "reason");
        if (reason.isBlank()) {
            source.sendFailure(CommandTexts.error("必须填写修正原因。"));
            return 0;
        }
        if (value == 0 && operation.equals("+")) {
            source.sendFailure(CommandTexts.error("修正值不能为 0。"));
            return 0;
        }

        // The affected cells: the adjustment always lands on a concrete detail scope, so that the
        // breakdown and the total can never disagree.
        runAdmin(source, () -> {
        List<Cell> cells = findCells(target.uuid(), identity, metric, category, dimension, gameMode);
        if (cells.isEmpty()) {
            return List.of(CommandTexts.error("该范围内没有任何已有累计，无法定位修正作用的具体明细。"));
        }
        List<Component> preview = new ArrayList<>();
        preview.add(CommandTexts.title("数据修正预览 · " + target.name()));
        preview.add(CommandTexts.value("范围", target.name() + " · " + identity.displayName() + " · "
                + metric.displayName()));
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("target", target.uuid().toString());
        parameters.put("identity", identity.id());
        parameters.put("metric", metric.id());
        parameters.put("operation", operation);
        parameters.put("value", Long.toString(value));
        parameters.put("reason", reason);
        parameters.put("cells", cells.stream().map(cell -> cell.category + "|" + cell.dimension + "|"
                + cell.gameMode + "|" + cell.value).collect(java.util.stream.Collectors.joining(";")));

        long totalDelta = 0;
        for (Cell cell : cells) {
            long newValue = switch (operation) {
                case "+" -> cell.value + value;
                case "-" -> cell.value - value;
                case "=" -> value;
                default -> cell.value;
            };
            totalDelta += newValue - cell.value;
            preview.add(CommandTexts.line("· " + describeCell(cell) + "：" + cell.value + " → " + newValue));
        }
        if (totalDelta < 0 && cells.stream().allMatch(cell -> cell.value < 0)) {
            // A negative result is refused; the check below covers the single cell case.
        }
        for (Cell cell : cells) {
            long newValue = switch (operation) {
                case "+" -> cell.value + value;
                case "-" -> cell.value - value;
                case "=" -> value;
                default -> cell.value;
            };
            if (newValue < 0) {
                return List.of(CommandTexts.error("修正后数值会变成负数，已拒绝。请调整修正值。"));
            }
        }
        preview.add(CommandTexts.hint("合计变化：" + (totalDelta >= 0 ? "+" : "") + totalDelta));
        var token = tokens.issue(operatorUuid(source), "数据修正 " + target.name() + " · " + metric.id(),
                parameters);
        preview.add(CommandTexts.ok("确认请执行：/themisstats admin confirm " + token.id()
                + "（令牌 " + (ConfirmTokens.LIFETIME_MILLIS / 1000) + " 秒内有效，且只能使用一次）"));
        return preview;
        });
        return 1;
    }

    private int confirm(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String id = StringArgumentType.getString(ctx, "token");
        ConfirmTokens.Token token = tokens.consume(id, operatorUuid(source));
        if (token == null) {
            source.sendFailure(CommandTexts.error("令牌无效、已过期、已被使用或属于其他操作者。"));
            return 0;
        }
        Map<String, String> parameters = token.parameters();
        UUID uuid = UUID.fromString(parameters.get("target"));
        Identity identity = Identity.byId(parameters.get("identity"));
        Metric metric = Metric.byId(parameters.get("metric"));
        String operation = parameters.get("operation");
        long value = Long.parseLong(parameters.get("value"));
        String reason = parameters.get("reason");
        String cells = parameters.get("cells");
        runAdmin(source, () -> {
            int applied = 0;
            for (String raw : cells.split(";")) {
                String[] parts = raw.split("\\|", 4);
                if (parts.length != 4) {
                    continue;
                }
                long cellValue = Long.parseLong(parts[3]);
                long delta = switch (operation) {
                    case "+" -> value;
                    case "-" -> -value;
                    case "=" -> value - cellValue;
                    default -> 0;
                };
                if (delta == 0) {
                    continue;
                }
                services().accounts().addAdjustment(uuid, identity, metric.id(), parts[0], parts[1], parts[2],
                        delta, reason, operator(source), System.currentTimeMillis());
                applied++;
            }
            services().accounts().audit("adjust", uuid.toString(),
                    metric.id() + " " + operation + value + "（" + applied + " 个明细） | " + reason,
                    operator(source), System.currentTimeMillis());
            int appliedCount = applied;
            return List.of(CommandTexts.ok("已应用数据修正，共 " + appliedCount
                    + " 个明细范围。原始会话统计保持不变，修正作为独立调整项展示。"));
        });
        return 1;
    }

    /**
     * The leaderboard with an explicit mode list, for administrators.
     *
     * <p>The public leaderboard only shows the modes that {@code themisStatsPublicModes} allows.
     * An administrator may look at any mode combination, which is what a diagnostic needs.</p>
     */
    private int adminBoard(CommandContext<CommandSourceStack> ctx, String category, String dimension,
                           int page) {
        CommandSourceStack source = ctx.getSource();
        Metric metric = Metric.byId(StringArgumentType.getString(ctx, "metric"));
        if (metric == null) {
            source.sendFailure(CommandTexts.error("未知指标。"));
            return 0;
        }
        Identity identity = Identity.byId(StringArgumentType.getString(ctx, "identity"));
        if (identity == Identity.UNKNOWN) {
            source.sendFailure(CommandTexts.error("未知身份组。"));
            return 0;
        }
        List<String> modes = new ArrayList<>();
        for (String part : StringArgumentType.getString(ctx, "modes").split(",")) {
            String trimmed = part.trim().toLowerCase(java.util.Locale.ROOT);
            if (!trimmed.isEmpty()) {
                modes.add(trimmed);
            }
        }
        int pageNumber = page;

        runAdmin(source, () -> {
            List<Component> lines = new ArrayList<>();
            var scope = dev.themisstats.storage.StatsRepository.Scope.of(metric, identity, modes, dimension,
                    category);
            var entries = services().stats().board(scope, pageNumber, 10);
            lines.add(CommandTexts.title("诊断榜 · " + metric.displayName() + " · " + identity.displayName()));
            lines.add(CommandTexts.hint("模式：" + (modes.isEmpty() ? "全部" : String.join("、", modes))
                    + "；维度：" + (dimension.isEmpty() ? "全部" : dimension)
                    + "；分类：" + (category.isEmpty() ? "全部" : category)));
            if (entries.isEmpty()) {
                lines.add(CommandTexts.line("该范围内暂无数据。"));
            } else {
                for (var entry : entries) {
                    lines.add(Component.literal("§8#" + entry.rank() + " §f" + entry.name() + " §8- §a"
                            + dev.themisstats.util.Numbers.full(entry.value())));
                }
                lines.add(CommandTexts.hint("共 " + services().stats().boardSize(scope) + " 个对象。"));
            }
            return lines;
        });
        return 1;
    }

    private static final com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String>
            boardMetric = Commands.argument("metric", StringArgumentType.word());
    private static final com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String>
            boardIdentity = Commands.argument("identity", StringArgumentType.word());
    private static final com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String>
            boardModes = Commands.argument("modes", StringArgumentType.word());
    private static final com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String>
            boardCategory = Commands.argument("category", TokenArgumentType.INSTANCE);
    private static final com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String>
            boardDimension = Commands.argument("dimension", TokenArgumentType.INSTANCE);
    private static final com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, Integer>
            boardPage = Commands.argument("page", IntegerArgumentType.integer(1));

    /**
     * Imports a CSV file with historical data.
     *
     * <p>The import is always explicit and the imported rows keep the origin
     * {@code imported}, so they never mix with the data this mod collected itself and never end up
     * on a public leaderboard on their own.</p>
     */
    private int importData(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String fileName = StringArgumentType.getString(ctx, "file");
        Path file = Path.of(fileName);
        if (!Files.isRegularFile(file)) {
            source.sendFailure(CommandTexts.error("找不到文件：" + fileName));
            return 0;
        }
        runAdmin(source, () -> {
            var result = new dev.themisstats.storage.ImportService(services().database().connection())
                    .importCsv(file, operator(source));
            services().watch().logImport(fileName, result.imported(), result.skipped(),
                    operator(source), System.currentTimeMillis());
            List<Component> lines = new ArrayList<>();
            lines.add(CommandTexts.ok("导入完成：写入 " + result.imported() + " 行，跳过 " + result.skipped()
                    + " 行。"));
            lines.add(CommandTexts.hint("导入的数据标记为「导入」来源，不会自动进入公共榜；"
                    + "它代表的是你写进文件里的内容，不是过去真人与假人行为的还原。"));
            int shown = 0;
            for (String problem : result.problems()) {
                if (shown++ >= 5) {
                    lines.add(CommandTexts.hint("……还有更多问题，见 /themisstats admin audit。"));
                    break;
                }
                lines.add(CommandTexts.error(problem));
            }
            return lines;
        });
        return 1;
    }

    /**
     * Shows what an explicit import really wrote.
     *
     * <p>The imported history lives in its own rows, so this view never mixes with the collected
     * data and never appears on a public leaderboard.</p>
     */
    private int importedBoard(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        Metric metric = Metric.byId(StringArgumentType.getString(ctx, "metric"));
        if (metric == null) {
            source.sendFailure(CommandTexts.error("未知指标。"));
            return 0;
        }
        Identity identity = Identity.byId(StringArgumentType.getString(ctx, "identity"));
        if (identity == Identity.UNKNOWN) {
            source.sendFailure(CommandTexts.error("未知身份组。"));
            return 0;
        }
        runAdmin(source, () -> {
            var scope = dev.themisstats.storage.StatsRepository.Scope.of(metric, identity, List.of(), "", "");
            var entries = services().stats().importedRanked(scope);
            List<Component> lines = new ArrayList<>();
            lines.add(CommandTexts.title("导入历史 · " + metric.displayName() + " · " + identity.displayName()));
            if (entries.isEmpty()) {
                lines.add(CommandTexts.line("还没有任何导入数据。"));
            } else {
                for (var entry : entries) {
                    lines.add(Component.literal("§8- §f" + entry.name() + " §8- §a"
                            + dev.themisstats.util.Numbers.full(entry.value())));
                }
                lines.add(CommandTexts.hint("共 " + entries.size() + " 个对象有导入历史。"
                        + "这些数值不会自动进入公共榜。"));
            }
            return lines;
        });
        return 1;
    }

    /**
     * Puts an object into a project, so that the output of a whole farm can be looked at.
     */
    private int setProject(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String targetInput = StringArgumentType.getString(ctx, "target");
        if (!requireTarget(source, targetInput)) {
            return 0;
        }
        Resolved target = resolve(targetInput, source);
        String project = StringArgumentType.getString(ctx, "project").trim();
        if (project.isEmpty() || project.length() > 40) {
            source.sendFailure(CommandTexts.error("工程名长度必须在 1 到 40 之间。"));
            return 0;
        }
        for (Identity identity : new Identity[]{Identity.BOT, Identity.SHADOW}) {
            services().accounts().saveProject(target.uuid(), identity, project);
        }
        services().accounts().audit("bot.project", target.name(), "工程 -> " + project, operator(source),
                System.currentTimeMillis());
        source.sendSuccess(() -> CommandTexts.ok("已把 " + target.name() + " 归入工程「" + project + "」。"), false);
        source.sendSuccess(() -> CommandTexts.hint("查看工程累计：/themisstats project " + project), false);
        return 1;
    }

    // ------------------------------------------------------------------ adjustment scope

    private record Cell(String category, String dimension, String gameMode, long value) {
    }

    private List<Cell> findCells(UUID uuid, Identity identity, Metric metric, String category,
                                  String dimension, String gameMode) {
        List<Cell> result = new ArrayList<>();
        // Only the collected cells are listed: an adjustment changes the public value, and the
        // public value is built from the collected cells. The imported history stays untouched.
        String sql = "SELECT category, dimension, game_mode, SUM(value) FROM total "
                + "WHERE uuid = ? AND identity = ? AND metric = ? AND source = 'collected' "
                + "AND (? = '' OR category = ?) AND (? = '' OR dimension = ?) AND (? = '' OR game_mode = ?) "
                + "GROUP BY category, dimension, game_mode ORDER BY SUM(value) DESC";
        try (var ps = services().database().connection().prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, identity.id());
            ps.setString(3, metric.id());
            ps.setString(4, category == null ? "" : category);
            ps.setString(5, category == null ? "" : category);
            ps.setString(6, dimension == null ? "" : dimension);
            ps.setString(7, dimension == null ? "" : dimension);
            ps.setString(8, gameMode == null ? "" : gameMode);
            ps.setString(9, gameMode == null ? "" : gameMode);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new Cell(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4)));
                }
            }
        } catch (java.sql.SQLException e) {
            throw new dev.themisstats.storage.StatsRepository.StorageException("查询修正范围失败", e);
        }
        return result;
    }

    private String describeCell(Cell cell) {
        List<String> parts = new ArrayList<>();
        if (!cell.category().isEmpty()) {
            parts.add("分类 " + cell.category());
        }
        if (!cell.dimension().isEmpty()) {
            parts.add("维度 " + cell.dimension());
        }
        if (!cell.gameMode().isEmpty()) {
            parts.add("模式 " + cell.gameMode());
        }
        return parts.isEmpty() ? "整体" : String.join(" / ", parts);
    }
}

