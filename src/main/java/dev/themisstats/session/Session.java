package dev.themisstats.session;

import dev.themisstats.identity.Identity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One live session: a single actually-online connection of a single object under a single identity.
 *
 * <p>Durations are measured with a monotonic clock ({@link System#nanoTime()}) so that a wall clock
 * correction can never produce a negative or inflated session length. Wall clock time is only used
 * for display.</p>
 *
 * <p>Two durations are tracked separately, as the specification requires:</p>
 * <ul>
 *   <li><b>actual online time</b> - how long the connection existed.</li>
 *   <li><b>collected time</b> - how long the collection of metrics was actually enabled. Only this
 *       one feeds the {@code online} metric.</li>
 * </ul>
 */
public final class Session {

    private final String id;
    private final UUID accountUuid;
    private final Identity identity;
    private final String bootId;
    private final long startWallMillis;
    private final long startNanos;
    private final String initialDimension;
    private final double initialX;
    private final double initialY;
    private final double initialZ;

    private long lastNano;
    private long collectedNanos;
    private long onlineNanos;
    private boolean collecting;
    private boolean settled;
    private Long pauseStartNano;
    private final List<long[]> pauses = new ArrayList<>();

    // last known position
    private String lastDimension;
    private double lastX;
    private double lastY;
    private double lastZ;

    public Session(String id, UUID accountUuid, Identity identity, String bootId, long startWallMillis,
                   long startNanos, String initialDimension, double initialX, double initialY, double initialZ,
                   boolean collecting) {
        this.id = id;
        this.accountUuid = accountUuid;
        this.identity = identity;
        this.bootId = bootId;
        this.startWallMillis = startWallMillis;
        this.startNanos = startNanos;
        this.initialDimension = initialDimension;
        this.initialX = initialX;
        this.initialY = initialY;
        this.initialZ = initialZ;
        this.lastDimension = initialDimension;
        this.lastX = initialX;
        this.lastY = initialY;
        this.lastZ = initialZ;
        this.lastNano = startNanos;
        this.collecting = collecting;
    }

    public String id() {
        return id;
    }

    public UUID accountUuid() {
        return accountUuid;
    }

    public Identity identity() {
        return identity;
    }

    public String bootId() {
        return bootId;
    }

    public long startWallMillis() {
        return startWallMillis;
    }

    public long startNanos() {
        return startNanos;
    }

    public String initialDimension() {
        return initialDimension;
    }

    public double initialX() {
        return initialX;
    }

    public double initialY() {
        return initialY;
    }

    public double initialZ() {
        return initialZ;
    }

    public String lastDimension() {
        return lastDimension;
    }

    public double lastX() {
        return lastX;
    }

    public double lastY() {
        return lastY;
    }

    public double lastZ() {
        return lastZ;
    }

    public void updateLastKnown(String dimension, double x, double y, double z) {
        this.lastDimension = dimension;
        this.lastX = x;
        this.lastY = y;
        this.lastZ = z;
    }

    /** @return whether the session has already been settled */
    public boolean isSettled() {
        return settled;
    }

    /** @return whether metrics are currently being collected for this session */
    public boolean isCollecting() {
        return collecting;
    }

    /**
     * Advances the monotonic accounting to {@code nowNanos}.
     *
     * <p>Must be called before toggling {@link #setCollecting(boolean)} so that the time spent in
     * the previous state is attributed to that state.</p>
     */
    public void advanceTo(long nowNanos) {
        long delta = nowNanos - lastNano;
        if (delta <= 0) {
            return;
        }
        lastNano = nowNanos;
        onlineNanos += delta;
        if (collecting) {
            collectedNanos += delta;
        }
    }

    /**
     * Starts or stops metric collection for this session.
     *
     * @param nowNanos the current monotonic reading
     * @param enabled  whether collection should be running
     * @return {@code true} if the state actually changed
     */
    public boolean setCollecting(long nowNanos, boolean enabled) {
        advanceTo(nowNanos);
        if (collecting == enabled) {
            return false;
        }
        if (enabled) {
            if (pauseStartNano != null) {
                pauses.add(new long[]{pauseStartNano, nowNanos});
                pauseStartNano = null;
            }
        } else {
            pauseStartNano = nowNanos;
        }
        collecting = enabled;
        return true;
    }

    /**
     * Closes a still open pause interval. Called when the session is settled.
     *
     * @param nowNanos the current monotonic reading
     */
    public void closeOpenPause(long nowNanos) {
        advanceTo(nowNanos);
        if (pauseStartNano != null) {
            pauses.add(new long[]{pauseStartNano, nowNanos});
            pauseStartNano = null;
        }
    }

    /** @return the pause intervals of this session, as monotonic {@code [start, end]} pairs */
    public List<long[]> pauses() {
        return List.copyOf(pauses);
    }

    /** @return the collected time in milliseconds */
    public long collectedMillis() {
        return collectedNanos / 1_000_000L;
    }

    /** @return the actual online time in milliseconds */
    public long onlineMillis() {
        return onlineNanos / 1_000_000L;
    }

    /** @return the collected time in milliseconds, up to the given monotonic reading */
    public long collectedMillisAt(long nowNanos) {
        long nanos = collectedNanos + (collecting ? Math.max(0, nowNanos - lastNano) : 0);
        return nanos / 1_000_000L;
    }

    /** @return the actual online time in milliseconds, up to the given monotonic reading */
    public long onlineMillisAt(long nowNanos) {
        long nanos = onlineNanos + Math.max(0, nowNanos - lastNano);
        return nanos / 1_000_000L;
    }
}
