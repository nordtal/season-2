package eu.nordtal.s2.steward.ui.push;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * {@code steward_push_subscription}, against a real PostgreSQL - modelled on
 * {@code SessionRowsTest}, the sibling this is closest to.
 *
 * <p>Acceptance assertion 1 of steward/98: "a subscription is created". A subscription only
 * exists once a row does; nothing here fakes the table.</p>
 */
class PushSubscriptionsTest {

    private static PostgreSQLContainer<?> postgres;
    private static PushSubscriptions subscriptions;

    @BeforeAll
    static void start() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "no docker daemon - skipping");
        postgres = new PostgreSQLContainer<>("postgres:17-alpine");
        postgres.start();
        Flyway.configure(PushSubscriptionsTest.class.getClassLoader())
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        final PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(postgres.getJdbcUrl());
        source.setUser(postgres.getUsername());
        source.setPassword(postgres.getPassword());
        subscriptions = new PushSubscriptions(source);
    }

    @AfterAll
    static void stop() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    @DisplayName("subscribing writes a row that all() and of() then both see")
    void subscribingCreatesARow() {
        subscriptions.subscribe("42", "https://push.example/ep-1", "p256dh-1", "auth-1");

        final List<PushSubscriptions.Subscription> mine = subscriptions.of("42");
        assertEquals(1, mine.size(), "subscribing did not create a row for this account");
        assertEquals("https://push.example/ep-1", mine.get(0).endpoint());
        assertEquals("p256dh-1", mine.get(0).p256dh());
        assertEquals("auth-1", mine.get(0).auth());

        assertTrue(
                subscriptions.all().stream().anyMatch(row -> row.endpoint().equals("https://push.example/ep-1")),
                "the new subscription is not in all() either");
    }

    @Test
    @DisplayName("subscribing again with the same endpoint refreshes the row instead of doubling it")
    void resubscribingReplacesNotDuplicates() {
        subscriptions.subscribe("43", "https://push.example/ep-2", "old-p256dh", "old-auth");
        subscriptions.subscribe("43", "https://push.example/ep-2", "new-p256dh", "new-auth");

        final List<PushSubscriptions.Subscription> mine = subscriptions.of("43");
        assertEquals(1, mine.size(), "the same endpoint subscribing twice produced two rows");
        assertEquals("new-p256dh", mine.get(0).p256dh());
        assertEquals("new-auth", mine.get(0).auth());
    }

    @Test
    @DisplayName("unsubscribing removes only the caller's own subscription")
    void unsubscribeIsScopedToTheAccount() {
        subscriptions.subscribe("44", "https://push.example/ep-3", "p", "a");

        assertFalse(
                subscriptions.unsubscribe("someone-else", "https://push.example/ep-3"),
                "a different account was able to remove somebody else's subscription");
        assertTrue(subscriptions.unsubscribe("44", "https://push.example/ep-3"));
        assertTrue(subscriptions.of("44").isEmpty());
    }

    @Test
    @DisplayName("the device name is derived from the User-Agent and survives a resubscription")
    void theDeviceNameIsKept() {
        subscriptions.subscribe(
                "46",
                "https://push.example/ep-5",
                "p",
                "a",
                "Mozilla/5.0 (iPhone; CPU iPhone OS 18_6 like Mac OS X) AppleWebKit/605.1.15"
                        + " (KHTML, like Gecko) Version/18.6 Mobile/15E148 Safari/604.1");
        assertEquals(
                "iPhone, Safari",
                subscriptions.of("46").get(0).device(),
                "the endpoint is not a name - see Devices for where one comes from");

        // A page reloaded twice in the same tab resubscribes with the same endpoint. A browser that
        // sends no User-Agent at all on that second call must not blank the name it already has.
        subscriptions.subscribe("46", "https://push.example/ep-5", "p", "a", null);
        assertEquals(
                "iPhone, Safari",
                subscriptions.of("46").get(0).device(),
                "a resubscription without a User-Agent erased the name");
    }

    @Test
    @DisplayName("find() answers only within the account, so a test send cannot be aimed elsewhere")
    void findIsScopedToTheAccount() {
        subscriptions.subscribe("47", "https://push.example/ep-6", "p", "a");

        assertNull(
                subscriptions.find("someone-else", "https://push.example/ep-6"),
                "a different account could look up somebody else's subscription by endpoint");
        assertNotNull(subscriptions.find("47", "https://push.example/ep-6"));
    }

    @Test
    @DisplayName("expired() removes a subscription outright, with no account check")
    void expiredRemovesRegardlessOfAccount() {
        subscriptions.subscribe("45", "https://push.example/ep-4", "p", "a");

        subscriptions.expired("https://push.example/ep-4");

        assertTrue(subscriptions.of("45").isEmpty());
    }
}
