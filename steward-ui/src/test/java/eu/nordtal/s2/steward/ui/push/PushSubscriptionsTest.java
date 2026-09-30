package eu.nordtal.s2.steward.ui.push;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import eu.nordtal.s2.common.id.DiscordId;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/** {@code steward_push_subscription}, against a real PostgreSQL. */
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
    void subscribingCreatesARow() {
        subscriptions.subscribe(DiscordId.of("42"), "https://push.example/ep-1", "p256dh-1", "auth-1");

        final List<PushSubscriptions.Subscription> mine = subscriptions.of(DiscordId.of("42"));
        assertEquals(1, mine.size(), "subscribing did not create a row for this account");
        assertEquals("https://push.example/ep-1", mine.get(0).endpoint());
        assertEquals("p256dh-1", mine.get(0).p256dh());
        assertEquals("auth-1", mine.get(0).auth());

        assertTrue(
                subscriptions.all().stream().anyMatch(row -> row.endpoint().equals("https://push.example/ep-1")),
                "the new subscription is not in all() either");
    }

    @Test
    void resubscribingReplacesNotDuplicates() {
        subscriptions.subscribe(DiscordId.of("43"), "https://push.example/ep-2", "old-p256dh", "old-auth");
        subscriptions.subscribe(DiscordId.of("43"), "https://push.example/ep-2", "new-p256dh", "new-auth");

        final List<PushSubscriptions.Subscription> mine = subscriptions.of(DiscordId.of("43"));
        assertEquals(1, mine.size(), "the same endpoint subscribing twice produced two rows");
        assertEquals("new-p256dh", mine.get(0).p256dh());
        assertEquals("new-auth", mine.get(0).auth());
    }

    @Test
    void unsubscribeIsScopedToTheAccount() {
        subscriptions.subscribe(DiscordId.of("44"), "https://push.example/ep-3", "p", "a");

        assertFalse(
                subscriptions.unsubscribe(DiscordId.of("someone-else"), "https://push.example/ep-3"),
                "a different account was able to remove somebody else's subscription");
        assertTrue(subscriptions.unsubscribe(DiscordId.of("44"), "https://push.example/ep-3"));
        assertTrue(subscriptions.of(DiscordId.of("44")).isEmpty());
    }

    @Test
    void theDeviceNameIsKept() {
        subscriptions.subscribe(
                DiscordId.of("46"),
                "https://push.example/ep-5",
                "p",
                "a",
                "Mozilla/5.0 (iPhone; CPU iPhone OS 18_6 like Mac OS X) AppleWebKit/605.1.15"
                        + " (KHTML, like Gecko) Version/18.6 Mobile/15E148 Safari/604.1");
        assertEquals(
                "iPhone, Safari",
                subscriptions.of(DiscordId.of("46")).get(0).device(),
                "the endpoint is not a name - see Devices for where one comes from");

        // A second call without a User-Agent must not blank the name it already has.
        subscriptions.subscribe(DiscordId.of("46"), "https://push.example/ep-5", "p", "a", null);
        assertEquals(
                "iPhone, Safari",
                subscriptions.of(DiscordId.of("46")).get(0).device(),
                "a resubscription without a User-Agent erased the name");
    }

    @Test
    void findIsScopedToTheAccount() {
        subscriptions.subscribe(DiscordId.of("47"), "https://push.example/ep-6", "p", "a");

        assertNull(
                subscriptions.find(DiscordId.of("someone-else"), "https://push.example/ep-6"),
                "a different account could look up somebody else's subscription by endpoint");
        assertNotNull(subscriptions.find(DiscordId.of("47"), "https://push.example/ep-6"));
    }

    @Test
    void expiredRemovesRegardlessOfAccount() {
        subscriptions.subscribe(DiscordId.of("45"), "https://push.example/ep-4", "p", "a");

        subscriptions.expired("https://push.example/ep-4");

        assertTrue(subscriptions.of(DiscordId.of("45")).isEmpty());
    }
}
