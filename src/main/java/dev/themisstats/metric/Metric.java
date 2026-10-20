package dev.themisstats.metric;

/**
 * The eight P0 metrics.
 *
 * <p>Every metric is stored as a 64 bit integer. The unit depends on {@link #unit()}:</p>
 * <ul>
 *   <li>{@link Unit#COUNT} - a number of successful operations.</li>
 *   <li>{@link Unit#MILLISECOND} - elapsed milliseconds of collection-enabled session time.</li>
 *   <li>{@link Unit#MILLI_BLOCK} - elytra glide distance in thousandths of a block, so that the
 *       value stays an exact integer instead of a lossy float.</li>
 * </ul>
 */
public enum Metric {

    ONLINE("online", "统计在线时长", Unit.MILLISECOND, false, false),
    MINING("mining", "挖掘量", Unit.COUNT, true, true),
    PLACEMENT("placement", "放置量", Unit.COUNT, true, true),
    KILLS("kills", "击杀量", Unit.COUNT, true, true),
    DEATHS("deaths", "死亡数", Unit.COUNT, true, false),
    VILLAGER_TRADES("villager_trades", "村民交易", Unit.COUNT, false, false),
    FISHING("fishing", "钓获次数", Unit.COUNT, false, false),
    ELYTRA_DISTANCE("elytra_distance", "鞘翅距离", Unit.MILLI_BLOCK, false, false),

    // ---- P1 增强指标 ----
    DAMAGE_DEALT("damage_dealt", "造成伤害", Unit.DAMAGE, true, true),
    DAMAGE_TAKEN("damage_taken", "受到伤害", Unit.DAMAGE, true, false),
    MOVEMENT("movement", "其他移动", Unit.MILLI_BLOCK, true, false),
    CRAFTED("crafted", "制作物品", Unit.COUNT, true, true),
    PICKED_UP("picked_up", "拾取物品", Unit.COUNT, true, true),
    DROPPED("dropped", "丢弃物品", Unit.COUNT, true, true),
    PIGLIN_BARTERS("piglin_barters", "猪灵交换", Unit.COUNT, false, false);

    /** The physical unit a metric is measured in. */
    public enum Unit {
        COUNT("次"),
        MILLISECOND("毫秒"),
        MILLI_BLOCK("千分之一方块"),
        DAMAGE("点生命值");

        private final String display;

        Unit(String display) {
            this.display = display;
        }

        public String display() {
            return display;
        }
    }

    private final String id;
    private final String displayName;
    private final Unit unit;
    private final boolean hasCategory;
    private final boolean categoryIsIdentifier;

    Metric(String id, String displayName, Unit unit, boolean hasCategory, boolean categoryIsIdentifier) {
        this.id = id;
        this.displayName = displayName;
        this.unit = unit;
        this.hasCategory = hasCategory;
        this.categoryIsIdentifier = categoryIsIdentifier;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public Unit unit() {
        return unit;
    }

    /** @return whether this metric records a per block / per entity / per reason breakdown */
    public boolean hasCategory() {
        return hasCategory;
    }

    /** @return whether this metric is a behaviour metric that can be filtered by game mode */
    public boolean isModeClassified() {
        return this != ONLINE;
    }

    /**
     * @return whether this metric is a P1 enhancement metric
     */
    public boolean isEnhanced() {
        return switch (this) {
            case DAMAGE_DEALT, DAMAGE_TAKEN, MOVEMENT, CRAFTED, PICKED_UP, DROPPED, PIGLIN_BARTERS -> true;
            default -> false;
        };
    }

    /**
     * @return whether this metric can be broken down into a per block / per entity leaderboard
     */
    public boolean supportsFineBoards() {
        return hasCategory;
    }

    /**
     * @return whether the categories of this metric are resource locations
     *
     * <p>The categories of the block, item and entity metrics are the registry ids
     * ({@code minecraft:stone}), while the categories of the death, damage and movement metrics are
     * plain words ({@code mob}, {@code walk}). The difference matters for the commands that filter
     * by a category: a resource location may be written without its namespace.</p>
     */
    public boolean categoryIsIdentifier() {
        return categoryIsIdentifier;
    }

    /**
     * Brings a category the user typed into the form the data is stored in.
     *
     * <p>A resource location may be written without its namespace: {@code stone} becomes
     * {@code minecraft:stone}. A category that already has a namespace and the plain word
     * categories are left alone - the data is never guessed, a wrong guess would silently show an
     * empty leaderboard.</p>
     *
     * @param raw the category the user typed
     * @return the category in the stored form
     */
    public String normalizeCategory(String raw) {
        if (raw == null || raw.isBlank() || !categoryIsIdentifier) {
            return raw;
        }
        if (raw.indexOf(':') >= 0) {
            return raw;
        }
        return "minecraft:" + raw;
    }

    public static Metric byId(String id) {
        if (id == null) {
            return null;
        }
        for (Metric metric : values()) {
            if (metric.id.equalsIgnoreCase(id)) {
                return metric;
            }
        }
        return null;
    }

    /** @return the comma separated list of all metric ids, used for command suggestions */
    public static String idList() {
        StringBuilder sb = new StringBuilder();
        for (Metric metric : values()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(metric.id);
        }
        return sb.toString();
    }
}
