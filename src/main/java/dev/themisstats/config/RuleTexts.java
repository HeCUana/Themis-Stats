package dev.themisstats.config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Rule name / description / extra-info texts for the {@code themisStats} rule category.
 *
 * <p>Carpet resolves the text of a modern {@link carpet.api.settings.Rule} through its translation
 * system, using the keys
 * {@code carpet.rule.<ruleName>.name}, {@code carpet.rule.<ruleName>.desc} and
 * {@code carpet.rule.<ruleName>.extra.<n>}. Because Themis Stats registers into Carpet's
 * <em>main</em> settings manager, the namespace is {@code carpet.}.</p>
 *
 * <p>These texts are surfaced by the {@code /carpet} interaction menu. They are Chinese by design:
 * the product specification requires Chinese rule descriptions, and a server may run Carpet with any
 * language setting, so the Chinese text is shipped for every language this mod provides. See
 * {@code docs/VERSIONS.md}.</p>
 */
final class RuleTexts {

    private RuleTexts() {
    }

    private static final Map<String, String> BASE = build();

    private static Map<String, String> build() {
        Map<String, String> m = new LinkedHashMap<>();

        m.put("carpet.category.themisStats", "Themis Stats 统计");

        rule(m, "themisStatsRecording",
                "统计采集开关",
                "是否采集统计指标与统计在线时长。关闭后只暂停采集，不删除任何历史数据，指标榜与查询仍可查看历史累计。",
                "类型：布尔值｜默认：true",
                "关闭后仍会保存最小的身份、连接会话与暂停区间元数据，供运行观察与崩溃恢复使用；",
                "会话实际在线时长与有效统计时长会分别展示。");

        rule(m, "themisStatsSidebar",
                "个人侧边栏开关",
                "是否开放本 Mod 的个人侧边栏服务。关闭只影响显示，不影响指标采集与历史查询。",
                "类型：布尔值｜默认：true",
                "关闭侧栏后停止侧栏刷新任务，不会清空其他 Mod 或插件的计分板与队伍。");

        rule(m, "themisStatsHumanBoard",
                "真人公共榜开关",
                "是否开放真人公共排行榜与真人公共汇总。关闭后普通玩家无法查询真人公开榜，管理员与本人查询仍然保留。",
                "类型：布尔值｜默认：true",
                "关闭不影响真人指标的采集与累计。");

        rule(m, "themisStatsBotBoard",
                "假人公共榜开关",
                "是否开放普通假人公共排行榜与普通假人公共汇总。关闭后普通玩家无法查询假人公开榜，管理员与本人查询仍然保留。",
                "类型：布尔值｜默认：true",
                "关闭不影响假人指标的采集与累计。");

        rule(m, "themisStatsShadowBoard",
                "Shadow 公共榜开关",
                "是否开放 Shadow 公共排行榜与 Shadow 公共汇总。关闭时普通玩家界面不披露其分项汇总，但本人与管理员仍可查看自己的 Shadow 资料。",
                "类型：布尔值｜默认：false",
                "Shadow 始终独立分账，不会混入真人榜。");

        rule(m, "themisStatsDefaultBoard",
                "默认统计指标",
                "新玩家或恢复默认时采用的指标。必须是本版本已启用的指标，非法值会被拒绝且原值保留。",
                "类型：字符串｜默认：mining",
                "可用值：online、mining、placement、kills、deaths、villager_trades、fishing、elytra_distance。");

        rule(m, "themisStatsDefaultGroup",
                "默认身份组",
                "默认侧栏与排行榜使用的身份组，受对应公共榜开关约束。",
                "类型：字符串｜默认：human",
                "可用值：human、bot、shadow。",
                "对应公共榜关闭时不会自动开启该榜，而是回退到已开放的真人组，再到普通假人组，都不可用时关闭显示。");

        rule(m, "themisStatsDefaultDisplay",
                "默认侧栏模式",
                "玩家首次使用或执行 /themisstats board default 时采用的侧栏模式。",
                "类型：字符串｜默认：fixed",
                "可用值：off（关闭）、fixed（固定指标）、carousel（自动轮播）。");

        rule(m, "themisStatsCarouselSeconds",
                "轮播间隔秒数",
                "侧栏轮播切换间隔的目标真实秒数。",
                "类型：整数｜默认：15",
                "有效范围：5 到 300 秒，超出范围的值会被拒绝且原值保留。");

        rule(m, "themisStatsRefreshSeconds",
                "刷新间隔秒数",
                "排行榜与侧栏数据快照刷新的目标间隔。与轮播间隔相互独立。",
                "类型：整数｜默认：5",
                "有效范围：1 到 60 秒，超出范围的值会被拒绝且原值保留。",
                "主线程严重卡顿时允许延迟，界面会显示实际快照时间。");

        rule(m, "themisStatsAllowPlayerChoice",
                "允许个人选择开关",
                "是否允许玩家自行覆盖全服默认的侧栏偏好。关闭时强制使用服务器默认，但会保留个人偏好以便重新开放后恢复。",
                "类型：布尔值｜默认：true",
                "PublicQuery 关闭优先于个人偏好，不能通过侧栏绕过。");

        rule(m, "themisStatsPublicQuery",
                "公开查询开关",
                "是否允许普通玩家在公共聊天查询公开榜与他人公开汇总。关闭时仍可查看本人累计与名次，但不返回他人姓名、数值或受限汇总。",
                "类型：布尔值｜默认：true",
                "关闭后公共分享、公共侧栏与补全也一并受限。");

        rule(m, "themisStatsBotMonitor",
                "假人状态观察开关",
                "是否采样在线假人的运行状态（维度、坐标、生命、饥饿、装备、动作计划等）。",
                "类型：布尔值｜默认：true",
                "关闭后停止状态采样，已采集历史保留，当前状态标记为不可观察；",
                "指标采集由 themisStatsRecording 单独决定，两者互不影响。");

        rule(m, "themisStatsBotSampleSeconds",
                "假人状态采样间隔秒数",
                "在线假人状态快照的采样间隔。采样只读取有限字段，不扫描周边农场，也不加载区块。",
                "类型：整数｜默认：2",
                "有效范围：1 到 30 秒，超出范围的值会被拒绝且原值保留。");

        rule(m, "themisStatsBotLocations",
                "假人位置可见范围",
                "精确位置、装备与受限运行细节的可见范围。管理员始终可查。",
                "类型：字符串｜默认：owners",
                "可用值：ops（仅 OP）、owners（负责人与管理员）、all（所有玩家）。",
                "离线对象的坐标与装备只能显示为最后已知值，不会伪装成当前状态。");

        rule(m, "themisStatsBotAlerts",
                "运行提醒开关",
                "是否发送已配置、已订阅的运行提醒（低血量、工具低耐久、意外离线、指定统计停滞、保存异常）。",
                "类型：布尔｜默认：false",
                "关闭时不会发送任何提醒，但订阅记录可以提前保存，打开后立即生效。",
                "提醒只发给有权限且已订阅的人，不会自动清空背包、补充工具、传送假人或修改它的动作。");

        rule(m, "themisStatsPublicModes",
                "公开模式范围",
                "哪些游戏模式计入公共排行榜与公共汇总。",
                "类型：字符串｜默认：survival,adventure",
                "可用值：survival、adventure、creative、spectator，用英文逗号分隔。",
                "所有模式的数据都会被采集保存，这里只决定哪些模式出现在公共榜上。",
                "统计在线时长不受此设置影响，它按各身份实际被采集的时长计算。");

        rule(m, "themisStatsStallPurposes",
                "停滞提醒用途范围",
                "哪些用途的假人允许触发「指定统计在观察期内没有增长」的提醒。",
                "类型：字符串｜默认：mining,combat,use,custom",
                "可用值：loader、mining、combat、use、custom、any，用英文逗号分隔。",
                "loader（挂机加载）默认不在列表里：只用来保持区块加载的假人本来就不该有行为增量。",
                "提醒只会说明「观察期内指定指标无增长」，不会断言机器故障。",
                "该提醒还要求订阅时明确指定要观察的指标。");

        return m;
    }

    private static void rule(Map<String, String> m, String name, String displayName, String desc, String... extra) {
        m.put("carpet.rule." + name + ".name", displayName);
        m.put("carpet.rule." + name + ".desc", desc);
        for (int i = 0; i < extra.length; i++) {
            m.put("carpet.rule." + name + ".extra." + i, extra[i]);
        }
    }

    /**
     * @param lang the language id Carpet is currently configured with
     * @return the Themis Stats rule texts, or an empty map for unsupported languages
     */
    static Map<String, String> forLanguage(String lang) {
        // The Chinese texts are shipped for every language so that the /carpet interaction menu
        // always shows the Chinese rule descriptions the specification asks for.
        if (lang == null) {
            return Map.of();
        }
        return BASE;
    }
}
