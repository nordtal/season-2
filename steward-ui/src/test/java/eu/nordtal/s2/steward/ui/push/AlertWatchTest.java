package eu.nordtal.s2.steward.ui.push;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.flywaydb.core.Flyway;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * {@link AlertWatch}, with the worker and the push protocol both replaced by a fake this test
 * drives - see {@link AlertLevelSource} and {@link PushSender}'s own class notes on why those two
 * seams exist.
 *
 * <p>Covers acceptance assertions 2 and 3 of steward/98: a traffic-light change reaches every
 * subscription as a send, and a send that comes back as a 404/410 (here: {@link
 * PushSender.Result#EXPIRED}) removes that subscription - detected and handled, not just noticed.
 */
class AlertWatchTest {

    private static PostgreSQLContainer<?> postgres;
    private static PGSimpleDataSource dataSource;

    private PushSubscriptions subscriptions;
    private PushPreferences preferences;
    private FakeSource source;
    private FakeSender sender;
    private AlertWatch watch;

    /** The same three numbers steward-ui.yml ships with - see UiSpec.AlertSpec. */
    private static final Alerts.Thresholds THRESHOLDS = new Alerts.Thresholds(85, 90, 36);

    @BeforeAll
    static void startDatabase() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "no docker daemon - skipping");
        postgres = new PostgreSQLContainer<>("postgres:17-alpine");
        postgres.start();
        Flyway.configure(AlertWatchTest.class.getClassLoader())
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        dataSource = new PGSimpleDataSource();
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @BeforeEach
    void freshSubscriptions() {
        // Fresh rows per test: TRUNCATE rather than a new container, which would cost a Postgres
        // start per test rather than per class.
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            statement.execute("TRUNCATE steward_push_subscription");
            statement.execute("TRUNCATE steward_push_preference");
        } catch (final java.sql.SQLException failure) {
            throw new RuntimeException(failure);
        }
        subscriptions = new PushSubscriptions(dataSource);
        subscriptions.subscribe("42", "https://push.example/a", "p-a", "a-a");
        subscriptions.subscribe("43", "https://push.example/b", "p-b", "a-b");
        preferences = new PushPreferences(dataSource);
        source = new FakeSource();
        sender = new FakeSender();
        watch = new AlertWatch(source, subscriptions, preferences, sender, THRESHOLDS);
    }

    @Test
    @DisplayName("a traffic-light change sends to every subscription; an unchanged reading sends to none")
    void trafficLightChangeTriggersASend() {
        source.allClear();
        watch.poll();
        assertEquals(0, sender.sent.size(), "the very first poll has nothing to compare against and" + " sent anyway");

        source.allClear();
        watch.poll();
        assertEquals(0, sender.sent.size(), "an unchanged reading was sent as if it had changed");

        source.next(trigger("service", "down", "smp", "/services/smp"));
        watch.poll();
        assertEquals(2, sender.sent.size(), "a traffic-light change did not reach both subscriptions");
        assertTrue(sender.sent.stream().anyMatch(call -> call.endpoint.equals("https://push.example/a")));
        assertTrue(sender.sent.stream().anyMatch(call -> call.endpoint.equals("https://push.example/b")));
        assertTrue(
                sender.sent.getFirst().payload.contains("down"),
                "the payload does not name the new level: " + sender.sent.getFirst().payload);
        assertTrue(
                sender.sent.getFirst().payload.contains("smp"),
                "the payload does not name the subject: " + sender.sent.getFirst().payload);
    }

    @Test
    @DisplayName("a 404/410 (EXPIRED) removes that subscription; the other one stays")
    void expiredSubscriptionIsRemoved() {
        sender.expire("https://push.example/a");

        source.allClear();
        watch.poll();
        source.next(trigger("backup", "down", "database dump", "/operations"));
        watch.poll();

        final List<String> left = subscriptions.all().stream()
                .map(PushSubscriptions.Subscription::endpoint)
                .toList();
        assertEquals(
                List.of("https://push.example/b"),
                left,
                "the subscription that answered EXPIRED was not removed, or the other one was too");
    }

    @Test
    @DisplayName("a type switched off on one account reaches that account's browsers and no other")
    void aSwitchedOffTypeIsNotSentToThatAccount() {
        preferences.set("42", AlertType.SERVICE, false);

        source.allClear();
        watch.poll();
        source.next(trigger("service", "down", "smp", "/services/smp"));
        watch.poll();

        assertEquals(
                List.of("https://push.example/b"),
                sender.sent.stream().map(call -> call.endpoint).toList(),
                "a service alert reached an account that had switched service notifications off,"
                        + " or missed the account that had not");
    }

    @Test
    @DisplayName("drift is off by default, so nobody is woken by it until somebody asks to be")
    void driftIsOffByDefault() {
        source.allClear();
        watch.poll();
        source.next(trigger("drift", "warn", "caddy", "/operations"));
        watch.poll();

        assertEquals(
                0,
                sender.sent.size(),
                "an image drift was pushed to accounts that never switched drift on - see"
                        + " AlertType's own defaults");
    }

    @Test
    @DisplayName("an account that switched drift on does get it")
    void driftReachesTheAccountThatAskedForIt() {
        preferences.set("43", AlertType.DRIFT, true);

        source.allClear();
        watch.poll();
        source.next(trigger("drift", "warn", "caddy", "/operations"));
        watch.poll();

        assertEquals(
                List.of("https://push.example/b"),
                sender.sent.stream().map(call -> call.endpoint).toList(),
                "the account that switched drift ON did not get it, or the one that did not switch" + " it on did");
    }

    @Test
    @DisplayName("two types moving in one poll are two notifications, not one")
    void everyTypeThatMovedIsItsOwnNotification() {
        source.allClear();
        watch.poll();
        source.next(List.of(
                trigger("service", "down", "smp", "/services/smp"),
                trigger("backup", "down", "backups", "/operations")));
        watch.poll();

        // Two subscriptions, both accounts at their defaults, two types: four sends, and the
        // payloads say which is which. Before steward/98's review the worker answered only the
        // worst trigger, so the backup would have been invisible for as long as smp was down.
        assertEquals(4, sender.sent.size(), "a poll in which two types moved did not send both");
        assertTrue(
                sender.sent.stream().anyMatch(call -> call.payload.contains("\"service\"")),
                "no notification named the service type: " + sender.sent);
        assertTrue(
                sender.sent.stream().anyMatch(call -> call.payload.contains("\"backup\"")),
                "no notification named the backup type: " + sender.sent);
    }

    @Test
    @DisplayName("an all-clear still names what it is clearing")
    void anAllClearNamesWhatCleared() {
        source.allClear();
        watch.poll();
        source.next(trigger("service", "down", "smp", "/services/smp"));
        watch.poll();
        sender.sent.clear();

        source.allClear();
        watch.poll();

        // Till, 2026-09-19: the first line of a notification is the thing and what is up with it.
        // The all-clear used to carry an empty subject, which the service worker can only draw as
        // "Steward is clear" - and the one notification somebody waits for after a service went
        // down is the one saying THAT service is back.
        assertEquals(2, sender.sent.size(), "the all-clear did not reach both subscriptions");
        assertTrue(
                sender.sent.getFirst().payload.contains("\"subject\":\"smp\""),
                "the all-clear forgot what it was clearing: " + sender.sent.getFirst().payload);
        assertTrue(
                sender.sent.getFirst().payload.contains("\"level\":\"ok\""),
                "the all-clear is not an ok: " + sender.sent.getFirst().payload);
    }

    @Test
    @DisplayName("the disk threshold is this service's own, applied to the worker's raw percentage")
    void theDiskThresholdIsAppliedHere() {
        source.next(new AlertReading(List.of(), 10.0, 10.0, 1.0));
        watch.poll();
        source.next(new AlertReading(List.of(), 84.9, 10.0, 1.0));
        watch.poll();
        assertEquals(0, sender.sent.size(), "a disk below the configured 85 % was pushed as if it were over");

        source.next(new AlertReading(List.of(), 85.0, 10.0, 1.0));
        watch.poll();
        assertEquals(
                2,
                sender.sent.size(),
                "a disk AT the configured 85 % was not pushed - health.ts compares with >= and so" + " must this");
        assertTrue(
                sender.sent.getFirst().payload.contains("disk"),
                "the payload is not about the disk: " + sender.sent.getFirst().payload);
    }

    @Test
    @DisplayName("a backup older than the permitted age is the backup type, not a sixth one")
    void anOldBackupIsTheBackupType() {
        source.next(new AlertReading(List.of(), 10.0, 10.0, 1.0));
        watch.poll();
        source.next(new AlertReading(List.of(), 10.0, 10.0, 37.0));
        watch.poll();

        assertEquals(2, sender.sent.size(), "a backup past the permitted age did not push");
        assertTrue(
                sender.sent.getFirst().payload.contains("\"backup\""),
                "an old backup was not sent as the backup type: " + sender.sent.getFirst().payload);
    }

    @Test
    @DisplayName("a measurement the worker could not take raises no alarm")
    void anUnmeasuredNumberRaisesNothing() {
        source.allClear();
        watch.poll();
        // The worker leaves the three numbers out exactly when it could not read them. "Nobody
        // looked" is not "the disk is full", and an unknown backup age is not an infinitely old
        // backup - the missing backup itself is a trigger of its own and says so in words.
        source.next(new AlertReading(List.of(), null, null, null));
        watch.poll();

        assertEquals(0, sender.sent.size(), "a reading with no measurements in it woke somebody up: " + sender.sent);
    }

    private static AlertReading.Trigger trigger(
            final String kind, final String level, final String subject, final String path) {
        return new AlertReading.Trigger(kind, level, subject, path);
    }

    /** A worker that answers whatever {@link #next} queued, in order. */
    private static final class FakeSource implements AlertLevelSource {
        private final Deque<AlertReading> queue = new ArrayDeque<>();

        /** Nothing wrong, and three measurements comfortably under every threshold. */
        void allClear() {
            queue.addLast(new AlertReading(List.of(), 10.0, 10.0, 1.0));
        }

        void next(final AlertReading.Trigger trigger) {
            next(List.of(trigger));
        }

        void next(final List<AlertReading.Trigger> triggers) {
            queue.addLast(new AlertReading(triggers, 10.0, 10.0, 1.0));
        }

        void next(final AlertReading reading) {
            queue.addLast(reading);
        }

        @Override
        public @NotNull AlertReading current() {
            return queue.removeFirst();
        }
    }

    /** A push service that records every call and answers EXPIRED for whichever endpoints asked. */
    private static final class FakeSender implements PushSender {
        record Call(String endpoint, String payload) {}

        private final CopyOnWriteArrayList<Call> sent = new CopyOnWriteArrayList<>();
        private final Map<String, Boolean> expired = new ConcurrentHashMap<>();

        void expire(final String endpoint) {
            expired.put(endpoint, Boolean.TRUE);
        }

        @Override
        public @NotNull Result send(
                final @NotNull PushSubscriptions.Subscription subscription, final @NotNull String payload) {
            sent.add(new Call(subscription.endpoint(), payload));
            return expired.containsKey(subscription.endpoint()) ? Result.EXPIRED : Result.SENT;
        }
    }
}
