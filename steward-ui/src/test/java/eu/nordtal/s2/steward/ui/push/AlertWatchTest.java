package eu.nordtal.s2.steward.ui.push;

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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
    private FakeSource source;
    private FakeSender sender;
    private AlertWatch watch;

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
        } catch (final java.sql.SQLException failure) {
            throw new RuntimeException(failure);
        }
        subscriptions = new PushSubscriptions(dataSource);
        subscriptions.subscribe("42", "https://push.example/a", "p-a", "a-a");
        subscriptions.subscribe("43", "https://push.example/b", "p-b", "a-b");
        source = new FakeSource();
        sender = new FakeSender();
        watch = new AlertWatch(source, subscriptions, sender);
    }

    @Test
    @DisplayName("a traffic-light change sends to every subscription; an unchanged reading sends to none")
    void trafficLightChangeTriggersASend() {
        source.next("ok", "", "/");
        watch.poll();
        assertEquals(0, sender.sent.size(), "the very first poll has nothing to compare against and"
                + " sent anyway");

        source.next("ok", "", "/");
        watch.poll();
        assertEquals(0, sender.sent.size(), "an unchanged reading was sent as if it had changed");

        source.next("down", "smp", "/services/smp");
        watch.poll();
        assertEquals(2, sender.sent.size(), "a traffic-light change did not reach both subscriptions");
        assertTrue(sender.sent.stream().anyMatch(call -> call.endpoint.equals("https://push.example/a")));
        assertTrue(sender.sent.stream().anyMatch(call -> call.endpoint.equals("https://push.example/b")));
        assertTrue(sender.sent.getFirst().payload.contains("down"),
                "the payload does not name the new level: " + sender.sent.getFirst().payload);
        assertTrue(sender.sent.getFirst().payload.contains("smp"),
                "the payload does not name the subject: " + sender.sent.getFirst().payload);
    }

    @Test
    @DisplayName("a 404/410 (EXPIRED) removes that subscription; the other one stays")
    void expiredSubscriptionIsRemoved() {
        sender.expire("https://push.example/a");

        source.next("ok", "", "/");
        watch.poll();
        source.next("down", "backups", "database dump");
        watch.poll();

        final List<String> left = subscriptions.all().stream()
                .map(PushSubscriptions.Subscription::endpoint).toList();
        assertEquals(List.of("https://push.example/b"), left,
                "the subscription that answered EXPIRED was not removed, or the other one was too");
    }

    /** A worker that answers whatever {@link #next} queued, in order. */
    private static final class FakeSource implements AlertLevelSource {
        private final Deque<AlertReading> queue = new ArrayDeque<>();

        void next(final String level, final String subject, final String path) {
            queue.addLast(new AlertReading(level, subject, path));
        }

        @Override
        public @NotNull AlertReading current() {
            return queue.removeFirst();
        }
    }

    /** A push service that records every call and answers EXPIRED for whichever endpoints asked. */
    private static final class FakeSender implements PushSender {
        record Call(String endpoint, String payload) {
        }

        private final CopyOnWriteArrayList<Call> sent = new CopyOnWriteArrayList<>();
        private final Map<String, Boolean> expired = new ConcurrentHashMap<>();

        void expire(final String endpoint) {
            expired.put(endpoint, Boolean.TRUE);
        }

        @Override
        public @NotNull Result send(final @NotNull PushSubscriptions.Subscription subscription,
                                    final @NotNull String payload) {
            sent.add(new Call(subscription.endpoint(), payload));
            return expired.containsKey(subscription.endpoint()) ? Result.EXPIRED : Result.SENT;
        }
    }
}
