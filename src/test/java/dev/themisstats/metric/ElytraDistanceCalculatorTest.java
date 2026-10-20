package dev.themisstats.metric;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests of the elytra distance measurement rules.
 *
 * <p>These are the rules that decide what counts as glide distance and what is a teleport jump.
 * They are pure, so they can be tested exactly.</p>
 */
class ElytraDistanceCalculatorTest {

    @Test
    void normalGlideIsAccumulated() {
        ElytraDistanceCalculator calculator = new ElytraDistanceCalculator();
        // The first tick only initialises the measurement.
        assertEquals(0L, calculator.tick("minecraft:overworld", 0, 70, 0, true));
        // Four ticks of three blocks each.
        assertEquals(3000L, calculator.tick("minecraft:overworld", 3, 70, 0, true));
        assertEquals(3000L, calculator.tick("minecraft:overworld", 6, 70, 0, true));
        assertEquals(3000L, calculator.tick("minecraft:overworld", 9, 70, 0, true));
        assertEquals(3000L, calculator.tick("minecraft:overworld", 12, 70, 0, true));
        assertEquals(12000L, calculator.total());
    }

    @Test
    void theLaunchAndTheLandingAreNotCounted() {
        ElytraDistanceCalculator calculator = new ElytraDistanceCalculator();
        // Not gliding yet: the position is only remembered.
        calculator.tick("minecraft:overworld", 0, 70, 0, false);
        // The tick on which the gliding starts must not count the jump from the launch.
        assertEquals(0L, calculator.tick("minecraft:overworld", 0, 70, 0, true));
        // Now it is gliding, the distance counts.
        assertEquals(2000L, calculator.tick("minecraft:overworld", 2, 70, 0, true));
        // The tick on which the gliding stops must not count either.
        assertEquals(0L, calculator.tick("minecraft:overworld", 4, 70, 0, false));
        assertEquals(2000L, calculator.total());
    }

    @Test
    void aTeleportIsNotCounted() {
        ElytraDistanceCalculator calculator = new ElytraDistanceCalculator();
        calculator.tick("minecraft:overworld", 0, 70, 0, true);
        // A jump of 500 blocks is a teleport, not a glide.
        assertEquals(0L, calculator.tick("minecraft:overworld", 500, 70, 0, true));
        // The measurement continues from the new position.
        assertEquals(1000L, calculator.tick("minecraft:overworld", 501, 70, 0, true));
        assertEquals(1000L, calculator.total());
    }

    @Test
    void aDimensionChangeIsNotCounted() {
        ElytraDistanceCalculator calculator = new ElytraDistanceCalculator();
        calculator.tick("minecraft:overworld", 0, 70, 0, true);
        assertEquals(2000L, calculator.tick("minecraft:overworld", 2, 70, 0, true));
        // Crossing a dimension restarts the measurement.
        assertEquals(0L, calculator.tick("minecraft:the_nether", 2, 70, 0, true));
        // One block further in the new dimension.
        assertEquals(1000L, calculator.tick("minecraft:the_nether", 3, 70, 0, true));
        assertEquals(3000L, calculator.total());
    }

    @Test
    void theDistanceIsStoredInThousandthsOfABlock() {
        ElytraDistanceCalculator calculator = new ElytraDistanceCalculator();
        calculator.tick("minecraft:overworld", 0, 70, 0, true);
        // A diagonal tick of 3 / 4 / 0 blocks is exactly five blocks long.
        long delta = calculator.tick("minecraft:overworld", 3, 74, 0, true);
        assertEquals(5000L, delta);
    }

    @Test
    void aResetStartsFromZero() {
        ElytraDistanceCalculator calculator = new ElytraDistanceCalculator();
        calculator.tick("minecraft:overworld", 0, 70, 0, true);
        calculator.tick("minecraft:overworld", 2, 70, 0, true);
        assertTrue(calculator.total() > 0);
        calculator.reset();
        assertEquals(0L, calculator.total());
        assertEquals(0L, calculator.tick("minecraft:overworld", 2, 70, 0, true));
    }
}
