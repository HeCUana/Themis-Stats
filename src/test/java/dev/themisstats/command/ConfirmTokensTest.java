package dev.themisstats.command;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests of the confirmation tokens of the administrative changes.
 *
 * <p>A token has to be bound to the operator, to the parameters and to an expiry, and it must not
 * be usable twice.</p>
 */
class ConfirmTokensTest {

    private static final UUID OPERATOR = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-0000000000b0");

    @Test
    void aTokenCanBeConsumedOnce() {
        ConfirmTokens tokens = new ConfirmTokens();
        var token = tokens.issue(OPERATOR, "数据修正", Map.of("target", "x"));
        assertNotNull(tokens.consume(token.id(), OPERATOR));
        assertNull(tokens.consume(token.id(), OPERATOR), "a token must not be usable twice");
    }

    @Test
    void aTokenOfAnotherOperatorIsRejected() {
        ConfirmTokens tokens = new ConfirmTokens();
        var token = tokens.issue(OPERATOR, "数据修正", Map.of("target", "x"));
        assertNull(tokens.consume(token.id(), OTHER), "a token must not work for another operator");
        // The rejected attempt must not have consumed the token either.
        assertNotNull(tokens.consume(token.id(), OPERATOR));
    }

    @Test
    void theParametersAreBoundToTheToken() {
        ConfirmTokens tokens = new ConfirmTokens();
        var token = tokens.issue(OPERATOR, "数据修正", Map.of("metric", "mining", "value", "5"));
        var consumed = tokens.consume(token.id(), OPERATOR);
        assertNotNull(consumed);
        assertEquals("mining", consumed.parameters().get("metric"));
        assertEquals("5", consumed.parameters().get("value"));
    }

    @Test
    void anExpiredTokenIsRejected() throws Exception {
        ConfirmTokens tokens = new ConfirmTokens();
        var token = tokens.issue(OPERATOR, "数据修正", Map.of());
        // The lifetime is 60 seconds, so the token is still valid here.
        assertTrue(token.expiresAtMillis() > System.currentTimeMillis());
        // A token that is already expired must be rejected.
        ConfirmTokens.Token expired = new ConfirmTokens.Token(token.id(), OPERATOR, "x", Map.of(),
                System.currentTimeMillis() - 1L);
        assertTrue(expired.expiresAtMillis() < System.currentTimeMillis());
    }

    @Test
    void anUnknownTokenIsRejected() {
        ConfirmTokens tokens = new ConfirmTokens();
        assertNull(tokens.consume("does-not-exist", OPERATOR));
    }
}
