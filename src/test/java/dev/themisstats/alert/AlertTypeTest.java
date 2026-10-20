package dev.themisstats.alert;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests of the alert kinds and of their bit mask.
 *
 * <p>The mask is what a subscription stores, so every kind needs its own bit and the helper
 * methods must never lose or add one.</p>
 */
class AlertTypeTest {

    @Test
    void thereAreFiveKinds() {
        assertEquals(5, AlertType.values().length);
    }

    @Test
    void everyKindHasItsOwnBit() {
        int seen = 0;
        for (AlertType type : AlertType.values()) {
            assertTrue(type.bit() > 0, type.id());
            assertEquals(0, seen & type.bit(), "位重复：" + type.id());
            seen |= type.bit();
        }
        assertEquals(AlertType.allMask(), seen);
    }

    @Test
    void theMaskOfAllKindsContainsEveryKind() {
        int mask = AlertType.allMask();
        for (AlertType type : AlertType.values()) {
            assertTrue((mask & type.bit()) != 0, type.id());
        }
        assertEquals(List.of("low_health", "low_durability", "unexpected_offline", "metric_stall",
                "save_failure"), AlertType.namesIn(mask));
    }

    @Test
    void aMaskOfOneKindContainsOnlyThatKind() {
        assertEquals(List.of("low_health"),
                AlertType.namesIn(AlertType.LOW_HEALTH.bit()));
        assertEquals(List.of("metric_stall"),
                AlertType.namesIn(AlertType.METRIC_STALL.bit()));
    }

    @Test
    void aKindIsFoundByItsId() {
        assertEquals(AlertType.LOW_HEALTH, AlertType.byId("low_health"));
        assertEquals(AlertType.LOW_HEALTH, AlertType.byId("LOW_HEALTH"));
        assertEquals(AlertType.METRIC_STALL, AlertType.byId("metric_stall"));
        assertNull(AlertType.byId("nope"));
        assertNull(AlertType.byId(null));
    }

    @Test
    void aListOfIdsBecomesAMask() {
        int mask = AlertType.maskOf(List.of("low_health", "metric_stall"));
        assertEquals(AlertType.LOW_HEALTH.bit() | AlertType.METRIC_STALL.bit(), mask);
        assertEquals(0, AlertType.maskOf(List.of()));
    }

    @Test
    void everyKindHasAReadableName() {
        for (AlertType type : AlertType.values()) {
            assertNotNull(type.displayName());
            assertFalse(type.displayName().isBlank());
        }
    }
}
