package eu.nordtal.s2.common.audit;

import eu.nordtal.s2.common.access.AccessSchema;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Exercises {@link AuditDirectory} against a real PostgreSQL instance running the real migrations.
 * <p>
 * The filter is the reason this is an integration test and not a unit one: {@code search} is a
 * single statement whose predicates switch themselves off when a parameter is null, and whether
 * PostgreSQL will even accept {@code cast(:action AS varchar) IS NULL} is not a question a mock can
 * answer. Testcontainers is driven by hand from {@link BeforeAll} because the
 * {@code org.testcontainers:junit-jupiter} extension is built against JUnit 5 and this repo is on
 * the JUnit 6 BOM.
 * </p>
 * <p>
 * These tests <b>skip themselves</b> when no Docker daemon is reachable.
 * </p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuditDirectoryIntegrationTest {

    private static final String ALICE = "100000000000000001";
    private static final String BOB = "100000000000000002";
    private static final String ADMIN = "100000000000000009";

    private static final UUID ALICE_MC = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static PostgreSQLContainer<?> postgres;
    private static PGSimpleDataSource dataSource;

    private AuditDirectory directory;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "No Docker daemon reachable - skipping the PostgreSQL-backed audit tests");

        postgres = new PostgreSQLContainer<>("postgres:17-alpine")
                .withDatabaseName("audit")
                .withUsername("audit")
                .withPassword("audit");
        postgres.start();

        dataSource = new PGSimpleDataSource();
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());

        AccessSchema.migrate(dataSource);
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
            postgres = null;
        }
        dataSource = null;
    }

    @BeforeEach
    void freshDirectory() {
        execute("TRUNCATE TABLE access_grant, account_link, link_code, payment_request, audit_log, "
                + "player_playtime, discord_user CASCADE");
        directory = AuditDirectory.using(dataSource);
    }

    // ---------------------------------------------------------------- recent

    @Test
    void everyColumnSurvivesTheRoundTrip() {
        entry("-1 minutes", "LINK", ADMIN, ALICE, "'" + ALICE_MC + "'", "'linked by hand'");

        final AuditEntry entry = directory.recent(10).getFirst();

        assertNotNull(entry.id());
        assertNotNull(entry.occurred());
        assertEquals("LINK", entry.action());
        assertEquals(ADMIN, entry.actor());
        assertEquals(ALICE, entry.subject());
        assertEquals(ALICE_MC, entry.mcUuid());
        assertEquals("linked by hand", entry.detail());
    }

    @Test
    void theNullableColumnsComeBackNull() {
        entry("-1 minutes", "PHASE", null, null, "NULL", "NULL");

        final AuditEntry entry = directory.recent(10).getFirst();

        assertNull(entry.actor(), "the bot acting on its own has no actor");
        assertNull(entry.subject());
        assertNull(entry.mcUuid());
        assertNull(entry.detail());
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

    // ---------------------------------------------------------------- search

    @Test
    void searchByActionOnly() {
        seedFourEntries();

        assertEquals(List.of("bob-link", "alice-link"), details(directory.search("LINK", null, 10)));
    }

    @Test
    void searchBySubjectOnly() {
        seedFourEntries();

        assertEquals(List.of("alice-revoke", "alice-grant", "alice-link"),
                details(directory.search(null, ALICE, 10)));
    }

    @Test
    void searchByBoth() {
        seedFourEntries();

        assertEquals(List.of("alice-link"), details(directory.search("LINK", ALICE, 10)));
    }

    @Test
    void searchByNeitherIsTheWholeJournal() {
        seedFourEntries();

        assertEquals(details(directory.recent(10)), details(directory.search(null, null, 10)),
                "no filter must mean exactly what recent means");
    }

    @Test
    void aBlankFilterCountsAsNoFilter() {
        seedFourEntries();

        assertEquals(details(directory.recent(10)), details(directory.search("", "   ", 10)),
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

    // ---------------------------------------------------------------- helpers

    /** Three entries about Alice, one about Bob, two of which share an action. */
    private static void seedFourEntries() {
        entry("-4 minutes", "LINK", null, ALICE, "'" + ALICE_MC + "'", "'alice-link'");
        entry("-3 minutes", "LINK", null, BOB, "NULL", "'bob-link'");
        entry("-2 minutes", "GRANT_ACCESS", ADMIN, ALICE, "NULL", "'alice-grant'");
        entry("-1 minutes", "REVOKE_ACCESS", ADMIN, ALICE, "NULL", "'alice-revoke'");
    }

    private static List<String> details(final List<AuditEntry> entries) {
        return entries.stream().map(AuditEntry::detail).toList();
    }

    /**
     * Writes one {@code audit_log} row. {@code mcUuid} and {@code detail} arrive already quoted
     * because both are nullable and {@code NULL} is not a string literal; {@code actor} and
     * {@code subject} are quoted here because a Discord id always is one when it is present.
     *
     * <p>{@code occurred} is stated relative to the database's clock rather than left to the column
     * default: several rows written in one transaction would otherwise share it exactly, and the
     * order these tests assert on would be decided by the {@code id} tiebreak alone.
     */
    private static void entry(final String occurred, final String action, final String actor,
                              final String subject, final String mcUuid, final String detail) {
        execute("""
                INSERT INTO audit_log (occurred, action, actor, subject, mc_uuid, detail)
                VALUES (now() + interval '%s', '%s', %s, %s, %s, %s)
                """.formatted(occurred, action,
                quoted(actor), quoted(subject), mcUuid, detail));
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
