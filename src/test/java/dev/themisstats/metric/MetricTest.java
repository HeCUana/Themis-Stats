package dev.themisstats.metric;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests of the metric catalogue.
 *
 * <p>The catalogue is what the commands, the sidebar, the Scarpet functions and the export all use.
 * A metric that is missing here would be collected but never visible, so the ids have to be unique
 * and every metric needs a readable name and a unit.</p>
 */
class MetricTest {

    @Test
    void thereAreFifteenMetrics() {
        assertEquals(15, Metric.values().length, "P0 8 类 + P1 7 类");
    }

    @Test
    void everyMetricIdIsUnique() {
        Set<String> ids = new HashSet<>();
        for (Metric metric : Metric.values()) {
            assertTrue(ids.add(metric.id()), "指标 id 重复：" + metric.id());
        }
        assertEquals(Metric.values().length, ids.size());
    }

    @Test
    void everyMetricHasANameAndAUnit() {
        for (Metric metric : Metric.values()) {
            assertNotNull(metric.displayName());
            assertFalse(metric.displayName().isBlank(), metric.id());
            assertNotNull(metric.unit());
            assertFalse(metric.unit().display().isBlank(), metric.id());
        }
    }

    @Test
    void aMetricIsFoundByItsId() {
        assertEquals(Metric.MINING, Metric.byId("mining"));
        assertEquals(Metric.MINING, Metric.byId("MINING"));
        assertEquals(Metric.ELYTRA_DISTANCE, Metric.byId("elytra_distance"));
        assertNull(Metric.byId("nope"));
        assertNull(Metric.byId(null));
    }

    @Test
    void theOnlineMetricIsNotFilteredByGameMode() {
        assertFalse(Metric.ONLINE.isModeClassified());
        for (Metric metric : Metric.values()) {
            if (metric != Metric.ONLINE) {
                assertTrue(metric.isModeClassified(), metric.id());
            }
        }
    }

    @Test
    void theSevenEnhancedMetricsAreMarkedAsSuch() {
        List<Metric> enhanced = List.of(Metric.DAMAGE_DEALT, Metric.DAMAGE_TAKEN, Metric.MOVEMENT,
                Metric.CRAFTED, Metric.PICKED_UP, Metric.DROPPED, Metric.PIGLIN_BARTERS);
        for (Metric metric : Metric.values()) {
            assertEquals(enhanced.contains(metric), metric.isEnhanced(), metric.id());
        }
        assertEquals(7, enhanced.size());
    }

    @Test
    void theDamageMetricsAreMeasuredInLifePoints() {
        assertEquals(Metric.Unit.DAMAGE, Metric.DAMAGE_DEALT.unit());
        assertEquals(Metric.Unit.DAMAGE, Metric.DAMAGE_TAKEN.unit());
        assertEquals(Metric.Unit.MILLI_BLOCK, Metric.MOVEMENT.unit());
        assertEquals(Metric.Unit.COUNT, Metric.CRAFTED.unit());
        assertEquals(Metric.Unit.COUNT, Metric.PICKED_UP.unit());
        assertEquals(Metric.Unit.COUNT, Metric.DROPPED.unit());
        assertEquals(Metric.Unit.COUNT, Metric.PIGLIN_BARTERS.unit());
    }

    @Test
    void onlyTheMetricsWithABreakdownCanBeSplitUp() {
        for (Metric metric : Metric.values()) {
            assertEquals(metric.hasCategory(), metric.supportsFineBoards(), metric.id());
        }
        assertTrue(Metric.MINING.supportsFineBoards());
        assertFalse(Metric.ONLINE.supportsFineBoards());
    }

    @Test
    void theIdListNamesEveryMetric() {
        String list = Metric.idList();
        for (Metric metric : Metric.values()) {
            assertTrue(list.contains(metric.id()), metric.id());
        }
    }

    @Test
    void aResourceLocationCategoryMayBeWrittenWithoutItsNamespace() {
        assertEquals("minecraft:stone", Metric.MINING.normalizeCategory("stone"));
        assertEquals("minecraft:stone", Metric.MINING.normalizeCategory("minecraft:stone"));
        assertEquals("minecraft:zombie", Metric.KILLS.normalizeCategory("zombie"));
    }

    @Test
    void aPlainWordCategoryIsNeverGuessed() {
        // The death, damage and movement categories are words, not resource locations. Adding a
        // namespace to them would silently show an empty leaderboard.
        assertEquals("mob", Metric.DEATHS.normalizeCategory("mob"));
        assertEquals("genericKill", Metric.DEATHS.normalizeCategory("genericKill"));
        assertEquals("walk", Metric.MOVEMENT.normalizeCategory("walk"));
        assertEquals("mob", Metric.DAMAGE_TAKEN.normalizeCategory("mob"));
    }

    @Test
    void anEmptyCategoryStaysEmpty() {
        for (Metric metric : Metric.values()) {
            assertEquals("", metric.normalizeCategory(""));
            assertNull(metric.normalizeCategory(null));
        }
    }

    @Test
    void theIdentifierCategoriesAreTheBlockItemAndEntityOnes() {
        assertTrue(Metric.MINING.categoryIsIdentifier());
        assertTrue(Metric.PLACEMENT.categoryIsIdentifier());
        assertTrue(Metric.KILLS.categoryIsIdentifier());
        assertTrue(Metric.DAMAGE_DEALT.categoryIsIdentifier());
        assertTrue(Metric.CRAFTED.categoryIsIdentifier());
        assertTrue(Metric.PICKED_UP.categoryIsIdentifier());
        assertTrue(Metric.DROPPED.categoryIsIdentifier());
        assertFalse(Metric.DEATHS.categoryIsIdentifier());
        assertFalse(Metric.DAMAGE_TAKEN.categoryIsIdentifier());
        assertFalse(Metric.MOVEMENT.categoryIsIdentifier());
    }
}
