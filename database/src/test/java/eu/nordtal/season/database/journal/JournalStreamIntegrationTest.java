package eu.nordtal.season.database.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.JournalDetail.AccessGranted;
import eu.nordtal.season.database.JournalDetail.PluginRemoved;
import eu.nordtal.season.database.JournalDetail.RunAsked;
import eu.nordtal.season.database.JournalDetail.RunCancelled;
import eu.nordtal.season.database.JournalDetail.Settled;
import eu.nordtal.season.database.TestDatabase;
import eu.nordtal.season.database.alert.Alert;
import eu.nordtal.season.database.alert.AlertBook;
import eu.nordtal.season.database.alert.Measure;
import eu.nordtal.season.database.audit.AuditDirectory;
import eu.nordtal.season.database.audit.AuditLine;
import eu.nordtal.season.database.audit.JournalAction;
import eu.nordtal.season.database.journal.JournalSearch.Term;
import eu.nordtal.season.database.journal.JournalSearch.Term.Kind;
import eu.nordtal.season.database.payment.PaymentMatch;
import eu.nordtal.season.database.update.UpdateKind;
import eu.nordtal.season.messages.value.Money;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** Exercises {@link JournalStream} against PostgreSQL with the real migrations: one test per kind of word. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JournalStreamIntegrationTest {

    private static final String ALICE = "100000000000000001";
    private static final String BOB = "100000000000000002";
    private static final String ADMIN = "100000000000000009";
    private static final UUID ALICE_MC = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Instant UNTIL = Instant.parse("2026-11-02T10:00:00Z");

    private static DataSource dataSource;

    private AuditDirectory journal;
    private AlertBook alerts;
    private JournalStream stream;

    @BeforeAll
    static void startDatabase() {
        dataSource = TestDatabase.fresh().dataSource();
    }

    @BeforeEach
    void freshJournal() {
        execute("TRUNCATE TABLE account_link, payment_request, journal_entry, steward_inbox, discord_user CASCADE");
        execute("INSERT INTO discord_user (discord_id, discord_username, discord_display_name) VALUES ('" + ALICE
                + "', 'alice', 'Alice A'), ('" + BOB + "', 'bob', NULL), ('" + ADMIN + "', 'root', NULL)");
        execute("INSERT INTO account_link (discord_id, mc_uuid, mc_name) VALUES ('" + ALICE + "', '" + ALICE_MC
                + "', 'Steve')");
        journal = AuditDirectory.using(dataSource);
        alerts = AlertBook.using(dataSource);
        stream = JournalStream.using(dataSource);
    }

    @Test
    void theStreamIsEntriesAlertsAndRunsNewestFirstAndARunStandsForTheEntryThatAskedForIt() {
        about(JournalAction.LINK, ALICE);
        final long run = run("UPDATE", "DONE", ADMIN, "smp");
        journal.record(
                AuditLine.of(JournalAction.REQUEST_RUN, admin(), new RunAsked(run, UpdateKind.UPDATE, List.of("smp"))));
        alerts.raise(down("smp"), Actor.STEWARD);

        assertEquals(List.of("alert SERVICE_DOWN", "run " + run, "entry LINK"), shown(List.of()));
        final JournalItem runRow = stream.page(List.of(), null, 10).items().get(1);
        assertEquals(Actor.person(DiscordId.of(ADMIN)), runRow.actor());
        assertEquals(List.of("smp"), runRow.run().services());
    }

    @Test
    void aPageEndsWithTheCursorOfTheNextAndTheLastPageWithNone() {
        about(JournalAction.LINK, ALICE);
        about(JournalAction.LINK, BOB);
        run("BACKUP", "DONE", null);

        final JournalStream.Page first = stream.page(List.of(), null, 2);
        assertEquals(2, first.items().size());
        assertNotNull(first.next());
        final JournalStream.Page second = stream.page(List.of(), first.next(), 2);
        assertEquals(
                List.of("entry LINK"),
                second.items().stream().map(JournalStreamIntegrationTest::label).toList());
        assertNull(second.next());
    }

    @Test
    void aPaymentReferenceMatchesItsBookingExactly() {
        final UUID payment = payment("NT-ABCDEF", ALICE, 30);
        journal.record(AuditLine.about(
                JournalAction.SETTLE,
                admin(),
                DiscordId.of(ALICE),
                new Settled(payment, "NT-ABCDEF", PaymentMatch.MANUAL, 30, 30, null, Money.euroCents(0))));
        about(JournalAction.LINK, ALICE);

        assertEquals(List.of("entry SETTLE"), shown(List.of(term(Kind.PAYMENT, "NT-ABCDEF"))));
        assertEquals(List.of(), shown(List.of(term(Kind.PAYMENT, "NT-ABCDEE"))));
    }

    @Test
    void aDiscordIdMatchesWhatConcernsOrWasDoneByThePerson() {
        about(JournalAction.LINK, ALICE);
        about(JournalAction.LINK, BOB);
        final long run = run("RESTART", "DONE", ADMIN);

        assertEquals(List.of("entry LINK"), shown(List.of(term(Kind.DISCORD, ALICE))));
        assertEquals(List.of("run " + run, "entry LINK", "entry LINK"), shown(List.of(term(Kind.DISCORD, ADMIN))));
    }

    @Test
    void aPlayerUuidMatchesWhatConcernsItsLinkedPerson() {
        about(JournalAction.GRANT_ACCESS, ALICE);
        about(JournalAction.LINK, BOB);

        assertEquals(List.of("entry GRANT_ACCESS"), shown(List.of(term(Kind.UUID, ALICE_MC.toString()))));
    }

    @Test
    void aNameMatchesTheStartOfADiscordNameADisplayNameOrAPlayerName() {
        about(JournalAction.GRANT_ACCESS, ALICE);
        about(JournalAction.LINK, BOB);

        assertEquals(List.of("entry GRANT_ACCESS"), shown(List.of(term(Kind.NAME, "ALI"))));
        assertEquals(List.of("entry GRANT_ACCESS"), shown(List.of(term(Kind.NAME, "alice a"))));
        assertEquals(List.of("entry GRANT_ACCESS"), shown(List.of(term(Kind.NAME, "steve"))));
        assertEquals(List.of("entry LINK"), shown(List.of(term(Kind.NAME, "bob"))));
        assertEquals(List.of(), shown(List.of(term(Kind.NAME, "carol"))));
    }

    @Test
    void daysMatchAGrantOrABookingOfAtLeastThatMany() {
        journal.record(
                AuditLine.about(JournalAction.GRANT_ACCESS, admin(), DiscordId.of(BOB), new AccessGranted(90, UNTIL)));
        journal.record(
                AuditLine.about(JournalAction.GRANT_ACCESS, admin(), DiscordId.of(ALICE), new AccessGranted(7, UNTIL)));
        about(JournalAction.LINK, BOB);

        assertEquals(List.of("entry GRANT_ACCESS"), shown(List.of(new Term(Kind.DAYS, "30d", List.of("30")))));
        assertEquals(
                List.of("entry GRANT_ACCESS", "entry GRANT_ACCESS"),
                shown(List.of(new Term(Kind.DAYS, "7d", List.of("7")))));
    }

    @Test
    void aServiceMatchesAnEntryAMeasurementAndARun() {
        final long smp = run("REMOVE_PLUGIN", "DONE", null, "smp");
        journal.record(AuditLine.of(JournalAction.REMOVE_PLUGIN, admin(), new PluginRemoved(smp, "smp", "chunky")));
        alerts.raise(down("smp"), Actor.STEWARD);
        run("RESTART", "DONE", null, "proxy");

        assertEquals(Set.of("smp", "proxy"), stream.services());
        assertEquals(
                List.of("alert SERVICE_DOWN", "entry REMOVE_PLUGIN", "run " + smp),
                shown(List.of(term(Kind.SERVICE, "smp"))));
    }

    @Test
    void aLevelMatchesAlertsAndDownAlsoAFailedRun() {
        alerts.raise(down("smp"), Actor.STEWARD);
        final long failed = run("UPDATE", "FAILED", null);
        run("UPDATE", "DONE", null);
        alerts.raise(new Alert(JournalAction.SERVICE_DOWN, Alert.Level.OK, List.of()), Actor.STEWARD);

        assertEquals(List.of("run " + failed, "alert SERVICE_DOWN"), shown(List.of(term(Kind.LEVEL, "DOWN"))));
        assertEquals(List.of("alert SERVICE_DOWN"), shown(List.of(term(Kind.LEVEL, "OK"))));
    }

    @Test
    void aRunKindAndARunStatusMatchRunsAndTheEntriesOfTheirRuns() {
        final long backup = run("BACKUP", "CANCELLED", null);
        journal.record(AuditLine.of(JournalAction.CANCEL_RUN, admin(), new RunCancelled(backup, UpdateKind.BACKUP)));
        run("UPDATE", "DONE", null);

        assertEquals(List.of("entry CANCEL_RUN", "run " + backup), shown(List.of(term(Kind.RUN_KIND, "BACKUP"))));
        assertEquals(List.of("entry CANCEL_RUN", "run " + backup), shown(List.of(term(Kind.RUN_STATUS, "CANCELLED"))));
    }

    @Test
    void aGaugeMatchesTheAlertsThatMeasuredIt() {
        alerts.raise(down("smp"), Actor.STEWARD);
        alerts.raise(
                new Alert(
                        JournalAction.DISK_FULL,
                        Alert.Level.WARN,
                        List.of(new Measure(Measure.Gauge.DISK_PERCENT, "disk", 91.0, 90.0, null))),
                Actor.STEWARD);

        assertEquals(List.of("alert DISK_FULL"), shown(List.of(term(Kind.GAUGE, "DISK_PERCENT"))));
    }

    @Test
    void anActionMatchesItsEntriesAndARunStandsForTheRequestOfIt() {
        about(JournalAction.LINK, ALICE);
        about(JournalAction.GRANT_ACCESS, ALICE);
        final long run = run("RESTART", "DONE", null);

        assertEquals(
                List.of("entry GRANT_ACCESS", "entry LINK"),
                shown(List.of(new Term(Kind.ACTION, "a", List.of("LINK", "GRANT_ACCESS")))));
        assertEquals(List.of("run " + run), shown(List.of(new Term(Kind.ACTION, "run", List.of("REQUEST_RUN")))));
    }

    @Test
    void anActionWordAlsoMatchesTheNamesItStarts() {
        about(JournalAction.LINK, ALICE);
        about(JournalAction.GRANT_ACCESS, ALICE);
        about(JournalAction.GRANT_ACCESS, BOB);

        assertEquals(
                List.of("entry GRANT_ACCESS", "entry GRANT_ACCESS", "entry LINK"),
                shown(List.of(new Term(Kind.ACTION, "al", List.of("GRANT_ACCESS")))));
    }

    @Test
    void wordsCombineWithAnd() {
        about(JournalAction.LINK, ALICE);
        about(JournalAction.LINK, BOB);
        about(JournalAction.GRANT_ACCESS, ALICE);

        assertEquals(
                List.of("entry LINK"),
                shown(List.of(term(Kind.DISCORD, ALICE), new Term(Kind.ACTION, "link", List.of("LINK")))));
    }

    @Test
    void anAlertKnowsWhenTheProblemItRaisesOrClearsBegan() {
        final UUID first = alerts.raise(down("smp"), Actor.STEWARD);
        execute("UPDATE journal_entry SET occurred = now() - interval '42 minutes' WHERE id = '" + first + "'");
        alerts.raise(down("proxy"), Actor.STEWARD);
        alerts.raise(new Alert(JournalAction.SERVICE_DOWN, Alert.Level.OK, List.of()), Actor.STEWARD);
        alerts.raise(down("smp"), Actor.STEWARD);

        final List<JournalItem> items = stream.page(List.of(), null, 10).items();
        final Instant began = items.getLast().occurred();
        assertEquals(
                List.of(began, began),
                List.of(items.get(2).opened(), items.get(1).opened()));
        assertEquals(42, Duration.between(began, items.get(1).occurred()).toMinutes(), "the all-clear came after it");
        assertEquals(items.getFirst().occurred(), items.getFirst().opened(), "a new problem begins after the clear");
    }

    private static Alert down(final String service) {
        return new Alert(
                JournalAction.SERVICE_DOWN,
                Alert.Level.DOWN,
                List.of(new Measure(Measure.Gauge.CONTAINER_STATE, service, null, null, "exited")));
    }

    private static Actor admin() {
        return Actor.person(DiscordId.of(ADMIN));
    }

    private void about(final JournalAction action, final String person) {
        journal.record(AuditLine.about(action, admin(), DiscordId.of(person), null));
    }

    private static Term term(final Kind kind, final String value) {
        return new Term(kind, value, List.of(value));
    }

    private List<String> shown(final List<Term> terms) {
        return stream.page(terms, null, 50).items().stream()
                .map(JournalStreamIntegrationTest::label)
                .toList();
    }

    private static String label(final JournalItem item) {
        if (item.run() != null) {
            return "run " + item.run().id();
        }
        return item.alert() != null
                ? "alert " + item.alert().alert().action()
                : "entry " + item.entry().action();
    }

    /** Writes a run that a person or the host asked for, finished unless pending or running, with its services. */
    private static long run(final String kind, final String status, final String person, final String... services) {
        final long id = Long.parseLong(strings("""
                        INSERT INTO steward_inbox (kind, actor_kind, actor_id, status, finished)
                        VALUES ('%s', '%s', %s, '%s', now())
                        RETURNING id
                        """.formatted(
                        kind, person == null ? "HOST" : "PERSON", person == null ? "NULL" : "'" + person + "'", status))
                .getFirst());
        for (final String service : services) {
            execute("INSERT INTO run_service (run, service, moving) VALUES (" + id + ", '" + service + "', false)");
        }
        return id;
    }

    private static UUID payment(final String reference, final String person, final int days) {
        return UUID.fromString(strings("""
                        INSERT INTO payment_request (reference, discord_id, days, amount_cents, status, expires, settled,
                                                     matched_by)
                        VALUES ('%s', '%s', %d, 300, 'PAID', now() + interval '1 day', now(), 'MANUAL')
                        RETURNING id
                        """.formatted(reference, person, days)).getFirst());
    }

    private static List<String> strings(final String sql) {
        try (Connection connection = dataSource.getConnection();
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

    private static void execute(final String sql) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (final SQLException exception) {
            throw new IllegalStateException("Test statement failed: " + sql, exception);
        }
    }
}
