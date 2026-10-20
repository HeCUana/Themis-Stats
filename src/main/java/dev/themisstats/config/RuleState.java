package dev.themisstats.config;

import carpet.CarpetServer;
import carpet.api.settings.CarpetRule;
import carpet.api.settings.RuleHelper;
import dev.themisstats.ThemisStats;
import net.minecraft.commands.CommandSourceStack;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Live view over the 15 Themis Stats Carpet rules.
 *
 * <p>Carpet owns the authoritative values; this class only caches the last observed value of every
 * rule so that a change can be reported as {@code old -> new}, and so that consumers can subscribe
 * to rule changes and react <em>immediately</em> instead of waiting for the next slow refresh.</p>
 *
 * <p>Per the specification, no second copy of the global rule values is stored in the database:
 * the database must never be able to override what Carpet holds.</p>
 */
public final class RuleState {

    /** A rule change notification. */
    public interface Listener {
        void onRuleChanged(String ruleName, Object oldValue, Object newValue, CommandSourceStack source);
    }

    private static final List<String> RULE_NAMES = List.of(
            "themisStatsRecording",
            "themisStatsSidebar",
            "themisStatsHumanBoard",
            "themisStatsBotBoard",
            "themisStatsShadowBoard",
            "themisStatsDefaultBoard",
            "themisStatsDefaultGroup",
            "themisStatsDefaultDisplay",
            "themisStatsCarouselSeconds",
            "themisStatsRefreshSeconds",
            "themisStatsAllowPlayerChoice",
            "themisStatsPublicQuery",
            "themisStatsBotMonitor",
            "themisStatsBotSampleSeconds",
            "themisStatsBotLocations");

    /** @return the names of all Themis Stats rules, in a stable order */
    public static List<String> ruleNames() {
        return RULE_NAMES;
    }

    private final Map<String, Object> lastKnown = new LinkedHashMap<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final List<RuleChangeLogEntry> changeLog = new CopyOnWriteArrayList<>();

    /** Recorded rule changes, for the admin audit trail. */
    public record RuleChangeLogEntry(String ruleName, Object oldValue, Object newValue,
                                     String operator, long wallClockMillis) {
    }

    /**
     * Called by Carpet's rule observer. The rule value has already been updated at this point,
     * so the previous value comes from our own cache.
     */
    public void onRuleChanged(CommandSourceStack source, CarpetRule<?> rule, String userInput) {
        String name = rule.name();
        if (!RULE_NAMES.contains(name)) {
            return;
        }
        Object oldValue = lastKnown.put(name, rule.value());
        if (oldValue == null) {
            // First observation (startup / config load): nothing changed yet.
            return;
        }
        if (oldValue.equals(rule.value())) {
            return;
        }
        String operator = source == null ? "系统" : source.getTextName();
        changeLog.add(new RuleChangeLogEntry(name, oldValue, rule.value(), operator,
                System.currentTimeMillis()));
        for (Listener listener : listeners) {
            try {
                listener.onRuleChanged(name, oldValue, rule.value(), source);
            } catch (RuntimeException e) {
                ThemisStats.LOGGER.error("[ThemisStats] rule listener failed for {}", name, e);
            }
        }
    }

    /** Snapshot the current values of all Themis Stats rules. */
    public void captureInitialState() {
        for (String name : RULE_NAMES) {
            CarpetRule<?> rule = CarpetServer.settingsManager == null
                    ? null : CarpetServer.settingsManager.getCarpetRule(name);
            if (rule != null) {
                lastKnown.put(name, rule.value());
            }
        }
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /** @return an immutable snapshot of the last observed rule values */
    public Map<String, Object> snapshot() {
        return Map.copyOf(lastKnown);
    }

    /** @return recorded rule changes, newest last */
    public List<RuleChangeLogEntry> recentChanges() {
        return List.copyOf(changeLog);
    }

    // ---------------------------------------------------------------- typed accessors

    public boolean recording() {
        return ThemisStatsRules.themisStatsRecording;
    }

    public boolean sidebar() {
        return ThemisStatsRules.themisStatsSidebar;
    }

    public boolean humanBoard() {
        return ThemisStatsRules.themisStatsHumanBoard;
    }

    public boolean botBoard() {
        return ThemisStatsRules.themisStatsBotBoard;
    }

    public boolean shadowBoard() {
        return ThemisStatsRules.themisStatsShadowBoard;
    }

    public String defaultBoard() {
        return ThemisStatsRules.themisStatsDefaultBoard;
    }

    public String defaultGroup() {
        return ThemisStatsRules.themisStatsDefaultGroup;
    }

    public String defaultDisplay() {
        return ThemisStatsRules.themisStatsDefaultDisplay;
    }

    public int carouselSeconds() {
        return ThemisStatsRules.themisStatsCarouselSeconds;
    }

    public int refreshSeconds() {
        return ThemisStatsRules.themisStatsRefreshSeconds;
    }

    public boolean allowPlayerChoice() {
        return ThemisStatsRules.themisStatsAllowPlayerChoice;
    }

    public boolean publicQuery() {
        return ThemisStatsRules.themisStatsPublicQuery;
    }

    public boolean botMonitor() {
        return ThemisStatsRules.themisStatsBotMonitor;
    }

    public int botSampleSeconds() {
        return ThemisStatsRules.themisStatsBotSampleSeconds;
    }

    public String botLocations() {
        return ThemisStatsRules.themisStatsBotLocations;
    }

    /** @return the default value of a rule, as Carpet reports it */
    public static String defaultOf(String ruleName) {
        CarpetRule<?> rule = CarpetServer.settingsManager == null
                ? null : CarpetServer.settingsManager.getCarpetRule(ruleName);
        return rule == null ? "?" : RuleHelper.toRuleString(rule.defaultValue());
    }
}
