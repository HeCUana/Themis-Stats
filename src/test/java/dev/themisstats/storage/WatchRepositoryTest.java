package dev.themisstats.storage;

import dev.themisstats.identity.Identity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests of the run alert storage.
 *
 * <p>These tests run against a real SQLite database with the real schema, because the behaviour
 * under test is SQL behaviour: the upsert of a subscription, the cooldown state and the observation
 * window.</p>
 */
class WatchRepositoryTest {

    private static Connection connection;
    private static WatchRepository repository;

    private static final UUID TARGET = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID SUBSCRIBER = UUID.fromString("00000000-0000-0000-0000-0000000000b0");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-0000000000c0");

    @BeforeAll
    static void setUpDatabase() throws SQLException {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement statement = connection.createStatement()) {
            for (String migration : Schema.migrations()) {
                statement.executeUpdate(migration);
            }
        }
        repository = new WatchRepository(connection);
    }

    @BeforeEach
    void clear() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM watch_subscription");
            statement.executeUpdate("DELETE FROM alert_state");
            statement.executeUpdate("DELETE FROM alert_log");
            statement.executeUpdate("DELETE FROM stall_window");
            statement.executeUpdate("DELETE FROM summary_pref");
            statement.executeUpdate("DELETE FROM import_log");
        }
    }

    @Test
    void aSubscriptionIsStoredAndFoundAgain() {
        repository.subscribe(new WatchRepository.Subscription(TARGET, Identity.BOT, SUBSCRIBER,
                1 | 8, 6.0D, 10.0D, "mining", 1000L));
        WatchRepository.Subscription stored = repository.subscription(TARGET, Identity.BOT, SUBSCRIBER);
        assertNotNull(stored);
        assertEquals(TARGET, stored.target());
        assertEquals(Identity.BOT, stored.identity());
        assertEquals(1 | 8, stored.alerts());
        assertEquals("mining", stored.stallMetric());
        assertEquals(1, repository.subscriptionsOf(TARGET, Identity.BOT).size());
        assertEquals(1, repository.subscriptionsOfSubscriber(SUBSCRIBER).size());
    }

    @Test
    void subscribingTwiceUpdatesInsteadOfDuplicating() {
        repository.subscribe(new WatchRepository.Subscription(TARGET, Identity.BOT, SUBSCRIBER,
                1, 6.0D, 10.0D, null, 1000L));
        repository.subscribe(new WatchRepository.Subscription(TARGET, Identity.BOT, SUBSCRIBER,
                2, 3.0D, 20.0D, "kills", 2000L));
        assertEquals(1, repository.allSubscriptions().size());
        WatchRepository.Subscription stored = repository.subscription(TARGET, Identity.BOT, SUBSCRIBER);
        assertNotNull(stored);
        assertEquals(2, stored.alerts());
        assertEquals(3.0D, stored.lowHealth());
        assertEquals(20.0D, stored.lowDurability());
        assertEquals("kills", stored.stallMetric());
    }

    @Test
    void unsubscribingRemovesTheSubscription() {
        repository.subscribe(new WatchRepository.Subscription(TARGET, Identity.BOT, SUBSCRIBER,
                1, 6.0D, 10.0D, null, 1000L));
        repository.unsubscribe(TARGET, Identity.BOT, SUBSCRIBER);
        assertNull(repository.subscription(TARGET, Identity.BOT, SUBSCRIBER));
        assertTrue(repository.allSubscriptions().isEmpty());
    }

    @Test
    void theSameNameInAnotherIdentityIsAnotherSubscription() {
        repository.subscribe(new WatchRepository.Subscription(TARGET, Identity.BOT, SUBSCRIBER,
                1, 6.0D, 10.0D, null, 1000L));
        repository.subscribe(new WatchRepository.Subscription(TARGET, Identity.SHADOW, SUBSCRIBER,
                1, 6.0D, 10.0D, null, 1000L));
        assertEquals(2, repository.allSubscriptions().size());
        assertEquals(1, repository.subscriptionsOf(TARGET, Identity.BOT).size());
        assertEquals(1, repository.subscriptionsOf(TARGET, Identity.SHADOW).size());
    }

    @Test
    void theAlertStateRemembersTheLastNotice() {
        assertNull(repository.alertState(TARGET, Identity.BOT, "low_health"));
        repository.markFired(TARGET, Identity.BOT, "low_health", 5000L);
        WatchRepository.AlertState state = repository.alertState(TARGET, Identity.BOT, "low_health");
        assertNotNull(state);
        assertTrue(state.active());
        assertEquals(5000L, state.lastFiredWall());
        assertNull(state.lastRecoveredWall());
    }

    @Test
    void theRecoveryClearsTheActiveFlagAndKeepsTheTimes() {
        repository.markFired(TARGET, Identity.BOT, "low_health", 5000L);
        repository.markRecovered(TARGET, Identity.BOT, "low_health", 9000L);
        WatchRepository.AlertState state = repository.alertState(TARGET, Identity.BOT, "low_health");
        assertNotNull(state);
        assertFalse(state.active());
        assertEquals(5000L, state.lastFiredWall());
        assertEquals(9000L, state.lastRecoveredWall());
    }

    @Test
    void theObservationWindowIsStoredPerMetric() {
        repository.saveStallWindow(new WatchRepository.StallWindow(TARGET, Identity.BOT, "mining", 1000L, 42L));
        WatchRepository.StallWindow window = repository.stallWindow(TARGET, Identity.BOT, "mining");
        assertNotNull(window);
        assertEquals(1000L, window.windowStartWall());
        assertEquals(42L, window.lastValue());
        assertNull(repository.stallWindow(TARGET, Identity.BOT, "kills"));
        // A second save updates the window instead of adding another one.
        repository.saveStallWindow(new WatchRepository.StallWindow(TARGET, Identity.BOT, "mining", 2000L, 42L));
        assertEquals(2000L, repository.stallWindow(TARGET, Identity.BOT, "mining").windowStartWall());
    }

    @Test
    void theSummaryPreferenceIsStoredPerPlayer() {
        assertFalse(repository.summaryEnabled(SUBSCRIBER));
        repository.saveSummaryEnabled(SUBSCRIBER, true);
        assertTrue(repository.summaryEnabled(SUBSCRIBER));
        assertEquals(List.of(SUBSCRIBER), repository.summarySubscribers());
        repository.saveSummaryEnabled(SUBSCRIBER, false);
        assertFalse(repository.summaryEnabled(SUBSCRIBER));
        assertTrue(repository.summarySubscribers().isEmpty());
    }

    @Test
    void theAlertLogKeepsEveryNotice() {
        repository.logAlert(TARGET, Identity.BOT, "low_health", SUBSCRIBER, "第一条", 1000L);
        repository.logAlert(TARGET, Identity.BOT, "low_health_recovered", SUBSCRIBER, "第二条", 2000L);
        List<String> entries = repository.recentAlerts(10);
        assertEquals(2, entries.size());
        // The newest entry comes first.
        assertTrue(entries.get(0).contains("第二条"));
        // Every notice is counted under its own reason.
        assertEquals(1L, repository.statistics().get("low_health"));
        assertEquals(1L, repository.statistics().get("low_health_recovered"));
    }

    @Test
    void theImportLogKeepsWhatWasImported() {
        repository.logImport("import.csv", 5, 2, "admin", 1000L);
        List<String> entries = repository.importLog();
        assertEquals(1, entries.size());
        assertTrue(entries.get(0).contains("导入 5"));
        assertTrue(entries.get(0).contains("跳过 2"));
    }

    @Test
    void twoSubscribersOfTheSameObjectAreBothStored() {
        repository.subscribe(new WatchRepository.Subscription(TARGET, Identity.BOT, SUBSCRIBER,
                1, 6.0D, 10.0D, null, 1000L));
        repository.subscribe(new WatchRepository.Subscription(TARGET, Identity.BOT, OTHER,
                1, 6.0D, 10.0D, null, 1000L));
        assertEquals(2, repository.subscriptionsOf(TARGET, Identity.BOT).size());
    }
}
