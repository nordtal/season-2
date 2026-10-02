package eu.nordtal.s2.database.inbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.database.Actor;
import eu.nordtal.s2.database.TestDatabase;
import eu.nordtal.s2.database.alert.Alert;
import eu.nordtal.s2.database.audit.AuditLine;
import eu.nordtal.s2.database.notify.SignalHub;
import eu.nordtal.s2.messages.MessageRef;
import eu.nordtal.s2.messages.Refusal;
import eu.nordtal.s2.messages.RefusalReason;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** The one inbox implementation against PostgreSQL, on the bot's table, which every other table shares columns with. */
class InboxIntegrationTest {

    private static final DiscordId SOMEONE = DiscordId.of("400000000000000002");
    private static final Actor ADMIN = Actor.person(DiscordId.of("400000000000000001"));

    private static TestDatabase database;
    private static Inbox<BotRequest> inbox;

    private enum Reason implements RefusalReason {
        NOT_NOW
    }

    @BeforeAll
    static void freshDatabase() {
        database = TestDatabase.fresh();
        inbox = Inbox.over(database.dataSource(), BotRequest.TABLE);
    }

    @BeforeEach
    void emptyTable() throws SQLException {
        execute("DELETE FROM bot_inbox");
    }

    @Test
    void aRequestSubmittedElsewhereWakesItsConsumerThroughTheHubAndNotAPoll() throws Exception {
        final CountDownLatch carriedOut = new CountDownLatch(1);
        // The hub's reconciliation is a minute: an answer within seconds can only have come from the signal.
        try (SignalHub hub = SignalHub.open(
                database.jdbcUrl(),
                database.username(),
                database.password(),
                5,
                "inbox-test",
                LoggerFactory.getLogger(InboxIntegrationTest.class))) {
            Inbox.over(database.dataSource(), BotRequest.TABLE).listen(hub, request -> {
                carriedOut.countDown();
                return Outcome.done(Map.of("revoked", "1"));
            });
            hub.start();
            // Past the connect's own drain, so only the notification can pick the row up.
            TimeUnit.MILLISECONDS.sleep(500);

            final Request<BotRequest> asked = inbox.submit(new BotRequest.Revoke(SOMEONE), ADMIN);

            assertTrue(carriedOut.await(10, TimeUnit.SECONDS), "the consumer never woke up");
            assertEquals(InboxStatus.DONE, waitForSettled(asked.id()).status());
        }
    }

    @Test
    void twoConsumersNeverClaimTheSameRow() throws Exception {
        for (int i = 0; i < 40; i++) {
            inbox.submit(new BotRequest.SetPlaytime(SOMEONE, i), ADMIN);
        }
        final Set<Long> claimed = ConcurrentHashMap.newKeySet();
        final List<Long> twice = new ArrayList<>();
        final ExecutorService consumers = Executors.newFixedThreadPool(2);
        try {
            final List<Future<?>> running = new ArrayList<>();
            for (int consumer = 0; consumer < 2; consumer++) {
                final Inbox<BotRequest> own = Inbox.over(database.dataSource(), BotRequest.TABLE);
                running.add(consumers.submit(() -> {
                    for (Optional<Request<BotRequest>> one = own.claim(); one.isPresent(); one = own.claim()) {
                        if (!claimed.add(one.get().id())) {
                            synchronized (twice) {
                                twice.add(one.get().id());
                            }
                        }
                    }
                }));
            }
            for (final Future<?> one : running) {
                one.get(30, TimeUnit.SECONDS);
            }
        } finally {
            consumers.shutdownNow();
        }
        assertEquals(40, claimed.size());
        assertTrue(twice.isEmpty(), "claimed twice: " + twice);
    }

    @Test
    void aRequestForLaterIsNotClaimedBeforeItsTime() {
        final Request<BotRequest> later =
                inbox.submit(new BotRequest.Revoke(SOMEONE), ADMIN, Schedule.after(Duration.ofHours(1)));

        assertTrue(inbox.claim().isEmpty(), "claimed an hour early");
        assertEquals(Optional.of(later.scheduledFor()), inbox.nextDue());
        assertTrue(later.scheduledFor().isAfter(later.requested().plus(Duration.ofMinutes(59))));

        final Request<BotRequest> now = inbox.submit(new BotRequest.Unlink(SOMEONE), ADMIN);
        assertEquals(now.id(), inbox.claim().orElseThrow().id(), "the due one is claimed past the later one");
    }

    @Test
    void anUnclaimedRequestReadsExpiredOnceItsPatienceIsGoneAndIsNeverClaimed() {
        final Request<BotRequest> asked =
                inbox.submit(new BotRequest.Revoke(SOMEONE), ADMIN, Schedule.within(Duration.ZERO));

        assertEquals(InboxStatus.EXPIRED, inbox.find(asked.id()).orElseThrow().status(), "the asker sees it");
        assertTrue(inbox.claim().isEmpty());
        assertEquals(1, inbox.expireDue());
        final Request<BotRequest> expired = inbox.find(asked.id()).orElseThrow();
        assertEquals(InboxStatus.EXPIRED, expired.status());
        assertTrue(expired.finished() != null, "the consumer's pass wrote it down");
    }

    @Test
    void aSettledRequestKeepsItsAnswerAndCannotBeSettledTwice() {
        final Request<BotRequest> asked = inbox.submit(new BotRequest.Grant(SOMEONE, 30), ADMIN);
        final Request<BotRequest> claimed = inbox.claim().orElseThrow();
        assertEquals(asked.id(), claimed.id());
        assertEquals(new BotRequest.Grant(SOMEONE, 30), claimed.payload());
        assertEquals(InboxStatus.RUNNING, claimed.status());
        assertTrue(claimed.started() != null);

        assertTrue(inbox.progress(asked.id(), Map.of("step", "role")));
        assertEquals(
                Optional.of(Map.of("step", "role")),
                inbox.find(asked.id()).orElseThrow().outcome(Map.class));

        final Request<BotRequest> done = inbox.settle(asked.id(), Outcome.done(Map.of("until", "2026-10-20")))
                .orElseThrow();
        assertEquals(InboxStatus.DONE, done.status());
        assertEquals("{\"until\": \"2026-10-20\"}", done.outcome());
        assertTrue(inbox.settle(asked.id(), Outcome.failed(null)).isEmpty(), "settled a second time");
        assertFalse(inbox.progress(asked.id(), Map.of()), "rewrote a settled answer");
    }

    @Test
    void anAlertKeepsItsLevelAndMentionsThroughTheTable() {
        final BotRequest.PostAlert alert = new BotRequest.PostAlert(
                Alert.Level.DOWN, "smp is not running", "exited", List.of(SOMEONE, DiscordId.of("400000000000000003")));
        inbox.submit(alert, Actor.STEWARD, Schedule.within(Duration.ofHours(1)));
        assertEquals(alert, inbox.claim().orElseThrow().payload());
    }

    @Test
    void aBookedPaymentReachesTheBotWithEveryTypedValue() {
        final BotRequest.PaymentBooked booked = new BotRequest.PaymentBooked(
                UUID.fromString("00000000-0000-0000-0000-000000000042"),
                SOMEONE,
                "NT-ABC123",
                60,
                500,
                true,
                900,
                Instant.parse("2026-10-01T00:00:00Z"),
                Instant.parse("2026-11-30T00:00:00Z"));
        inbox.submit(booked, Actor.STEWARD, Schedule.within(Duration.ofHours(1)));

        final Request<BotRequest> claimed = inbox.claim().orElseThrow();
        assertEquals("PAYMENT_BOOKED", claimed.kind());
        assertEquals(booked, claimed.payload());
    }

    @Test
    void aRefusalReadsBackWithItsReasonAndMessage() {
        final Request<BotRequest> asked = inbox.submit(new BotRequest.Revoke(SOMEONE), ADMIN);
        assertTrue(inbox.claim().isPresent());

        inbox.settle(
                asked.id(),
                Outcome.refused(new Refusal(Reason.NOT_NOW, new MessageRef("inbox.not-now", Map.of("days", 3)))));

        final Refusal read = inbox.find(asked.id()).orElseThrow().refusal().orElseThrow();
        assertEquals("NOT_NOW", read.reason().name());
        assertEquals(new MessageRef("inbox.not-now", Map.of("days", 3L)), read.message());
    }

    @Test
    void aHandlerThatThrowsFailsItsOwnRequestAndTheNextStillRuns() {
        final Request<BotRequest> first = inbox.submit(new BotRequest.Grant(SOMEONE, 30), ADMIN);
        final Request<BotRequest> second = inbox.submit(new BotRequest.Grant(SOMEONE, 7), ADMIN);

        assertEquals(2, inbox.drain(request -> {
            if (request.id() == first.id()) {
                throw new IllegalStateException("the guild said no");
            }
            return Outcome.done(null);
        }));

        final Request<BotRequest> failed = inbox.find(first.id()).orElseThrow();
        assertEquals(InboxStatus.FAILED, failed.status());
        assertEquals("{\"error\": \"the guild said no\"}", failed.outcome());
        assertEquals(InboxStatus.DONE, inbox.find(second.id()).orElseThrow().status());
    }

    @Test
    void onlyAnUnclaimedRequestCanBeCancelled() {
        final Request<BotRequest> waiting = inbox.submit(new BotRequest.Revoke(SOMEONE), ADMIN);
        assertEquals(
                InboxStatus.CANCELLED,
                inbox.cancel(waiting.id(), Map.of("by", "admin")).orElseThrow().status());

        final Request<BotRequest> running = inbox.submit(new BotRequest.Unlink(SOMEONE), ADMIN);
        assertTrue(inbox.claim().isPresent());
        assertTrue(inbox.cancel(running.id(), null).isEmpty(), "withdrew a request already running");
    }

    @Test
    void whatTheLastConsumerLeftRunningFailsAndOldHistoryIsPurged() throws SQLException {
        final Request<BotRequest> orphan = inbox.submit(new BotRequest.Revoke(SOMEONE), ADMIN);
        assertTrue(inbox.claim().isPresent());
        final Request<BotRequest> waiting = inbox.submit(new BotRequest.Unlink(SOMEONE), ADMIN);

        Inbox.takeOver(database.dataSource(), BotRequest.TABLE, "the bot");
        final Request<BotRequest> failed = inbox.find(orphan.id()).orElseThrow();
        assertEquals(InboxStatus.FAILED, failed.status());
        assertEquals("{\"error\": \"the bot restarted while it ran this\"}", failed.outcome());
        assertEquals(InboxStatus.PENDING, inbox.find(waiting.id()).orElseThrow().status());

        execute("UPDATE bot_inbox SET finished = now() - interval '40 days' WHERE id = " + orphan.id());
        assertEquals(1, inbox.purge(Duration.ofDays(30)));
        assertTrue(inbox.find(orphan.id()).isEmpty());
        assertTrue(inbox.find(waiting.id()).isPresent(), "a pending row is work, not history");
    }

    @Test
    void aJournalledRequestIsWrittenTogetherWithItsLine() throws SQLException {
        final int before = count("SELECT count(*) FROM audit_log WHERE action = 'INBOX_TEST'");

        inbox.submit(
                new BotRequest.Revoke(SOMEONE),
                ADMIN,
                Schedule.NOW,
                AuditLine.about(
                        "INBOX_TEST",
                        Actor.person(DiscordId.of("400000000000000001")),
                        DiscordId.of("400000000000000002"),
                        Map.of()));

        assertEquals(before + 1, count("SELECT count(*) FROM audit_log WHERE action = 'INBOX_TEST'"));
        assertEquals(1, count("SELECT count(*) FROM bot_inbox"));
    }

    @Test
    void theKindCheckOfEveryTableIsExactlyItsKinds() throws SQLException {
        for (final InboxTable<?> table : Inboxes.ALL) {
            final Set<String> checked = new HashSet<>();
            try (Connection connection = database.dataSource().getConnection();
                    Statement statement = connection.createStatement();
                    ResultSet rows = statement.executeQuery("SELECT pg_get_constraintdef(oid) FROM pg_constraint"
                            + " WHERE conname = '" + table.name() + "_kind_check'")) {
                assertTrue(rows.next(), table + " has no kind check");
                final java.util.regex.Matcher kinds =
                        java.util.regex.Pattern.compile("'([A-Z_]+)'").matcher(rows.getString(1));
                while (kinds.find()) {
                    checked.add(kinds.group(1));
                }
            }
            assertEquals(Set.copyOf(table.kinds()), checked, table.name());
        }
    }

    @Test
    void aWriterCanAskAndReadAndOnlyTheConsumerCanClaim() {
        final Inbox<BotRequest> ui =
                Inbox.over(database.dataSourceAs(eu.nordtal.s2.database.DatabaseRole.STEWARD), BotRequest.TABLE);
        final Inbox<BotRequest> bot =
                Inbox.over(database.dataSourceAs(eu.nordtal.s2.database.DatabaseRole.DISCORD_BOT), BotRequest.TABLE);

        final Request<BotRequest> asked =
                ui.submit(new BotRequest.Revoke(SOMEONE), ADMIN, Schedule.within(Duration.ofMinutes(2)));
        assertEquals(InboxStatus.PENDING, ui.find(asked.id()).orElseThrow().status());
        assertThrowsDenied(ui::claim);

        bot.drain(request -> Outcome.done(Map.of("revoked", "1")));
        bot.settleOrphans(Map.of());
        bot.purge(Duration.ofDays(30));
        assertEquals(InboxStatus.DONE, ui.find(asked.id()).orElseThrow().status());
    }

    @Test
    void theVersionMovesWithEveryWriteAndWithAnExpiryNobodyWrote() throws InterruptedException {
        final String empty = inbox.version();
        final Request<BotRequest> asked = inbox.submit(new BotRequest.Revoke(SOMEONE), ADMIN);
        final String submitted = inbox.version();
        assertEquals(asked.id(), inbox.claim().orElseThrow().id());
        final String claimed = inbox.version();
        inbox.settle(asked.id(), Outcome.done(Map.of("revoked", "1")));
        final String settled = inbox.version();

        assertEquals(
                4, new HashSet<>(List.of(empty, submitted, claimed, settled)).size(), "a write left the version alone");
        assertEquals(settled, inbox.version(), "a read moved the version");

        inbox.submit(new BotRequest.Revoke(SOMEONE), ADMIN, Schedule.within(Duration.ofMillis(300)));
        final String waiting = inbox.version();
        TimeUnit.MILLISECONDS.sleep(500);
        // Nothing wrote the row, yet it reads as expired from now on, so the version has to say so.
        assertFalse(waiting.equals(inbox.version()), "an expiry left the version alone");
    }

    private static void assertThrowsDenied(final Runnable call) {
        try {
            call.run();
        } catch (final RuntimeException denied) {
            assertTrue(String.valueOf(denied.getMessage()).contains("permission denied"), denied.toString());
            return;
        }
        throw new AssertionError("the call went through");
    }

    private static Request<BotRequest> waitForSettled(final long id) throws InterruptedException {
        final Instant deadline = Instant.now().plusSeconds(10);
        while (Instant.now().isBefore(deadline)) {
            final Request<BotRequest> row = inbox.find(id).orElseThrow();
            if (row.status().settled()) {
                return row;
            }
            TimeUnit.MILLISECONDS.sleep(50);
        }
        throw new AssertionError("request " + id + " never settled");
    }

    private static int count(final String sql) throws SQLException {
        try (Connection connection = database.dataSource().getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private static void execute(final String sql) throws SQLException {
        try (Connection connection = database.dataSource().getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
