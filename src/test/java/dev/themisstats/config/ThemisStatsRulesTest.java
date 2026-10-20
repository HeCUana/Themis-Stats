package dev.themisstats.config;

import carpet.api.settings.Rule;
import dev.themisstats.metric.Metric;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests of the rule definitions.
 *
 * <p>The most important one is the translation test: Carpet refuses to register a rule whose
 * description is missing, and that would take the whole server down at start up. The test makes
 * sure that every rule has its texts before that can happen.</p>
 */
class ThemisStatsRulesTest {

    private static List<String> ruleNames() {
        List<String> names = new ArrayList<>();
        for (Field field : ThemisStatsRules.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getName().startsWith("themisStats")) {
                names.add(field.getName());
            }
        }
        return names;
    }

    @Test
    void everyRuleHasItsTexts() {
        Map<String, String> texts = RuleTexts.forLanguage("en_us");
        List<String> missing = new ArrayList<>();
        for (String name : ruleNames()) {
            if (!texts.containsKey("carpet.rule." + name + ".name")) {
                missing.add(name + ".name");
            }
            if (!texts.containsKey("carpet.rule." + name + ".desc")) {
                missing.add(name + ".desc");
            }
        }
        assertTrue(missing.isEmpty(), "缺少规则文本: " + missing);
    }

    @Test
    void theCategoryHasAText() {
        assertTrue(RuleTexts.forLanguage("en_us").containsKey("carpet.category.themisStats"));
    }

    @Test
    void thereAreExactlyEighteenRules() {
        assertEquals(18, ruleNames().size(), "P0 15 条 + P1 3 条");
    }

    @Test
    void theRuleStateKnowsEveryRule() {
        // The rule state is what reacts to a rule change. A rule that is missing there would be
        // changed silently, without a log entry and without a refresh.
        List<String> known = RuleState.ruleNames();
        List<String> missing = new ArrayList<>();
        for (String name : ruleNames()) {
            if (!known.contains(name)) {
                missing.add(name);
            }
        }
        assertTrue(missing.isEmpty(), "RuleState 不知道这些规则: " + missing);
        assertEquals(ruleNames().size(), known.size(), "RuleState 里有多余的规则名");
    }

    @Test
    void theDefaultBoardCanBeEveryEnabledMetric() {
        // The option list of the rule has to offer every metric this build can collect, otherwise
        // a new metric could never be shown on the sidebar.
        Rule annotation = null;
        for (Field field : ThemisStatsRules.class.getDeclaredFields()) {
            if (field.getName().equals("themisStatsDefaultBoard")) {
                annotation = field.getAnnotation(Rule.class);
            }
        }
        assertNotNull(annotation, "themisStatsDefaultBoard 缺少 @Rule 注解");
        List<String> options = List.of(annotation.options());
        for (Metric metric : Metric.values()) {
            assertTrue(options.contains(metric.id()), "默认指标下拉里缺少 " + metric.id());
        }
    }

    @Test
    void theP1AlertsRuleDefaultsToOff() {
        assertFalse(ThemisStatsRules.themisStatsBotAlerts, "运行提醒必须默认关闭");
    }

    @Test
    void thePublicModeScopeDefaultsToSurvivalAndAdventure() {
        assertEquals("survival,adventure", ThemisStatsRules.themisStatsPublicModes);
    }

    @Test
    void theTextsAreAlsoAvailableForOtherLanguages() {
        // The Chinese texts are shipped for every language, so that the /carpet menu always
        // shows them no matter which language the server runs in.
        for (String language : new String[]{"en_us", "zh_cn", "zh_tw", "fr_fr", "es_ar", "pt_br"}) {
            assertFalse(RuleTexts.forLanguage(language).isEmpty(), "语言 " + language + " 缺少文本");
        }
    }
}
