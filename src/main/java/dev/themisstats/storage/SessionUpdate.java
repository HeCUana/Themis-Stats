package dev.themisstats.storage;

/**
 * The data that settles (or checkpoints) a session.
 *
 * <p>Session rows are created by the background writer, so a session that has just started is not
 * yet in the database. The writer therefore also knows how to create a session row.</p>
 */
public record SessionUpdate(
        String sessionId,
        String bootId,
        java.util.UUID accountUuid,
        String identity,
        long startWall,
        /** {@code null} while the session is still running */
        Long endWall,
        String endReason,
        long checkpointWall,
        long checkpointNano,
        long actualOnlineMillis,
        long collectedMillis,
        String initialDimension,
        Double initialX,
        Double initialY,
        Double initialZ,
        String lastDimension,
        Double lastX,
        Double lastY,
        Double lastZ,
        String ownerUuid,
        String ownerName,
        String purpose,
        String note,
        boolean settled,
        String gapNote) {

    /** @return whether this update closes the session */
    public boolean isSettlement() {
        return settled;
    }
}
