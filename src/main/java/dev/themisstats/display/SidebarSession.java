package dev.themisstats.display;

/**
 * The per client state of the personal sidebar.
 *
 * <p><b>Why this state exists.</b> The client accepts the create packet of an objective
 * <em>once</em>. A second create packet for the same name makes the client throw
 * {@code IllegalArgumentException: An objective with the name 'themisstats_sidebar' already
 * exists!} and disconnect. A 26.3 client does exactly that, so the objective may be created
 * <strong>once per client</strong> and the later content updates may only touch the display slot
 * and the score entries.</p>
 *
 * <p>Older clients (1.21.11) silently replaced the objective, so the repeated create packet was
 * invisible there - but it was sent all the same.</p>
 */
final class SidebarSession {

    /** Whether the create packet was already sent to this client. */
    private boolean objectiveCreated;

    /** Whether the display slot was already pointed at our objective. */
    private boolean displayed;

    /** The signature of the content that was sent last. */
    private String lastSignature = "";

    /**
     * @return whether the create packet still has to be sent
     */
    boolean needsObjectiveCreate() {
        return !objectiveCreated;
    }

    /** Remembers that the create packet went out. */
    void markObjectiveCreated() {
        this.objectiveCreated = true;
    }

    /**
     * @return whether the display slot still has to be pointed at our objective
     */
    boolean needsDisplay() {
        return !displayed;
    }

    /** Remembers that the display slot points at our objective. */
    void markDisplayed() {
        this.displayed = true;
    }

    /**
     * @param signature the signature of the content that is about to be sent
     * @return whether the content differs from what was sent last
     */
    boolean contentChanged(String signature) {
        return !signature.equals(lastSignature);
    }

    /** Remembers the signature of the content that was sent. */
    void rememberSignature(String signature) {
        this.lastSignature = signature;
    }

    /**
     * @return the signature of the content that was sent last
     */
    String lastSignature() {
        return lastSignature;
    }
}
