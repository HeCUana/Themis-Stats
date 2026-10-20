package dev.themisstats.bot;

import dev.themisstats.identity.Identity;

import java.util.List;
import java.util.UUID;

/**
 * A sampled state of one bot like object.
 *
 * <p>A snapshot describes <em>what was observed at a given moment</em>. For an object that is
 * offline, the snapshot is the <b>last known</b> state and must be presented as such - it is never
 * shown as if the object were currently online.</p>
 */
public record BotSnapshot(
        UUID uuid,
        Identity identity,
        String name,
        boolean online,
        String dimension,
        double x,
        double y,
        double z,
        double health,
        int food,
        String mainHand,
        String offHand,
        Integer mainHandDurabilityPercent,
        List<String> actionPlan,
        long observedAtMillis,
        long sessionOnlineMillis,
        String sessionId) {

    /** @return whether the position and equipment may only be shown as the last known values */
    public boolean lastKnownOnly() {
        return !online;
    }
}
