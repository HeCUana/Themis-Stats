package dev.themisstats;

/**
 * Holds the services of the running server.
 *
 * <p>The mixins call into this holder. It is always safe to use: before the services are installed
 * and after the server closed, the accessors return {@code null} and the mixins do nothing, so a
 * mixin can never crash a server that is starting up or shutting down.</p>
 */
public final class ThemisStatsRuntime {

    private static volatile ThemisStatsServices services;

    private ThemisStatsRuntime() {
    }

    public static void install(ThemisStatsServices installed) {
        services = installed;
    }

    public static void uninstall() {
        services = null;
    }

    /** @return the running services, or {@code null} when the statistics module is not active */
    public static ThemisStatsServices services() {
        return services;
    }

    public static boolean available() {
        return services != null;
    }
}
