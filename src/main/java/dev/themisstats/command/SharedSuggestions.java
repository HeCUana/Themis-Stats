package dev.themisstats.command;

import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.themisstats.identity.Identity;
import dev.themisstats.metric.Metric;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * The command completions.
 *
 * <p>The completions never offer anything the player is not allowed to use, and they are computed
 * from the same lists the command itself validates against.</p>
 */
public final class SharedSuggestions {

    private SharedSuggestions() {
    }

    public static CompletableFuture<Suggestions> suggestMetrics(SuggestionsBuilder builder) {
        for (Metric metric : Metric.values()) {
            builder.suggest(metric.id());
        }
        return builder.buildFuture();
    }

    public static CompletableFuture<Suggestions> suggestIdentities(SuggestionsBuilder builder) {
        for (Identity identity : Identity.values()) {
            if (identity != Identity.UNKNOWN) {
                builder.suggest(identity.id());
            }
        }
        return builder.buildFuture();
    }

    public static CompletableFuture<Suggestions> suggestPurposes(SuggestionsBuilder builder) {
        for (String purpose : new String[]{"loader", "mining", "combat", "use", "custom"}) {
            builder.suggest(purpose);
        }
        return builder.buildFuture();
    }

    public static CompletableFuture<Suggestions> suggestFormats(SuggestionsBuilder builder) {
        builder.suggest("csv");
        builder.suggest("json");
        return builder.buildFuture();
    }

    public static CompletableFuture<Suggestions> suggestOperations(SuggestionsBuilder builder) {
        builder.suggest("+");
        builder.suggest("-");
        builder.suggest("=");
        return builder.buildFuture();
    }

    public static CompletableFuture<Suggestions> suggestTagOperations(SuggestionsBuilder builder) {
        builder.suggest("add");
        builder.suggest("remove");
        return builder.buildFuture();
    }

    /** Normalises a user input for a case insensitive comparison. */
    public static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
