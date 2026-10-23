package eu.nordtal.season.database.alert;

import static eu.nordtal.season.database.AdminTexts.TEXTS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.DatabaseRole;
import eu.nordtal.season.database.JournalDetail;
import eu.nordtal.season.database.JournalDetail.PaymentFlagged;
import eu.nordtal.season.database.TestDatabase;
import eu.nordtal.season.database.audit.JournalAction;
import eu.nordtal.season.database.notify.Channel;
import eu.nordtal.season.database.notify.Notifications;
import eu.nordtal.season.database.notify.PostgresNotifications;
import eu.nordtal.season.database.payment.PaymentRequestStatus;
import eu.nordtal.season.database.update.RunStep;
import eu.nordtal.season.database.update.UpdateKind;
import eu.nordtal.season.messages.value.Mention;
import eu.nordtal.season.messages.value.Money;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@link AlertBook} against the real migrations: an alert is one journal entry with a level and its rows. */
class AlertBookIntegrationTest {

    private static final DiscordId MEMBER = DiscordId.of("100000000000000001");

    private static final Alert DM =
            new Alert(JournalAction.DM_NOT_DELIVERED, Alert.Level.WARN, new JournalDetail.MemberFailed(MEMBER, null));

    private static final Alert DISK = new Alert(
            JournalAction.DISK_FULL,
            Alert.Level.WARN,
            List.of(Measure.against(Measure.Gauge.DISK_PERCENT, "disk", 91.25, 85)));

    private static TestDatabase database;

    @BeforeAll
    static void startDatabase() {
        database = TestDatabase.fresh();
    }

    @BeforeEach
    void emptyJournal() throws SQLException {
        execute("TRUNCATE TABLE journal_entry, steward_inbox, payment_request, discord_user CASCADE");
        execute("INSERT INTO discord_user (discord_id) VALUES ('" + MEMBER.value() + "')");
    }

    @Test
    void theBotRaisesAndStewardClaimsEveryEntryOnceOldestFirst() {
        final AlertBook bot = AlertBook.using(database.dataSourceAs(DatabaseRole.DISCORD_BOT));
        final AlertBook steward = AlertBook.using(database.dataSourceAs(DatabaseRole.STEWARD));

        bot.raise(DM, Actor.BOT);
        steward.raise(DISK, Actor.STEWARD);

        final List<RaisedAlert> claimed = steward.claimUnrouted();
        assertEquals(List.of(DM, DISK), claimed.stream().map(RaisedAlert::alert).toList());
        assertEquals(Actor.BOT, claimed.getFirst().raisedBy());
        assertTrue(steward.claimUnrouted().isEmpty(), "a routed entry was handed out a second time");
        assertEquals(
                List.of(TEXTS.alert().to(Mention.of(MEMBER))),
                bot.alert(claimed.getFirst().id()).orElseThrow().alert().lines(),
                "the bot reads the alert it posts back from its entry");
    }

    @Test
    void anAlertIsOneJournalEntryWithALevelAndOneMeasureRowPerFact() {
        final AlertBook steward = AlertBook.using(database.dataSourceAs(DatabaseRole.STEWARD));
        final Alert down = new Alert(
                JournalAction.SERVICE_DOWN,
                Alert.Level.DOWN,
                List.of(
                        Measure.state(Measure.Gauge.CONTAINER_STATE, "smp", "exited"),
                        Measure.of(Measure.Gauge.EXIT_CODE, "migrate", 3)));

        steward.raise(down, Actor.STEWARD);

        assertEquals(
                List.of("SERVICE_DOWN DOWN STEWARD"),
                strings("SELECT action || ' ' || level || ' ' || actor_kind" + " FROM journal_entry"));
        assertEquals(
                List.of("0 CONTAINER_STATE smp exited", "1 EXIT_CODE migrate 3"),
                strings("SELECT position || ' ' || gauge || ' ' || subject || ' ' || coalesce(state, value::text)"
                        + " FROM alert_measure ORDER BY position"));
        assertEquals(
                List.of(down),
                newest(steward, 5).stream().map(RaisedAlert::alert).toList());
    }

    @Test
    void aFailedRunIsRaisedOnceAndShowsItsFailedAndWarnedSteps() throws SQLException {
        final AlertBook steward = AlertBook.using(database.dataSourceAs(DatabaseRole.STEWARD));
        final long run = Long.parseLong(strings("""
                        INSERT INTO steward_inbox (kind, payload, actor_kind, status, finished)
                        VALUES ('UPDATE', '{"services": []}', 'HOST', 'FAILED', now()) RETURNING id""").getFirst());
        execute("""
                INSERT INTO run_step (run, seq, step, service, finished, outcome, reason)
                VALUES (%1$d, 1, 'STOP', 'smp', now(), 'DONE', NULL),
                       (%1$d, 2, 'START', 'smp', now(), 'FAILED', 'NOT_HEALTHY'),
                       (%1$d, 3, 'CLEANUP', NULL, now(), 'WARNING', NULL)""".formatted(run));
        final Alert failed = new Alert(
                JournalAction.RUN_FAILED,
                Alert.Level.DOWN,
                new JournalDetail.RunFailed(run, UpdateKind.UPDATE, List.of()));

        assertTrue(steward.raiseOnce(failed, Actor.STEWARD));
        assertFalse(steward.raiseOnce(failed, Actor.STEWARD), "the run was raised a second time");

        final Alert read = newest(steward, 5).getFirst().alert();
        assertEquals(
                new JournalDetail.RunFailed(
                        run,
                        UpdateKind.UPDATE,
                        List.of(
                                new JournalDetail.FailedStep(
                                        RunStep.Kind.START, "smp", RunStep.Outcome.FAILED, RunStep.Reason.NOT_HEALTHY),
                                new JournalDetail.FailedStep(
                                        RunStep.Kind.CLEANUP, null, RunStep.Outcome.WARNING, null))),
                read.detail());
        assertEquals("/operations/updates/" + run, read.path());
        assertEquals(3, read.lines().size(), "the run and its two steps");
    }

    @Test
    void aBankPaymentIsRaisedOnceAndShowsTheStatusOfItsRequestAsItIsNow() throws SQLException {
        final AlertBook steward = AlertBook.using(database.dataSourceAs(DatabaseRole.STEWARD));
        final String payment = strings("""
                        INSERT INTO payment_request (reference, discord_id, days, amount_cents, expires, status)
                        VALUES ('NT-AAAAAA', '100000000000000001', 30, 300, now() + interval '1 day', 'OPEN')
                        RETURNING id""").getFirst();
        final Alert flagged = new Alert(
                JournalAction.PAYMENT_NEEDS_LOOK,
                Alert.Level.DOWN,
                new PaymentFlagged(
                        PaymentFlagged.Problem.NOT_OPEN,
                        42,
                        Money.euroCents(300),
                        "NT-AAAAAA",
                        UUID.fromString(payment),
                        null));

        assertTrue(steward.raiseOnce(flagged, Actor.STEWARD));
        assertFalse(steward.raiseOnce(flagged, Actor.STEWARD), "the bank payment was raised a second time");
        execute("UPDATE payment_request SET status = 'EXPIRED'");

        final PaymentFlagged read =
                (PaymentFlagged) newest(steward, 5).getFirst().alert().detail();
        assertEquals(
                new PaymentFlagged(
                        PaymentFlagged.Problem.NOT_OPEN,
                        42,
                        Money.euroCents(300),
                        "NT-AAAAAA",
                        UUID.fromString(payment),
                        PaymentRequestStatus.EXPIRED),
                read);
    }

    @Test
    void theBotsEventsComeBackWithTheirRows() {
        final AlertBook bot = AlertBook.using(database.dataSourceAs(DatabaseRole.DISCORD_BOT));
        final List<Alert> raised = List.of(
                new Alert(
                        JournalAction.ROLE_NOT_CHANGED,
                        Alert.Level.WARN,
                        new JournalDetail.RoleNotChanged(DiscordRole.DONOR, true, MEMBER, "Missing Permissions")),
                new Alert(JournalAction.ROLE_AMBIGUOUS, Alert.Level.WARN, new JournalDetail.RoleNamed("Donor", null)),
                new Alert(
                        JournalAction.ROLE_NOT_CREATED,
                        Alert.Level.WARN,
                        new JournalDetail.RoleNamed("Donor", "Missing Permissions")),
                new Alert(
                        JournalAction.LOCK_NOT_KEPT,
                        Alert.Level.WARN,
                        new JournalDetail.ChannelsNotClosed(List.of(
                                new JournalDetail.ChannelFailure("general", "Missing Access"),
                                new JournalDetail.ChannelFailure("rules", "Missing Access")))),
                new Alert(JournalAction.LOCK_WITHOUT_CHANNEL, Alert.Level.WARN, List.of()),
                new Alert(
                        JournalAction.LINK_FAILED,
                        Alert.Level.WARN,
                        new JournalDetail.MemberFailed(MEMBER, "Unknown Member")));
        raised.forEach(alert -> bot.raise(alert, Actor.BOT));

        final List<Alert> read =
                new ArrayList<>(newest(bot, 10).stream().map(RaisedAlert::alert).toList());
        Collections.reverse(read);
        assertEquals(raised, read);
    }

    @Test
    void aRaiseIsAnnouncedOnTheAlertChannel() throws Exception {
        final AlertBook bot = AlertBook.using(database.dataSourceAs(DatabaseRole.DISCORD_BOT));
        try (Notifications listening = PostgresNotifications.connector(
                        database.jdbcUrl(), database.username(), database.password(), 5, "alert-book-test")
                .listen(Set.of(Channel.ALERT))) {
            bot.raise(DM, Actor.BOT);

            assertTrue(listening.awaitNotification(Duration.ofSeconds(10)), "nobody heard of the alert");
        }
    }

    /** Returns the newest alerts, newest first, read through {@code book}. */
    private static List<RaisedAlert> newest(final AlertBook book, final int limit) {
        final List<UUID> ids = strings(
                        "SELECT id FROM journal_entry WHERE level IS NOT NULL ORDER BY occurred DESC, id DESC LIMIT "
                                + limit)
                .stream()
                .map(UUID::fromString)
                .toList();
        return book.alerts(ids).stream()
                .sorted(Comparator.comparingInt(alert -> ids.indexOf(alert.id())))
                .toList();
    }

    private static void execute(final String sql) throws SQLException {
        try (Connection connection = database.dataSource().getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static List<String> strings(final String sql) {
        try (Connection connection = database.dataSource().getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            final List<String> values = new ArrayList<>();
            while (rows.next()) {
                values.add(rows.getString(1));
            }
            return values;
        } catch (final SQLException exception) {
            throw new IllegalStateException("Test statement failed: " + sql, exception);
        }
    }
}
