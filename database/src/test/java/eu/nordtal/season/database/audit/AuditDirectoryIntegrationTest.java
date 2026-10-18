package eu.nordtal.season.database.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.SeasonPhase;
import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.JournalDetail;
import eu.nordtal.season.database.JournalDetail.AccessGranted;
import eu.nordtal.season.database.JournalDetail.Settled;
import eu.nordtal.season.database.TestDatabase;
import eu.nordtal.season.database.alert.AlertChannel;
import eu.nordtal.season.database.alert.AlertType;
import eu.nordtal.season.database.payment.PaymentMatch;
import eu.nordtal.season.database.update.UpdateKind;
import eu.nordtal.season.messages.Messages;
import eu.nordtal.season.messages.value.Money;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** Exercises {@link AuditDirectory} against PostgreSQL with the real migrations. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuditDirectoryIntegrationTest {

    private static final String ALICE = "100000000000000001";
    private static final String BOB = "100000000000000002";
    private static final String ADMIN = "100000000000000009";

    private static final UUID ALICE_MC = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Instant UNTIL = Instant.parse("2026-11-02T10:00:00Z");
    private static final Messages ADMIN_TEXTS =
            Messages.load(AuditDirectoryIntegrationTest.class.getClassLoader(), "messages/admin");

    private static DataSource dataSource;

    private AuditDirectory directory;

    @BeforeAll
    static void startDatabase() {
        dataSource = TestDatabase.fresh().dataSource();
    }

    @BeforeEach
    void freshDirectory() {
        execute("TRUNCATE TABLE access_grant, account_link, link_code, payment_request, journal_entry, steward_inbox, "
                + "player_playtime, discord_user CASCADE");
        execute("INSERT INTO discord_user (discord_id) VALUES ('" + ALICE + "'), ('" + BOB + "')");
        directory = AuditDirectory.using(dataSource);
    }

    @Test
    void everyDetailComesBackFromItsRowsAndReadsAsTheLineWritten() {
        final UUID tab = payment("NT-TABTAB", ALICE, 30, 300, "TAB");
        final UUID byHand = payment("NT-HANDHA", BOB, 7, null, "MANUAL");
        final long update = run("UPDATE", "{\"services\": [\"smp\", \"proxy\"]}");
        final long restore = run("RESTORE", "{\"services\": [], \"archive\": \"nordtal-1.dump\"}");
        final long removal = run("REMOVE_PLUGIN", "{\"services\": [\"smp\"], \"artifact\": \"chunky\"}");
        final Map<JournalDetail, JournalAction> written = everyDetail(tab, byHand, update, restore, removal);

        final Set<Class<?>> covered =
                written.keySet().stream().map(Object::getClass).collect(Collectors.toSet());
        assertEquals(
                Set.of(),
                Arrays.stream(JournalDetail.class.getPermittedSubclasses())
                        .filter(kind -> !covered.contains(kind))
                        .collect(Collectors.toSet()),
                "every detail is written and read back here");

        written.forEach((detail, action) -> directory.record(
                new AuditLine(action, Actor.person(DiscordId.of(ADMIN)), DiscordId.of(ALICE), null, detail)));

        final List<String> expected = written.entrySet().stream()
                .map(each -> each.getValue() + ": "
                        + ADMIN_TEXTS.format(Locale.ENGLISH, each.getKey().line()))
                .toList();
        final List<String> read = directory.recent(100).reversed().stream()
                .map(entry -> entry.action() + ": " + ADMIN_TEXTS.format(Locale.ENGLISH, entry.line()))
                .toList();
        assertEquals(expected, read);
    }

    @Test
    void anEntryKeepsTheServiceRunAndPaymentItConcernsInColumns() {
        final long update = run("UPDATE", "{\"services\": [\"smp\"]}");
        directory.record(AuditLine.of(
                JournalAction.REQUEST_RUN,
                Actor.STEWARD,
                new JournalDetail.RunAsked(update, UpdateKind.UPDATE, List.of("smp"))));
        directory.record(
                AuditLine.of(JournalAction.CONSOLE, Actor.STEWARD, new JournalDetail.ConsoleTyped("smp", "list")));

        assertEquals(
                List.of("CONSOLE|smp|-", "REQUEST_RUN|-|" + update),
                strings("SELECT action || '|' || coalesce(service, '-') || '|' || coalesce(run::text, '-')"
                        + " FROM journal_entry ORDER BY action"));
    }

    @Test
    void everyActionIsAcceptedWithoutValuesAndReadsAsAtLeastItsName() {
        for (final JournalAction action : JournalAction.values()) {
            if (action != JournalAction.SETTLE) {
                directory.record(AuditLine.of(action, Actor.STEWARD, null));
            }
        }

        assertEquals(JournalAction.values().length - 1, directory.recent(100).size());
    }

    @Test
    void everyColumnSurvivesTheRoundTrip() {
        directory.record(new AuditLine(
                JournalAction.LINK, Actor.person(DiscordId.of(ADMIN)), DiscordId.of(ALICE), ALICE_MC, null));

        final AuditEntry entry = directory.recent(10).getFirst();

        assertNotNull(entry.id());
        assertNotNull(entry.occurred());
        assertEquals(JournalAction.LINK, entry.action());
        assertEquals(Actor.person(DiscordId.of(ADMIN)), entry.actor());
        assertEquals(DiscordId.of(ALICE), entry.subject());
        assertEquals(ALICE_MC, entry.mcUuid());
        assertEquals(directory.entry(entry.id()).orElseThrow(), entry, "one entry reads as the list reads it");
        assertTrue(directory.entry(UUID.randomUUID()).isEmpty());
    }

    @Test
    void theNullableColumnsComeBackNull() {
        directory.record(AuditLine.of(JournalAction.START_GAME, Actor.STEWARD, null));

        final AuditEntry entry = directory.recent(10).getFirst();

        assertEquals(Actor.STEWARD, entry.actor(), "Steward acting on its own is an actor of its own");
        assertNull(entry.subject());
        assertNull(entry.mcUuid());
        assertEquals(Map.of(), entry.line().args(), "a line with nothing to add carries no values, never null");
    }

    @Test
    void theJournalIsNewestFirstAndTheLimitIsHonoured() {
        seedFourEntries();

        assertEquals(List.of("alice-revoke", "alice-grant", "bob-link", "alice-link"), labels(directory.recent(10)));
        assertEquals(List.of("alice-revoke"), labels(directory.recent(1)));
        assertEquals(List.of("alice-revoke", "alice-grant"), labels(directory.recent(2)));
    }

    @Test
    void aLimitBelowOneIsClampedRatherThanRejected() {
        seedFourEntries();

        assertEquals(List.of("alice-revoke"), labels(directory.recent(0)));
        assertEquals(List.of("alice-revoke"), labels(directory.recent(-5)));
    }

    @Test
    void anEmptyJournalIsAnEmptyListAndNotAFailure() {
        assertTrue(directory.recent(10).isEmpty());
    }

    @Test
    void searchByActionOnly() {
        seedFourEntries();

        assertEquals(
                List.of("bob-link", "alice-link"),
                labels(directory.search(new JournalFilter("LINK", null, null, null), 10)));
    }

    @Test
    void searchBySubjectOnly() {
        seedFourEntries();

        assertEquals(
                List.of("alice-revoke", "alice-grant", "alice-link"),
                labels(directory.search(new JournalFilter(null, ALICE, null, null), 10)));
    }

    @Test
    void searchByBoth() {
        seedFourEntries();

        assertEquals(List.of("alice-link"), labels(directory.search(new JournalFilter("LINK", ALICE, null, null), 10)));
    }

    @Test
    void searchByNeitherIsTheWholeJournal() {
        seedFourEntries();

        assertEquals(
                labels(directory.recent(10)),
                labels(directory.search(JournalFilter.ANY, 10)),
                "no filter must mean exactly what recent means");
    }

    @Test
    void aBlankFilterCountsAsNoFilter() {
        seedFourEntries();

        assertEquals(
                labels(directory.recent(10)),
                labels(directory.search(new JournalFilter("", "   ", null, null), 10)),
                "an empty search box and an absent one are the same intention");
        assertEquals(
                List.of("bob-link", "alice-link"),
                labels(directory.search(new JournalFilter("LINK", "  ", null, null), 10)));
    }

    @Test
    void searchMatchesExactlyAndNotPartially() {
        seedFourEntries();

        assertTrue(
                directory.search(new JournalFilter("LIN", null, null, null), 10).isEmpty(), "a prefix is not a match");
        assertTrue(
                directory
                        .search(new JournalFilter("link", null, null, null), 10)
                        .isEmpty(),
                "and neither is the wrong case");
        assertTrue(directory
                .search(new JournalFilter(null, ALICE.substring(0, 8), null, null), 10)
                .isEmpty());
    }

    @Test
    void theSearchLimitIsHonoured() {
        seedFourEntries();

        assertEquals(List.of("alice-revoke"), labels(directory.search(new JournalFilter(null, ALICE, null, null), 1)));
        assertEquals(1, directory.search(JournalFilter.ANY, 0).size(), "clamped, like recent");
    }

    @Test
    void theJournalFiltersByThePaymentReferenceAndTheDaysOfAGrantOrABooking() {
        final Actor admin = Actor.person(DiscordId.of(ADMIN));
        final UUID abcd = payment("NT-ABCDEF", ALICE, 30, null, "MANUAL");
        final UUID efgh = payment("NT-EFGHIJ", BOB, 7, null, "MANUAL");
        directory.record(AuditLine.about(
                JournalAction.SETTLE,
                admin,
                DiscordId.of(ALICE),
                new Settled(abcd, "NT-ABCDEF", PaymentMatch.MANUAL, 30, 30, null, Money.euroCents(0))));
        directory.record(AuditLine.about(
                JournalAction.SETTLE,
                admin,
                DiscordId.of(BOB),
                new Settled(efgh, "NT-EFGHIJ", PaymentMatch.MANUAL, 7, 7, null, Money.euroCents(0))));
        directory.record(
                AuditLine.about(JournalAction.GRANT_ACCESS, admin, DiscordId.of(BOB), new AccessGranted(90, UNTIL)));
        directory.record(AuditLine.about(JournalAction.LINK, admin, DiscordId.of(BOB), null));

        assertEquals(
                List.of(DiscordId.of(ALICE)),
                directory.search(new JournalFilter(null, null, "NT-ABCDEF", null), 10).stream()
                        .map(AuditEntry::subject)
                        .toList(),
                "the reference matches its one booking");
        assertTrue(
                directory
                        .search(new JournalFilter(null, null, "NT-AB", null), 10)
                        .isEmpty(),
                "and exactly");
        assertEquals(
                List.of(JournalAction.GRANT_ACCESS, JournalAction.SETTLE),
                actions(directory.search(new JournalFilter(null, null, null, 30), 10)),
                "at least 30 days: the grant of 90 and the booking of 30, not the one of 7 nor the link");
        assertEquals(
                List.of(JournalAction.SETTLE),
                actions(directory.search(new JournalFilter("SETTLE", null, null, 8), 10)),
                "the fields combine with the action");
    }

    @Test
    void theBotIsAnActorOfItsOwn() {
        directory.record(AuditLine.about(JournalAction.LINK, Actor.BOT, DiscordId.of(ALICE), null));

        assertEquals(Actor.BOT, directory.recent(1).getFirst().actor());
    }

    @Test
    void theTableRefusesAnActorThatIsHalfAPersonAnActionNotListedAndABookingWithoutItsPayment() {
        assertThrows(
                IllegalStateException.class,
                () -> execute("INSERT INTO journal_entry (action, actor_kind, actor_id) VALUES ('LINK', 'STEWARD', '"
                        + ADMIN + "')"));
        assertThrows(
                IllegalStateException.class,
                () -> execute("INSERT INTO journal_entry (action, actor_kind) VALUES ('LINK', 'PERSON')"));
        assertThrows(
                IllegalStateException.class,
                () -> execute("INSERT INTO journal_entry (action, actor_kind) VALUES ('X', 'STEWARD')"));
        assertThrows(
                IllegalStateException.class,
                () -> execute("INSERT INTO journal_entry (action, actor_kind) VALUES ('SETTLE', 'STEWARD')"));
    }

    /** One detail of every kind, with both sides of each that may omit a value. */
    private static Map<JournalDetail, JournalAction> everyDetail(
            final UUID tab, final UUID byHand, final long update, final long restore, final long removal) {
        final Map<JournalDetail, JournalAction> written = new LinkedHashMap<>();
        written.put(new AccessGranted(30, UNTIL), JournalAction.GRANT_ACCESS);
        written.put(new JournalDetail.AccessRevoked(2), JournalAction.REVOKE_ACCESS);
        written.put(new JournalDetail.PlaytimeSet(Duration.ofMinutes(90)), JournalAction.SET_PLAYTIME);
        written.put(new JournalDetail.Unlinked(true), JournalAction.UNLINK);
        written.put(
                new Settled(tab, "NT-TABTAB", PaymentMatch.TAB, 30, 30, Money.euroCents(300), Money.euroCents(50)),
                JournalAction.SETTLE);
        written.put(
                new Settled(byHand, "NT-HANDHA", PaymentMatch.MANUAL, 7, 7, null, Money.euroCents(0)),
                JournalAction.SETTLE);
        written.put(
                new JournalDetail.PhaseSet(SeasonPhase.PRE_EVENT, SeasonPhase.SMP, "the opening"),
                JournalAction.SET_PHASE);
        written.put(
                new JournalDetail.PhaseSet(SeasonPhase.SMP, SeasonPhase.MAINTENANCE, null), JournalAction.SET_PHASE);
        written.put(new JournalDetail.LaunchSet(UNTIL), JournalAction.SET_LAUNCH);
        written.put(new JournalDetail.LaunchSet(null), JournalAction.SET_LAUNCH);
        written.put(new JournalDetail.SmpStartSet(UNTIL, 3), JournalAction.SET_SMP_START);
        written.put(new JournalDetail.SmpStartSet(null, 0), JournalAction.SET_SMP_START);
        written.put(new JournalDetail.KeyRegistered("YubiKey"), JournalAction.REGISTER_KEY);
        written.put(new JournalDetail.KeyHeld("YubiKey", true, Duration.ofMinutes(15)), JournalAction.HELD_KEY);
        written.put(new JournalDetail.KeyRenamed("Laptop"), JournalAction.RENAME_KEY);
        written.put(new JournalDetail.KeyRemoved("Laptop", 1), JournalAction.REMOVE_KEY);
        written.put(new JournalDetail.AuthenticatorUsed(Duration.ofHours(1)), JournalAction.USED_AUTHENTICATOR);
        written.put(new JournalDetail.AuthenticatorRemoved(2), JournalAction.REMOVE_AUTHENTICATOR);
        written.put(new JournalDetail.FactorsForgotten(2, true, 3), JournalAction.FORGET_FACTORS);
        written.put(new JournalDetail.PushTested(AlertType.DISK), JournalAction.WEB_PUSH_TEST);
        written.put(
                new JournalDetail.AlertPreferenceSet(AlertType.RUN, AlertChannel.DISCORD, false),
                JournalAction.SET_ALERT_PREFERENCE);
        written.put(new JournalDetail.ConsoleTyped("smp", "chunky start"), JournalAction.CONSOLE);
        written.put(new JournalDetail.SettingsSaved("smp", "motd"), JournalAction.SAVE_SETTINGS);
        written.put(new JournalDetail.MessagesSaved("player"), JournalAction.SAVE_MESSAGES);
        written.put(new JournalDetail.PluginAdded("smp", "chunky"), JournalAction.ADD_PLUGIN);
        written.put(new JournalDetail.PluginRemoved(removal, "smp", "chunky"), JournalAction.REMOVE_PLUGIN);
        written.put(
                new JournalDetail.RunAsked(update, UpdateKind.UPDATE, List.of("smp", "proxy")),
                JournalAction.REQUEST_RUN);
        written.put(new JournalDetail.RestoreAsked(restore, "nordtal-1.dump"), JournalAction.REQUEST_RUN);
        written.put(new JournalDetail.RunCancelled(update, UpdateKind.UPDATE), JournalAction.CANCEL_RUN);
        written.put(new JournalDetail.BackupDownloaded("nordtal-1.dump", 3_500_000L), JournalAction.DOWNLOAD_BACKUP);
        written.put(new JournalDetail.BackupDownloaded("nordtal-2.dump", null), JournalAction.DOWNLOAD_BACKUP);
        written.put(new JournalDetail.Announced(List.of("en", "de")), JournalAction.ANNOUNCE);
        written.put(new JournalDetail.ObjectiveCompleted("first-diamond"), JournalAction.COMPLETE_OBJECTIVE);
        written.put(new JournalDetail.MilestoneUnlocked("nether"), JournalAction.UNLOCK_MILESTONE);
        written.put(new JournalDetail.AdminRevoked(List.of(BOB, ALICE)), JournalAction.REVOKE_ADMIN);
        written.put(new JournalDetail.GrantUnanchored(UNTIL), JournalAction.UNANCHORED_GRANT);

        return written;
    }

    /** Three entries about Alice and one about Bob, two of which share an action. */
    private void seedFourEntries() {
        final Actor admin = Actor.person(DiscordId.of(ADMIN));
        entry(JournalAction.LINK, Actor.STEWARD, ALICE, "-4 minutes");
        entry(JournalAction.LINK, Actor.STEWARD, BOB, "-3 minutes");
        entry(JournalAction.GRANT_ACCESS, admin, ALICE, "-2 minutes");
        entry(JournalAction.REVOKE_ACCESS, admin, ALICE, "-1 minutes");
    }

    /** Writes an entry without values and moves it back in time, so the order does not depend on the clock. */
    private void entry(final JournalAction action, final Actor actor, final String subject, final String ago) {
        directory.record(AuditLine.about(action, actor, DiscordId.of(subject), null));
        execute("UPDATE journal_entry SET occurred = now() + interval '" + ago + "' WHERE occurred = now()"
                + " OR occurred > now() - interval '1 minute' AND action = '" + action + "' AND subject = '" + subject
                + "'");
    }

    /** Names an entry by whom it concerns and the last word of its action, such as {@code alice-grant}. */
    private static List<String> labels(final List<AuditEntry> entries) {
        return entries.stream()
                .map(entry -> (ALICE.equals(entry.subject().value()) ? "alice" : "bob") + "-"
                        + entry.action().name().toLowerCase(Locale.ROOT).replaceAll("_.*", ""))
                .toList();
    }

    private static List<JournalAction> actions(final List<AuditEntry> entries) {
        return entries.stream().map(AuditEntry::action).toList();
    }

    /** Writes a paid payment request and returns its id. {@code matchedCents} is null for a booking by hand. */
    private static UUID payment(
            final String reference,
            final String person,
            final int days,
            final Integer matchedCents,
            final String matchedBy) {
        return UUID.fromString(strings("""
                        INSERT INTO payment_request (reference, discord_id, days, amount_cents, status, expires, settled,
                                                     matched_cents, matched_by)
                        VALUES ('%s', '%s', %d, 300, 'PAID', now() + interval '1 day', now(), %s, '%s')
                        RETURNING id
                        """.formatted(reference, person, days, matchedCents, matchedBy))
                .getFirst());
    }

    /** Writes a finished run and returns its id. */
    private static long run(final String kind, final String payload) {
        return Long.parseLong(strings("""
                        INSERT INTO steward_inbox (kind, payload, actor_kind, status, finished)
                        VALUES ('%s', '%s', 'HOST', 'DONE', now())
                        RETURNING id
                        """.formatted(kind, payload)).getFirst());
    }

    private static List<String> strings(final String sql) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            final List<String> values = new java.util.ArrayList<>();
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
            throw new IllegalStateException("Test setup statement failed: " + sql, exception);
        }
    }
}
