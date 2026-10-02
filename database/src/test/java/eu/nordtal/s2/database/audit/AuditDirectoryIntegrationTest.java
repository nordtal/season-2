package eu.nordtal.s2.database.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.id.Actor;
import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.database.TestDatabase;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
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
        assertEquals(0, entry.facts().size(), "a line with nothing to add carries an empty object, never null");
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
    void aWrittenLineComesBackWithItsTypedValues() {
        directory.record(new AuditLine(
                "GRANT_ACCESS",
                Actor.person(DiscordId.of(ADMIN)),
                DiscordId.of(ALICE),
                ALICE_MC,
                Map.of("days", 30, "donor", true, "reference", "AB12CD")));

        final AuditEntry entry = directory.recent(1).getFirst();

        assertEquals(Actor.person(DiscordId.of(ADMIN)), entry.actor());
        assertEquals(30, entry.facts().get("days").getAsInt());
        assertTrue(entry.facts().get("donor").getAsBoolean());
        assertEquals("AB12CD", entry.facts().get("reference").getAsString());
    }

    @Test
    void theTableRefusesAnActorThatIsHalfAPerson() {
        assertThrows(
                IllegalStateException.class,
                () -> execute("INSERT INTO audit_log (action, actor_kind, actor_id) VALUES ('X', 'STEWARD', '" + ADMIN
                        + "')"));
        assertThrows(
                IllegalStateException.class,
                () -> execute("INSERT INTO audit_log (action, actor_kind) VALUES ('X', 'PERSON')"));
        assertThrows(
                IllegalStateException.class,
                () -> execute(
                        "INSERT INTO audit_log (action, actor_kind, facts) VALUES ('X', 'STEWARD', '\"a sentence\"')"));
    }

    private static List<String> details(final List<AuditEntry> entries) {
        return entries.stream().map(AuditDirectoryIntegrationTest::note).toList();
    }

    private static String note(final AuditEntry entry) {
        return entry.facts().get("note").getAsString();
    }

    /** Writes one {@code audit_log} row; {@code mcUuid} and {@code note} arrive quoted or as {@code NULL}. */
    private static void entry(
            final String occurred,
            final String action,
            final String actor,
            final String subject,
            final String mcUuid,
            final String note) {
        execute("""
                INSERT INTO audit_log (occurred, action, actor_kind, actor_id, subject, mc_uuid, facts)
                VALUES (now() + interval '%s', '%s', '%s', %s, %s, %s, jsonb_strip_nulls(jsonb_build_object('note', %s)))
                """.formatted(
                        occurred,
                        action,
                        actor == null ? "STEWARD" : "PERSON",
                        quoted(actor),
                        quoted(subject),
                        mcUuid,
                        "NULL".equals(note) ? "cast(NULL AS text)" : note));
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
