package eu.nordtal.s2.database.audit;

import static eu.nordtal.s2.database.AdminTexts.TEXTS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.id.Actor;
import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.database.TestDatabase;
import eu.nordtal.s2.messages.Messages;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Exercises {@link AuditDirectory} against a real PostgreSQL running the real migrations.
 *
 * Only PostgreSQL can say whether it accepts the null-switched predicates; the tests skip themselves without Docker.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuditDirectoryIntegrationTest {

    private static final String ALICE = "100000000000000001";
    private static final String BOB = "100000000000000002";
    private static final String ADMIN = "100000000000000009";

    private static final UUID ALICE_MC = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Messages ADMIN_TEXTS =
            Messages.load(AuditDirectoryIntegrationTest.class.getClassLoader(), "messages/admin");
    /** A line that is a message and says nothing, for rows that test something else. */
    private static final String LINE = "'{\"key\": \"x\", \"args\": {}}'";

    private static DataSource dataSource;

    private AuditDirectory directory;

    @BeforeAll
    static void startDatabase() {
        dataSource = TestDatabase.fresh().dataSource();
    }

    @BeforeEach
    void freshDirectory() {
        execute("TRUNCATE TABLE access_grant, account_link, link_code, payment_request, audit_log, "
                + "player_playtime, discord_user CASCADE");
        directory = AuditDirectory.using(dataSource);
    }

    @Test
    void everyColumnSurvivesTheRoundTrip() {
        entry("-1 minutes", "LINK", ADMIN, ALICE, "'" + ALICE_MC + "'", "'linked by hand'");

        final AuditEntry entry = directory.recent(10).getFirst();

        assertNotNull(entry.id());
        assertNotNull(entry.occurred());
        assertEquals("LINK", entry.action());
        assertEquals(Actor.person(DiscordId.of(ADMIN)), entry.actor());
        assertEquals(DiscordId.of(ALICE), entry.subject());
        assertEquals(ALICE_MC, entry.mcUuid());
        assertEquals("linked by hand", note(entry));
    }

    @Test
    void theNullableColumnsComeBackNull() {
        entry("-1 minutes", "PHASE", null, null, "NULL", "NULL");

        final AuditEntry entry = directory.recent(10).getFirst();

        assertEquals(Actor.STEWARD, entry.actor(), "Steward acting on its own is an actor of its own");
        assertNull(entry.subject());
        assertNull(entry.mcUuid());
        assertEquals(Map.of(), entry.line().args(), "a line with nothing to add carries no values, never null");
    }

    @Test
    void theJournalIsNewestFirstAndTheLimitIsHonoured() {
        entry("-3 minutes", "LINK", null, ALICE, "NULL", "'oldest'");
        entry("-2 minutes", "GRANT_ACCESS", null, ALICE, "NULL", "'middle'");
        entry("-1 minutes", "REVOKE_ACCESS", null, ALICE, "NULL", "'newest'");

        assertEquals(List.of("newest", "middle", "oldest"), details(directory.recent(10)));
        assertEquals(List.of("newest"), details(directory.recent(1)));
        assertEquals(List.of("newest", "middle"), details(directory.recent(2)));
    }

    @Test
    void aLimitBelowOneIsClampedRatherThanRejected() {
        entry("-1 minutes", "LINK", null, ALICE, "NULL", "'only'");

        assertEquals(List.of("only"), details(directory.recent(0)));
        assertEquals(List.of("only"), details(directory.recent(-5)));
    }

    @Test
    void anEmptyJournalIsAnEmptyListAndNotAFailure() {
        assertTrue(directory.recent(10).isEmpty());
    }

    @Test
    void searchByActionOnly() {
        seedFourEntries();

        assertEquals(List.of("bob-link", "alice-link"), details(directory.search("LINK", null, 10)));
    }

    @Test
    void searchBySubjectOnly() {
        seedFourEntries();

        assertEquals(List.of("alice-revoke", "alice-grant", "alice-link"), details(directory.search(null, ALICE, 10)));
    }

    @Test
    void searchByBoth() {
        seedFourEntries();

        assertEquals(List.of("alice-link"), details(directory.search("LINK", ALICE, 10)));
    }

    @Test
    void searchByNeitherIsTheWholeJournal() {
        seedFourEntries();

        assertEquals(
                details(directory.recent(10)),
                details(directory.search(null, null, 10)),
                "no filter must mean exactly what recent means");
    }

    @Test
    void aBlankFilterCountsAsNoFilter() {
        seedFourEntries();

        assertEquals(
                details(directory.recent(10)),
                details(directory.search("", "   ", 10)),
                "an empty search box and an absent one are the same intention");
        assertEquals(List.of("bob-link", "alice-link"), details(directory.search("LINK", "  ", 10)));
    }

    @Test
    void searchMatchesExactlyAndNotPartially() {
        seedFourEntries();

        assertTrue(directory.search("LIN", null, 10).isEmpty(), "a prefix is not a match");
        assertTrue(directory.search("link", null, 10).isEmpty(), "and neither is the wrong case");
        assertTrue(directory.search(null, ALICE.substring(0, 8), 10).isEmpty());
    }

    @Test
    void theSearchLimitIsHonoured() {
        seedFourEntries();

        assertEquals(List.of("alice-revoke"), details(directory.search(null, ALICE, 1)));
        assertEquals(1, directory.search(null, null, 0).size(), "clamped, like recent");
    }

    /** Three entries about Alice, one about Bob, two of which share an action. */
    private static void seedFourEntries() {
        entry("-4 minutes", "LINK", null, ALICE, "'" + ALICE_MC + "'", "'alice-link'");
        entry("-3 minutes", "LINK", null, BOB, "NULL", "'bob-link'");
        entry("-2 minutes", "GRANT_ACCESS", ADMIN, ALICE, "NULL", "'alice-grant'");
        entry("-1 minutes", "REVOKE_ACCESS", ADMIN, ALICE, "NULL", "'alice-revoke'");
    }

    @Test
    void aWrittenLineComesBackAsTheMessageItWas() {
        final var written = TEXTS.journal().grantAccess(30, Instant.parse("2026-11-02T10:00:00Z"));
        directory.record(new AuditLine(
                JournalAction.GRANT_ACCESS, Actor.person(DiscordId.of(ADMIN)), DiscordId.of(ALICE), ALICE_MC, written));

        final AuditEntry entry = directory.recent(1).getFirst();

        assertEquals(Actor.person(DiscordId.of(ADMIN)), entry.actor());
        assertEquals("GRANT_ACCESS", entry.action());
        assertEquals("journal.grant-access", entry.line().key());
        assertEquals(
                "30 days of access, until Nov 2, 2026.",
                ADMIN_TEXTS.format(Locale.ENGLISH, entry.line()),
                "every value comes back of its kind, so the line renders as the one written");
        assertEquals(ADMIN_TEXTS.format(Locale.ENGLISH, written), ADMIN_TEXTS.format(Locale.ENGLISH, entry.line()));
    }

    @Test
    void theTableRefusesAnActorThatIsHalfAPerson() {
        assertThrows(
                IllegalStateException.class,
                () -> execute("INSERT INTO audit_log (action, actor_kind, actor_id, line) VALUES ('X', 'STEWARD', '"
                        + ADMIN + "', " + LINE + ")"));
        assertThrows(
                IllegalStateException.class,
                () -> execute("INSERT INTO audit_log (action, actor_kind, line) VALUES ('X', 'PERSON', " + LINE + ")"));
    }

    @Test
    void theTableRefusesALineThatIsNoMessage() {
        execute("INSERT INTO audit_log (action, actor_kind, line) VALUES ('X', 'STEWARD', " + LINE + ")");
        assertThrows(
                IllegalStateException.class,
                () -> execute(
                        "INSERT INTO audit_log (action, actor_kind, line) VALUES ('X', 'STEWARD', '\"a sentence\"')"));
        assertThrows(
                IllegalStateException.class,
                () -> execute(
                        "INSERT INTO audit_log (action, actor_kind, line) VALUES ('X', 'STEWARD', '{\"key\": \"x\"}')"));
        assertThrows(
                IllegalStateException.class,
                () -> execute("INSERT INTO audit_log (action, actor_kind) VALUES ('X', 'STEWARD')"));
    }

    private static List<String> details(final List<AuditEntry> entries) {
        return entries.stream().map(AuditDirectoryIntegrationTest::note).toList();
    }

    private static String note(final AuditEntry entry) {
        return (String) entry.line().args().get("detail");
    }

    /** Writes one {@code audit_log} row of written words; {@code mcUuid} and {@code note} are quoted or NULL. */
    private static void entry(
            final String occurred,
            final String action,
            final String actor,
            final String subject,
            final String mcUuid,
            final String note) {
        execute("""
                INSERT INTO audit_log (occurred, action, actor_kind, actor_id, subject, mc_uuid, line)
                VALUES (now() + interval '%s', '%s', '%s', %s, %s, %s, jsonb_build_object('key', 'journal.written',
                        'args', %s))
                """.formatted(
                occurred,
                action,
                actor == null ? "STEWARD" : "PERSON",
                quoted(actor),
                quoted(subject),
                mcUuid,
                "NULL".equals(note)
                        ? "'{}'::jsonb"
                        : "jsonb_build_object('detail', jsonb_build_object('kind', 'text', 'value', " + note + "))"));
    }

    private static String quoted(final String value) {
        return value == null ? "NULL" : "'" + value + "'";
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
