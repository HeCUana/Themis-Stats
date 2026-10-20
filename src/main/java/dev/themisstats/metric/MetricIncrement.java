package dev.themisstats.metric;

import dev.themisstats.identity.Identity;

import java.util.UUID;

/**
 * An immutable single metric increment.
 *
 * <p>Everything that decides <em>who</em> and <em>where</em> an increment belongs is captured here,
 * at the moment the behaviour happened. The asynchronous writer must never re-derive the identity
 * from a currently online object, because the identity may have changed since.</p>
 *
 * @param accountUuid the account the behaviour belongs to
 * @param identity    the identity the behaviour happened under
 * @param sessionId   the id of the session the behaviour happened in
 * @param metric      the metric
 * @param amount      the amount to add, always positive
 * @param category    the block / entity / death-reason breakdown key, or {@code null}
 * @param dimension   the dimension id the behaviour happened in
 * @param gameMode    the game mode the behaviour happened in
 * @param wallClockMillis wall clock time of the behaviour (display purposes)
 */
public record MetricIncrement(
        UUID accountUuid,
        Identity identity,
        String sessionId,
        Metric metric,
        long amount,
        String category,
        String dimension,
        String gameMode,
        long wallClockMillis) {

    public MetricIncrement {
        if (amount <= 0) {
            throw new IllegalArgumentException("metric increment amount must be positive: " + amount);
        }
        if (accountUuid == null || identity == null || metric == null) {
            throw new IllegalArgumentException("metric increment is missing a required part");
        }
    }

    /** @return the category key, never {@code null} (empty string means "no category") */
    public String categoryKey() {
        return category == null ? "" : category;
    }
}
