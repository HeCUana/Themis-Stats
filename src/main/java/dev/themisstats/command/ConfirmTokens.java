package dev.themisstats.command;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The confirmation tokens of the administrative commands.
 *
 * <p>A token is bound to the operator, to the exact parameters of the pending change and to an
 * expiry. It can be used only once, and the parameters of a pending change cannot be modified
 * after the preview was shown - a changed parameter needs a new preview and a new token.</p>
 */
public final class ConfirmTokens {

    /** A pending change that waits for its confirmation. */
    public record Token(String id, UUID operator, String summary, Map<String, String> parameters,
                        long expiresAtMillis) {
    }

    /** How long a token stays valid. */
    public static final long LIFETIME_MILLIS = 60_000L;

    private final Map<String, Token> tokens = new ConcurrentHashMap<>();

    /**
     * Issues a token for a pending change.
     */
    public Token issue(UUID operator, String summary, Map<String, String> parameters) {
        purge();
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Token token = new Token(id, operator, summary, Map.copyOf(parameters),
                System.currentTimeMillis() + LIFETIME_MILLIS);
        tokens.put(id, token);
        return token;
    }

    /**
     * Consumes a token.
     *
     * @return the token, or {@code null} when it is unknown, expired, already used or belongs to
     *         another operator
     */
    public Token consume(String id, UUID operator) {
        purge();
        Token token = tokens.get(id);
        if (token == null) {
            return null;
        }
        if (token.expiresAtMillis() < System.currentTimeMillis()) {
            tokens.remove(id, token);
            return null;
        }
        if (operator != null && !operator.equals(token.operator())) {
            // A token that belongs to somebody else is rejected, but it is NOT consumed: the
            // rightful operator still has to be able to use it.
            return null;
        }
        tokens.remove(id, token);
        return token;
    }

    /** @return the number of tokens that are still waiting */
    public int pending() {
        purge();
        return tokens.size();
    }

    private void purge() {
        long now = System.currentTimeMillis();
        tokens.values().removeIf(token -> token.expiresAtMillis() < now);
    }
}
