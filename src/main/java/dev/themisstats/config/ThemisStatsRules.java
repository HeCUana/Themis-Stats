package dev.themisstats.config;

import carpet.api.settings.CarpetRule;
import carpet.api.settings.Rule;
import carpet.api.settings.Validator;
import net.minecraft.commands.CommandSourceStack;
import org.jetbrains.annotations.Nullable;

/**
 * The 18 Themis Stats rules: 15 from the first release and 3 for the run alerts.
 *
 * <p>These fields are registered into Carpet's <strong>main</strong> settings manager
 * (the one behind {@code /carpet}), so they are managed with {@code /carpet list themisStats},
 * {@code /carpet themisStatsSidebar true} and persisted with
 * {@code /carpet setDefault themisStatsSidebar true} / {@code /carpet removeDefault ...}
 * exactly like any built-in Carpet rule.</p>
 *
 * <p><strong>Why the field name matters:</strong> {@code carpet.settings.ParsedRule} derives the
 * rule name from {@link java.lang.reflect.Field#getName()} for the modern {@link Rule} annotation.
 * The field names below therefore are exactly the public rule names required by the spec
 * ({@code themisStatsRecording}, {@code themisStatsSidebar}, ...).</p>
 *
 * <p>Rule <em>names</em> are stable; rule <em>values</em> live here. Never keep a second copy of
 * these values in the database - Carpet owns them, per the design document.</p>
 */
public final class ThemisStatsRules {

    private ThemisStatsRules() {
    }

    /** The rule category used for every Themis Stats rule. */
    public static final String CATEGORY = "themisStats";

    // ------------------------------------------------------------------ collection / display

    /**
     * Whether statistics are recorded at all. Turning this off pauses metric collection and the
     * {@code online} metric, but never deletes previously collected data.
     */
    @Rule(categories = {CATEGORY})
    public static boolean themisStatsRecording = true;

    /** Whether the Themis Stats sidebar service is offered. Turning it off does not stop collection. */
    @Rule(categories = {CATEGORY})
    public static boolean themisStatsSidebar = true;

    // ------------------------------------------------------------------ public boards

    /** Whether the public human leaderboard and public human totals are open. */
    @Rule(categories = {CATEGORY})
    public static boolean themisStatsHumanBoard = true;

    /** Whether the public bot leaderboard and public bot totals are open. */
    @Rule(categories = {CATEGORY})
    public static boolean themisStatsBotBoard = true;

    /** Whether the public shadow leaderboard and public shadow totals are open. Off by default. */
    @Rule(categories = {CATEGORY})
    public static boolean themisStatsShadowBoard = false;

    // ------------------------------------------------------------------ defaults

    /** The metric shown by default on the sidebar. Must be a metric enabled in this build. */
    @Rule(categories = {CATEGORY}, options = {"online", "mining", "placement", "kills", "deaths",
            "villager_trades", "fishing", "elytra_distance", "damage_dealt", "damage_taken", "movement",
            "crafted", "picked_up", "dropped", "piglin_barters"})
    public static String themisStatsDefaultBoard = "mining";

    /** The default identity group. Constrained by the matching public board switch. */
    @Rule(categories = {CATEGORY}, options = {"human", "bot", "shadow"})
    public static String themisStatsDefaultGroup = "human";

    /** The default sidebar mode for players. */
    @Rule(categories = {CATEGORY}, options = {"off", "fixed", "carousel"})
    public static String themisStatsDefaultDisplay = "fixed";

    // ------------------------------------------------------------------ intervals

    /** Target seconds between carousel rotations. */
    @Rule(categories = {CATEGORY}, validators = {IntRange.Min5Max300.class})
    public static int themisStatsCarouselSeconds = 15;

    /** Target seconds between ranking / sidebar snapshot refreshes. */
    @Rule(categories = {CATEGORY}, validators = {IntRange.Min1Max60.class})
    public static int themisStatsRefreshSeconds = 5;

    // ------------------------------------------------------------------ permissions

    /** Whether players may override the server default sidebar preference. */
    @Rule(categories = {CATEGORY})
    public static boolean themisStatsAllowPlayerChoice = true;

    /** Whether ordinary players may query public boards and other players' public totals. */
    @Rule(categories = {CATEGORY})
    public static boolean themisStatsPublicQuery = true;

    // ------------------------------------------------------------------ bot observation

    /** Whether bot run state is sampled. Independent of metric collection. */
    @Rule(categories = {CATEGORY})
    public static boolean themisStatsBotMonitor = true;

    /** Target seconds between bot state samples. */
    @Rule(categories = {CATEGORY}, validators = {IntRange.Min1Max30.class})
    public static int themisStatsBotSampleSeconds = 2;

    /** Who may see precise bot positions, equipment and restricted run details. */
    @Rule(categories = {CATEGORY}, options = {"ops", "owners", "all"})
    public static String themisStatsBotLocations = "owners";

    // ------------------------------------------------------------------ P1

    /**
     * Whether the configured run alerts are sent at all.
     *
     * <p>Subscriptions may be stored while this is off, so an operator can prepare the
     * subscriptions and then switch the alerts on.</p>
     */
    @Rule(categories = {CATEGORY})
    public static boolean themisStatsBotAlerts = false;

    /**
     * The game modes that count towards the public leaderboards.
     *
     * <p>Default is the same scope as the first release: survival and adventure. Creative and
     * spectator are collected as well, they are just not public unless they are listed here.</p>
     */
    @Rule(categories = {CATEGORY}, options = {"survival,adventure", "survival,adventure,creative",
            "survival,adventure,creative,spectator", "survival"})
    public static String themisStatsPublicModes = "survival,adventure";

    /**
     * The bot purposes for which the "no progress" alert may fire.
     *
     * <p>The alert only ever says that the named statistic did not grow inside the observation
     * window; it never claims that a machine is broken. The {@code loader} purpose is not part of
     * the default list, because a bot that only keeps the area loaded is expected to be idle.</p>
     */
    @Rule(categories = {CATEGORY}, options = {"mining,combat,use,custom", "mining,combat,use,custom,loader",
            "mining", "combat", "use", "custom", "loader", "mining,combat,use,custom,loader,any"})
    public static String themisStatsStallPurposes = "mining,combat,use,custom";

    // ------------------------------------------------------------------ validators

    /**
     * Integer range validators. Carpet rejects out-of-range values and keeps the previous value,
     * which is exactly the behaviour the spec requires ("非法值拒绝，原值保留").
     */
    public abstract static class IntRange extends Validator<Integer> {
        private final int min;
        private final int max;

        protected IntRange(int min, int max) {
            this.min = min;
            this.max = max;
        }

        @Override
        public Integer validate(@Nullable CommandSourceStack source, CarpetRule<Integer> changingRule,
                                 Integer newValue, String userInput) {
            if (newValue == null) {
                return null;
            }
            if (newValue < min || newValue > max) {
                if (source != null) {
                    notifyFailure(source, changingRule, userInput);
                }
                return null;
            }
            return newValue;
        }

        @Override
        public String description() {
            return "必须在 " + min + " 到 " + max + " 之间";
        }

        public static class Min5Max300 extends IntRange {
            public Min5Max300() {
                super(5, 300);
            }
        }

        public static class Min1Max60 extends IntRange {
            public Min1Max60() {
                super(1, 60);
            }
        }

        public static class Min1Max30 extends IntRange {
            public Min1Max30() {
                super(1, 30);
            }
        }
    }
}
