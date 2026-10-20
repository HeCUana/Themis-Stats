package dev.themisstats.identity;

/**
 * The identity an action is attributed to.
 *
 * <p>Identity is fixed <em>at the moment the behaviour happens</em>. Switching identity (a human
 * logging in again while their Shadow still runs, for example) never rewrites history: the old
 * segment is settled and a new one begins.</p>
 */
public enum Identity {

    /** A real client connection. */
    HUMAN("human", "真人", true),

    /** A Carpet fake player. */
    BOT("bot", "假人", true),

    /** A Carpet shadow - the server side stand-in of a real player. */
    SHADOW("shadow", "Shadow", true),

    /**
     * An object that could not be classified with certainty. Unknown objects never enter a public
     * board and are never silently guessed to be human.
     */
    UNKNOWN("unknown", "未知", false);

    private final String id;
    private final String displayName;
    private final boolean countable;

    Identity(String id, String displayName, boolean countable) {
        this.id = id;
        this.displayName = displayName;
        this.countable = countable;
    }

    /** @return the stable machine id, used in the database and in commands */
    public String id() {
        return id;
    }

    /** @return the Chinese display name */
    public String displayName() {
        return displayName;
    }

    /** @return whether objects of this identity take part in public boards and public totals */
    public boolean countable() {
        return countable;
    }

    /** @return whether this identity is a bot-like (non human client) identity */
    public boolean botLike() {
        return this == BOT || this == SHADOW;
    }

    public static Identity byId(String id) {
        if (id == null) {
            return UNKNOWN;
        }
        for (Identity identity : values()) {
            if (identity.id.equalsIgnoreCase(id)) {
                return identity;
            }
        }
        return UNKNOWN;
    }
}
