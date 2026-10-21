package eu.nordtal.season.database.inbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.common.id.PlayerId;
import eu.nordtal.season.database.JournalDetail;
import eu.nordtal.season.database.TestDatabase;
import eu.nordtal.season.database.audit.AuditLine;
import eu.nordtal.season.database.audit.JournalAction;
import eu.nordtal.season.database.notify.SignalHub;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.Refusal;
import eu.nordtal.season.messages.RefusalReason;
import eu.nordtal.season.messages.document.Document;
import eu.nordtal.season.messages.document.Documents;
import eu.nordtal.season.messages.spec.Display;
import eu.nordtal.season.messages.value.DisplayName;
import java.math.BigDecimal;
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

/** The inbox implementation against PostgreSQL, on the table of the bot. */
class InboxIntegrationTest {

    private static final DiscordId SOMEONE = DiscordId.of("400000000000000002");
    private static final Actor ADMIN = Actor.person(DiscordId.of("400000000000000001"));

    private static TestDatabase database;
    private static Inbox<BotRequest> inbox;

    @BeforeAll
    static void freshDatabase() {
        database = TestDatabase.fresh();
        inbox = Inbox.over(database.dataSource(), BotRequest.TABLE);
    }

    @BeforeEach
    void emptyTable() throws SQLException {
        execute("DELETE FROM bot_inbox");
        execute("INSERT INTO discord_user (discord_id) VALUES ('" + SOMEONE + "') ON CONFLICT DO NOTHING");
    }

    @Test
    void aRequestSubmittedElsewhereWakesItsConsumerThroughTheHubAndNotAPoll() throws Exception {
        final CountDownLatch carriedOut = new CountDownLatch(1);
        // The hub reconciles every minute, so an answer within seconds came from the signal.
        try (SignalHub hub = SignalHub.open(
                database.jdbcUrl(),
                database.username(),
                database.password(),
                5,
                "inbox-test",
                LoggerFactory.getLogger(InboxIntegrationTest.class))) {
            Inbox.over(database.dataSource(), BotRequest.TABLE).listen(hub, request -> {
                carriedOut.countDown();
                return Outcome.done(new BotRequest.Revoke.Answer(1));
            });
            hub.start();
            // Past the drain of the connect, so only the notification can pick the row up.
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
    void aSettledRequestKeepsItsTypedAnswerInItsRowAndCannotBeSettledTwice() throws SQLException {
        final Request<BotRequest> asked = inbox.submit(new BotRequest.Grant(SOMEONE, 30), ADMIN);
        final Request<BotRequest> claimed = inbox.claim().orElseThrow();
        assertEquals(asked.id(), claimed.id());
        assertEquals(new BotRequest.Grant(SOMEONE, 30), claimed.payload());
        assertEquals(InboxStatus.RUNNING, claimed.status());
        assertTrue(claimed.started() != null);

        final BotRequest.Grant.Answer until = new BotRequest.Grant.Answer(Instant.parse("2026-10-20T00:00:00Z"));
        final Request<BotRequest> done =
                inbox.settle(asked.id(), Outcome.done(until)).orElseThrow();
        assertEquals(InboxStatus.DONE, done.status());
        assertEquals(Optional.of(until), done.answer(BotRequest.Grant.Answer.class));
        assertEquals(Optional.of(until), inbox.find(asked.id()).orElseThrow().answer(BotRequest.Grant.Answer.class));
        assertEquals(
                1,
                count("SELECT count(*) FROM bot_grant WHERE request = " + asked.id()
                        + " AND until = '2026-10-20T00:00:00Z'"));
        assertEquals(0, count("SELECT count(*) FROM bot_inbox WHERE outcome IS NOT NULL"), "the answer is no text");
        assertTrue(inbox.settle(asked.id(), Outcome.failed(null)).isEmpty(), "settled a second time");
    }

    @Test
    void anAnswerOfAnotherKindIsNeverWritten() {
        final Request<BotRequest> asked = inbox.submit(new BotRequest.Revoke(SOMEONE), ADMIN);
        assertTrue(inbox.claim().isPresent());

        assertThrows(
                IllegalArgumentException.class,
                () -> inbox.settle(asked.id(), Outcome.done(new BotRequest.Unlink.Answer(true))));
        assertEquals(InboxStatus.RUNNING, inbox.find(asked.id()).orElseThrow().status());
    }

    @Test
    void anAlertKeepsItsEntryAndMentionsThroughTheTable() {
        final BotRequest.PostAlert alert = new BotRequest.PostAlert(
                UUID.fromString("00000000-0000-0000-0000-000000000044"),
                List.of(SOMEONE, DiscordId.of("400000000000000003")));
        inbox.submit(alert, Actor.STEWARD, Schedule.within(Duration.ofHours(1)));
        assertEquals(alert, inbox.claim().orElseThrow().payload());
    }

    @Test
    void aPreviewReachesTheServerWithItsTextAndEveryTypedValue() throws SQLException {
        final PlayerId alex = PlayerId.of(UUID.fromString("00000000-0000-0000-0000-00000000000a"));
        final SmpRequest.PreviewMessage preview = new SmpRequest.PreviewMessage(
                alex,
                new MessagePreview(
                        new MessageRef(
                                "objective.done",
                                Map.of("player.name", new DisplayName(alex, "Alex"), "count", new BigDecimal("3"))),
                        "de",
                        "<good>{player.name}</good> hat {count}",
                        Display.TITLE,
                        Map.of("good", "#123456")));
        final Inbox<SmpRequest> smp = Inbox.over(database.dataSource(), SmpRequest.TABLE);
        execute("DELETE FROM smp_inbox");
        smp.submit(preview, ADMIN);

        final Request<SmpRequest> claimed = smp.claim().orElseThrow();
        assertEquals("PREVIEW_MESSAGE", claimed.kind());
        assertEquals(preview, claimed.payload());
    }

    @Test
    void aDraftedDocumentReachesTheBotWhole() throws SQLException {
        final BotRequest.PreviewMessage preview = new BotRequest.PreviewMessage(
                DiscordId.of("1"),
                MessagePreview.of(
                        new Documents.Packaged(Documents.decode("""
                                        [{"namespace":"bot","name":"welcome","area":"Join","shape":"MESSAGE",
                                          "values":{"player":{"kind":"text","example":"Alex"}},
                                          "slots":[{"id":"join","type":"BUTTON","required":true,"values":{}}]}]""").getFirst(), Document.decode("""
                                        {"parts":[
                                          {"type":"CONTAINER","accent":"#5865f2","parts":[
                                            {"type":"TEXT","lines":[{"text":{"en":"Hi {player}","de":"Hallo {player}"}}]}]},
                                          {"type":"GALLERY","media":["upload://7"]},
                                          {"type":"ROW","items":[{"slot":"join","label":{"en":"Join"}}]}]}""")), "de"));
        final Inbox<BotRequest> bot = Inbox.over(database.dataSource(), BotRequest.TABLE);
        execute("DELETE FROM bot_inbox");
        bot.submit(preview, ADMIN);

        final Request<BotRequest> claimed = bot.claim().orElseThrow();
        assertEquals("PREVIEW_MESSAGE", claimed.kind());
        assertEquals(preview, claimed.payload());
    }

    @Test
    void aKindWithItsOwnTableIsWrittenAsTypedColumnsAndReadBackFromThem() throws SQLException {
        execute("DELETE FROM smp_inbox");
        execute("DELETE FROM hunger_games_inbox");
        execute("INSERT INTO smp_milestone (key) VALUES ('typed-rows') ON CONFLICT DO NOTHING");
        final Inbox<SmpRequest> smp = Inbox.over(database.dataSource(), SmpRequest.TABLE);
        final Inbox<HungerGamesRequest> games = Inbox.over(database.dataSource(), HungerGamesRequest.TABLE);

        final long unlock =
                smp.submit(new SmpRequest.UnlockMilestone("typed-rows"), ADMIN).id();
        smp.submit(new SmpRequest.CompleteObjective("wood"), ADMIN);
        games.submit(new HungerGamesRequest.StartGame(true), ADMIN);

        assertEquals(
                1,
                count("SELECT count(*) FROM smp_unlock_milestone WHERE request = " + unlock
                        + " AND key = 'typed-rows'"));
        assertEquals(0, count("SELECT count(*) FROM smp_inbox WHERE payload IS NOT NULL"));
        assertEquals(1, count("SELECT count(*) FROM hunger_games_start_game WHERE confirmed"));
        assertEquals(
                new SmpRequest.UnlockMilestone("typed-rows"),
                smp.claim().orElseThrow().payload());
        assertEquals(
                new SmpRequest.CompleteObjective("wood"),
                smp.claim().orElseThrow().payload());
        assertEquals(
                new HungerGamesRequest.StartGame(true),
                games.claim().orElseThrow().payload());
        assertEquals(
                List.of(new SmpRequest.UnlockMilestone("typed-rows")),
                smp.recent(SmpRequest.UnlockMilestone.class, 5).stream()
                        .map(Request::payload)
                        .toList());

        execute("UPDATE smp_inbox SET status = 'DONE', finished = now() - interval '1 minute'");
        smp.purge(Duration.ZERO);
        assertEquals(0, count("SELECT count(*) FROM smp_unlock_milestone"));
    }

    @Test
    void everyKindWithItsOwnTableHasAColumnPerComponentOfItsRequestAndAnswer() throws SQLException {
        for (final InboxTable<?> table : Inboxes.ALL) {
            for (final String kind : table.kinds()) {
                final Class<?> type = table.typeOf(kind).orElseThrow();
                if (!table.isRow(type)) {
                    continue;
                }
                final Set<String> expected = new HashSet<>(Set.of("request"));
                for (final var component : type.getRecordComponents()) {
                    expected.add(InboxTable.column(component));
                }
                for (final var component : table.answerOf(type)
                        .map(Class::getRecordComponents)
                        .orElse(new java.lang.reflect.RecordComponent[0])) {
                    expected.add(InboxTable.column(component));
                }
                final Set<String> columns = new HashSet<>();
                try (Connection connection = database.dataSource().getConnection();
                        Statement statement = connection.createStatement();
                        ResultSet rows = statement.executeQuery("SELECT column_name FROM information_schema.columns"
                                + " WHERE table_name = '" + table.rowTable(type) + "'")) {
                    while (rows.next()) {
                        columns.add(rows.getString(1));
                    }
                }
                assertEquals(expected, columns, table.rowTable(type));
            }
        }
    }

    @Test
    void aRefusalIsARowOfItsReasonAndValuesAndReadsBackWorded() throws SQLException {
        execute("DELETE FROM smp_inbox");
        execute("DELETE FROM hunger_games_inbox");
        final Inbox<SmpRequest> smp = Inbox.over(database.dataSource(), SmpRequest.TABLE);
        final Inbox<HungerGamesRequest> games = Inbox.over(database.dataSource(), HungerGamesRequest.TABLE);
        final long objective =
                smp.submit(new SmpRequest.CompleteObjective("wood"), ADMIN).id();
        final long start =
                games.submit(new HungerGamesRequest.StartGame(false), ADMIN).id();
        assertTrue(smp.claim().isPresent());
        assertTrue(games.claim().isPresent());

        final Refusal notActive = ServerRefusal.MILESTONE_NOT_ACTIVE.with("wood", "stone");
        final Refusal tooFew = ServerRefusal.BELOW_SOFT_MINIMUM.with(3, 8);
        smp.settle(objective, Outcome.refused(notActive));
        games.settle(start, Outcome.refused(tooFew));

        assertEquals(
                1,
                count("SELECT count(*) FROM smp_refusal WHERE request = " + objective
                        + " AND reason = 'MILESTONE_NOT_ACTIVE' AND key = 'wood' AND active = 'stone'"));
        assertEquals(
                1,
                count("SELECT count(*) FROM hunger_games_refusal WHERE request = " + start
                        + " AND reason = 'BELOW_SOFT_MINIMUM' AND count = 3 AND minimum = 8"));
        assertEquals(notActive, smp.find(objective).orElseThrow().refusal().orElseThrow());
        assertEquals(tooFew, games.find(start).orElseThrow().refusal().orElseThrow());
    }

    @Test
    void aRefusalOfAnotherAuthorIsNeverWritten() {
        final Request<BotRequest> asked = inbox.submit(new BotRequest.Revoke(SOMEONE), ADMIN);
        assertTrue(inbox.claim().isPresent());

        assertThrows(
                IllegalArgumentException.class,
                () -> inbox.settle(
                        asked.id(),
                        Outcome.refused(new Refusal(Reason.NOT_NOW, new MessageRef("inbox.not-now", Map.of())))));
    }

    private enum Reason implements RefusalReason {
        NOT_NOW
    }

    @Test
    void aHandlerThatThrowsFailsItsOwnRequestAndTheNextStillRuns() {
        final Request<BotRequest> first = inbox.submit(new BotRequest.Grant(SOMEONE, 30), ADMIN);
        final Request<BotRequest> second = inbox.submit(new BotRequest.Grant(SOMEONE, 7), ADMIN);

        assertEquals(2, inbox.drain(request -> {
            if (request.id() == first.id()) {
                throw new IllegalStateException("the guild said no");
            }
            return Outcome.done(new BotRequest.Grant.Answer(Instant.parse("2026-10-27T00:00:00Z")));
        }));

        final Request<BotRequest> failed = inbox.find(first.id()).orElseThrow();
        assertEquals(InboxStatus.FAILED, failed.status());
        assertNull(failed.outcome(), "a failure's words go to the log");
        assertEquals(InboxStatus.DONE, inbox.find(second.id()).orElseThrow().status());
    }

    @Test
    void onlyAnUnclaimedRequestCanBeCancelled() {
        final Request<BotRequest> waiting = inbox.submit(new BotRequest.Revoke(SOMEONE), ADMIN);
        assertEquals(
                InboxStatus.CANCELLED,
                inbox.cancel(waiting.id(), null).orElseThrow().status());

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
        assertNull(failed.outcome(), "the restart is in the log");
        assertEquals(InboxStatus.PENDING, inbox.find(waiting.id()).orElseThrow().status());

        execute("UPDATE bot_inbox SET finished = now() - interval '40 days' WHERE id = " + orphan.id());
        assertEquals(1, inbox.purge(Duration.ofDays(30)));
        assertTrue(inbox.find(orphan.id()).isEmpty());
        assertTrue(inbox.find(waiting.id()).isPresent(), "a pending row is work, not history");
    }

    @Test
    void aJournalledRequestIsWrittenTogetherWithItsLine() throws SQLException {
        final int before = count("SELECT count(*) FROM journal_entry WHERE action = 'REVOKE_ACCESS'");

        inbox.submit(
                new BotRequest.Revoke(SOMEONE),
                ADMIN,
                Schedule.NOW,
                AuditLine.about(
                        JournalAction.REVOKE_ACCESS,
                        Actor.person(DiscordId.of("400000000000000001")),
                        DiscordId.of("400000000000000002"),
                        new JournalDetail.AccessRevoked(1)));

        assertEquals(before + 1, count("SELECT count(*) FROM journal_entry WHERE action = 'REVOKE_ACCESS'"));
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
                Inbox.over(database.dataSourceAs(eu.nordtal.season.database.DatabaseRole.STEWARD), BotRequest.TABLE);
        final Inbox<BotRequest> bot = Inbox.over(
                database.dataSourceAs(eu.nordtal.season.database.DatabaseRole.DISCORD_BOT), BotRequest.TABLE);

        final Request<BotRequest> asked =
                ui.submit(new BotRequest.Revoke(SOMEONE), ADMIN, Schedule.within(Duration.ofMinutes(2)));
        assertEquals(InboxStatus.PENDING, ui.find(asked.id()).orElseThrow().status());
        assertThrowsDenied(ui::claim);

        bot.drain(request -> Outcome.done(new BotRequest.Revoke.Answer(1)));
        bot.settleOrphans(null);
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
        inbox.settle(asked.id(), Outcome.done(new BotRequest.Revoke.Answer(1)));
        final String settled = inbox.version();

        assertEquals(
                4, new HashSet<>(List.of(empty, submitted, claimed, settled)).size(), "a write left the version alone");
        assertEquals(settled, inbox.version(), "a read moved the version");

        inbox.submit(new BotRequest.Revoke(SOMEONE), ADMIN, Schedule.within(Duration.ofMillis(300)));
        final String waiting = inbox.version();
        TimeUnit.MILLISECONDS.sleep(500);
        // Nothing wrote the row, yet it reads as expired from now on, so the version says so.
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
