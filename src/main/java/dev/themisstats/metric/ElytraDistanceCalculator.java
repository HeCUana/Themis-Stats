package dev.themisstats.metric;

/**
 * The elytra glide distance measurement, as a pure state machine.
 *
 * <p>The rules are deliberately separated from the Minecraft objects so that they can be tested
 * without a running server:</p>
 * <ul>
 *   <li>a change of dimension restarts the measurement and never counts;</li>
 *   <li>a displacement of more than {@link #MAX_TICK_DISPLACEMENT} blocks in one tick is treated
 *       as a teleport or a loading jump and is not counted;</li>
 *   <li>the tick on which the player starts gliding and the tick on which the player stops
 *       gliding are not counted, so neither the launch nor the landing adds distance.</li>
 * </ul>
 */
public final class ElytraDistanceCalculator {

    /** A single tick displacement above this many blocks is treated as a teleport. */
    public static final double MAX_TICK_DISPLACEMENT = 20.0D;

    private String dimension;
    private double x;
    private double y;
    private double z;
    private boolean gliding;
    private boolean initialised;
    private long totalMilliBlocks;

    /**
     * Feeds one tick of movement into the calculator.
     *
     * @param dimension the dimension the object is in
     * @param x         the current x position
     * @param y         the current y position
     * @param z         the current z position
     * @param gliding   whether the object is gliding in this tick
     * @return the distance of this tick in thousandths of a block, or {@code 0} when the tick does
     *         not count
     */
    public long tick(String dimension, double x, double y, double z, boolean gliding) {
        long result = 0L;
        if (!initialised || !dimension.equals(this.dimension)) {
            // The first tick and every dimension change restart the measurement.
            this.dimension = dimension;
        } else if (gliding && this.gliding) {
            double dx = x - this.x;
            double dy = y - this.y;
            double dz = z - this.z;
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (distance <= MAX_TICK_DISPLACEMENT) {
                result = Math.round(distance * 1000.0D);
                this.totalMilliBlocks += result;
            }
            // A displacement above the threshold is a teleport: it is discarded, and the
            // measurement continues from the new position.
        }
        this.x = x;
        this.y = y;
        this.z = z;
        this.gliding = gliding;
        this.initialised = true;
        return result;
    }

    /** @return the accumulated distance in thousandths of a block */
    public long total() {
        return totalMilliBlocks;
    }

    /**
     * Resets the calculator, for example when the object goes offline.
     */
    public void reset() {
        initialised = false;
        gliding = false;
        totalMilliBlocks = 0L;
    }
}
