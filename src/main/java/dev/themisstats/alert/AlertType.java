package dev.themisstats.alert;

/**
 * The kinds of run alerts.
 *
 * <p>The bit mask of the kinds is what a subscription stores, so a subscriber can pick exactly
 * the alerts it is interested in.</p>
 */
import java.util.ArrayList;
import java.util.List;

public enum AlertType {

    /** The confirmed health of the object is at or below the threshold. */
    LOW_HEALTH("low_health", "低生命值", 1),

    /** The durability left in the main hand tool is at or below the threshold. */
    LOW_DURABILITY("low_durability", "工具低耐久", 2),

    /** A watched object went offline for a reason that is not a normal shutdown. */
    UNEXPECTED_OFFLINE("unexpected_offline", "意外离线", 4),

    /** The watched statistic did not grow inside the observation window. */
    METRIC_STALL("metric_stall", "指定统计停滞", 8),

    /** The statistics could not be saved. This one goes to the administrators. */
    SAVE_FAILURE("save_failure", "保存异常", 16);

    public final String id;
    private final String displayName;
    public final int bit;

    AlertType(String id, String displayName, int bit) {
        this.id = id;
        this.displayName = displayName;
        this.bit = bit;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public int bit() {
        return bit;
    }

    /** @return the mask of all kinds */
    public static int allMask() {
        int mask = 0;
        for (AlertType type : values()) {
            mask |= type.bit;
        }
        return mask;
    }

    /** @return the mask of the kinds whose id is in the list */
    public static int maskOf(List<String> ids) {
        int mask = 0;
        for (String id : ids) {
            for (AlertType type : values()) {
                if (type.id.equalsIgnoreCase(id)) {
                    mask |= type.bit;
                }
            }
        }
        return mask;
    }

    /** @return the kind with the given id, or {@code null} */
    public static AlertType byId(String id) {
        if (id == null) {
            return null;
        }
        for (AlertType type : values()) {
            if (type.id.equalsIgnoreCase(id)) {
                return type;
            }
        }
        return null;
    }

    /** @return the names of the kinds in the mask */
    public static List<String> namesIn(int mask) {
        List<String> result = new ArrayList<>();
        for (AlertType type : values()) {
            if ((mask & type.bit) != 0) {
                result.add(type.id);
            }
        }
        return result;
    }
}
