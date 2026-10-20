package dev.themisstats.api;

import dev.themisstats.identity.Identity;
import dev.themisstats.metric.Metric;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests of the public query interface.
 *
 * <p>The interface is what another mod or a script uses. Without a running server it must answer
 * with empty results instead of throwing, because a script may ask before the world was loaded or
 * after it was closed.</p>
 */
class ThemisStatsApiTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    @Test
    void theApiIsNotAvailableWithoutARunningServer() {
        assertFalse(ThemisStatsApi.available());
    }

    @Test
    void theLeaderboardIsEmptyWithoutARunningServer() {
        ThemisStatsApi.Result result = ThemisStatsApi.top(Metric.MINING, Identity.BOT, 1, 10);
        assertNotNull(result);
        assertTrue(result.entries().isEmpty());
        assertEquals(0, result.totalObjects());
        assertEquals(0L, result.publicTotal());
    }

    @Test
    void theValueOfOneObjectIsZeroWithoutARunningServer() {
        assertEquals(0L, ThemisStatsApi.valueOf(ALICE, Metric.MINING, Identity.BOT));
    }

    @Test
    void theBreakdownIsEmptyWithoutARunningServer() {
        Map<String, Long> breakdown = ThemisStatsApi.breakdown(ALICE, Metric.MINING, Identity.BOT);
        assertNotNull(breakdown);
        assertTrue(breakdown.isEmpty());
    }

    @Test
    void theOnlineCountsAreEmptyWithoutARunningServer() {
        Map<Identity, Integer> counts = ThemisStatsApi.onlineCounts();
        assertNotNull(counts);
        assertTrue(counts.isEmpty());
    }
}
