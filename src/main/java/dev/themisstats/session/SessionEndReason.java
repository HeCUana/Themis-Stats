package dev.themisstats.session;

/**
 * How a session ended.
 *
 * <p>The distinction matters: a bot that died, a bot that was removed with {@code /player} and a
 * bot that simply went offline must not be reported as the same thing. When the cause cannot be
 * determined, {@link #UNKNOWN} is used - the mod never guesses that an administrator did
 * something it cannot observe.</p>
 */
public enum SessionEndReason {

    /** The client disconnected normally. */
    NORMAL_DISCONNECT("normal_disconnect", "正常下线"),

    /** The object died and therefore went offline. */
    DEATH("death", "死亡后离线"),

    /** The object was removed by an operator. */
    ADMIN_REMOVAL("admin_removal", "管理员移除"),

    /** The server shut down normally. */
    SERVER_SHUTDOWN("server_shutdown", "正常停服"),

    /** The server process ended without a clean shutdown. */
    ABNORMAL_TERMINATION("abnormal_termination", "异常中断"),

    /** The cause could not be determined. */
    UNKNOWN("unknown", "原因未知"),

    /** The session was settled because the object's identity changed. */
    IDENTITY_CHANGED("identity_changed", "身份切换");

    private final String id;
    private final String displayName;

    SessionEndReason(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public static SessionEndReason byId(String id) {
        if (id != null) {
            for (SessionEndReason reason : values()) {
                if (reason.id.equalsIgnoreCase(id)) {
                    return reason;
                }
            }
        }
        return UNKNOWN;
    }
}
