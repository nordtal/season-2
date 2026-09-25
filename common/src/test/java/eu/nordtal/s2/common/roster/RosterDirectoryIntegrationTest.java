package eu.nordtal.s2.common.roster;

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
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Exercises {@link RosterDirectory} against a real PostgreSQL instance running the real migrations.
 * <p>
 * Nothing here can be done in memory. The whole point of {@code people} is one statement with two
 * {@code LEFT JOIN}s and a {@code LATERAL} aggregate evaluated against PostgreSQL's own clock -
 * {@code bool_or(...)} over no rows, {@code now()} inside the aggregate and the difference between
 * a revoked grant and an absent one have no in-JVM stand-in. Testcontainers is driven by hand from
 * {@link BeforeAll} because the {@code org.testcontainers:junit-jupiter} extension is built against
 * JUnit 5 and this repo is on the JUnit 6 BOM.
 * </p>
 * <p>
 * These tests <b>skip themselves</b> when no Docker daemon is reachable. A green build on a machine
 * without Docker proves nothing about any of this.
 * </p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RosterDirectoryIntegrationTest {

    private static final String ALICE = "100000000000000001";
    private static final String BOB = "100000000000000002";
    private static final String CAROL = "100000000000000003";

    private static final UUID ALICE_MC = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static PostgreSQLContainer<?> postgres;
    private static PGSimpleDataSource dataSource;

    private RosterDirectory directory;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "No Docker daemon reachable - skipping the PostgreSQL-backed roster tests");

        postgres = new PostgreSQLContainer<>("postgres:17-alpine")
                .withDatabaseName("roster")
                .withUsername("roster")
                .withPassword("roster");
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
        // TRUNCATE ... CASCADE rather than dropping the schema: it keeps the migration applied once
        // per class while every test still starts from an empty database.
        execute("TRUNCATE TABLE access_grant, account_link, link_code, payment_request, audit_log, "
                + "player_playtime, discord_user CASCADE");
        directory = RosterDirectory.using(dataSource);
    }

    // ---------------------------------------------------------------- people

    @Test
    void somebodyWithNoLinkAndNoGrantIsListedWithNulls() {
        person(ALICE);

        final List<Person> people = directory.people(10);

        assertEquals(1, people.size(), "a person with nothing attached must still be in the list");
        final Person alice = people.getFirst();
        assertEquals(ALICE, alice.discordId());
        assertEquals("MEMBER", alice.memberState());
        assertEquals("en", alice.locale());
        assertFalse(alice.donor());
        assertFalse(alice.admin());
        assertNotNull(alice.updated());
        assertNull(alice.minecraftUuid(), "no account_link row means no UUID");
        assertNull(alice.linked());
        assertNull(alice.accessUntil(), "never a grant means nothing to show");
        assertFalse(alice.accessActive());
    }

    /**
     * steward/119: the roster prints play time, because the prestige tier is derived from it and
     * there is nothing else to look at. Null rather than zero for somebody with no row - the proxy
     * writes one on the first flush, and "has never been online" is not "has been online for no
     * time at all".
     */
    @Test
    void thePlayTimeRidesAlongOnTheSameRow() {
        person(ALICE);
        person(BOB);
        execute("INSERT INTO player_playtime (discord_id, seconds) VALUES ('" + ALICE + "', 7200)");

        final List<Person> people = directory.people(10);
        final Person alice = people.stream()
                .filter(person -> person.discordId().equals(ALICE)).findFirst().orElseThrow();
        final Person bob = people.stream()
                .filter(person -> person.discordId().equals(BOB)).findFirst().orElseThrow();

        assertEquals(7200L, alice.playtimeSeconds());
        assertNull(bob.playtimeSeconds(), "no player_playtime row is not a play time of zero");
    }

    @Test
    void theLinkAndTheFlagsRideAlongOnTheSameRow() {
        person(ALICE);
        execute("UPDATE discord_user SET donor = true, admin = true, admin_granted_at = now(), locale = 'de', "
                + "member_state = 'BANNED' WHERE discord_id = '" + ALICE + "'");
        execute("INSERT INTO account_link (discord_id, mc_uuid) VALUES ('"
                + ALICE + "', '" + ALICE_MC + "')");

        final Person alice = directory.people(10).getFirst();

        assertEquals(ALICE_MC, alice.minecraftUuid());
        assertNotNull(alice.linked());
        assertTrue(alice.donor());
        assertTrue(alice.admin());
        assertEquals("de", alice.locale());
        assertEquals("BANNED", alice.memberState());
    }

    @Test
    void aGrantCoveringNowIsActive() {
        person(ALICE);
        grant(ALICE, "-1 hours", "+47 hours", false);

        final Person alice = directory.people(10).getFirst();

        assertTrue(alice.accessActive(), "a live grant is active");
        assertNotNull(alice.accessUntil());
        assertTrue(alice.accessUntil().isAfter(Instant.now()));
    }

    @Test
    void aGrantThatExpiredYesterdayIsNotActive() {
        person(ALICE);
        grant(ALICE, "-31 days", "-1 days", false);

        final Person alice = directory.people(10).getFirst();

        assertFalse(alice.accessActive(), "a window that has run out is not active");
        assertNotNull(alice.accessUntil(), "but it is still the latest end on record");
        assertTrue(alice.accessUntil().isBefore(Instant.now()));
    }

    @Test
    void aRevokedGrantIsNotActiveButKeepsItsEnd() {
        person(ALICE);
        // Revoked inside its own window: the login decision has to say no while the list still
        // shows when the period it took away would have ended.
        grant(ALICE, "-1 days", "+29 days", true);

        final Person alice = directory.people(10).getFirst();

        assertFalse(alice.accessActive(), "a revoked grant never counts, not even inside its window");
        assertNotNull(alice.accessUntil(), "a revoked person is not the same as one who never paid");
        assertTrue(alice.accessUntil().isAfter(Instant.now()));
    }

    @Test
    void aLiveGrantBesideARevokedOneStillCounts() {
        person(ALICE);
        grant(ALICE, "-10 days", "-5 days", true);
        grant(ALICE, "-1 hours", "+47 hours", false);

        final Person alice = directory.people(10).getFirst();

        assertTrue(alice.accessActive());
    }

    @Test
    void accessUntilIsTheLatestEndAndNotTheFirstFound() {
        person(ALICE);
        grant(ALICE, "-60 days", "-30 days", false);
        grant(ALICE, "-30 days", "+30 days", false);

        final Person alice = directory.people(10).getFirst();

        assertTrue(alice.accessUntil().isAfter(Instant.now().plusSeconds(25 * 24 * 3600)),
                "max(valid_until), not whichever row the planner reached first: " + alice.accessUntil());
    }

    @Test
    void peopleComeBackNewestChangeFirstAndTheLimitIsHonoured() {
        person(ALICE);
        person(BOB);
        person(CAROL);
        touched(ALICE, "-3 days");
        touched(BOB, "-1 days");
        touched(CAROL, "-2 days");

        assertEquals(List.of(BOB, CAROL, ALICE), ids(directory.people(10)), "newest change first");
        assertEquals(List.of(BOB), ids(directory.people(1)), "the limit is a limit");
        assertEquals(List.of(BOB, CAROL), ids(directory.people(2)));
    }

    @Test
    void aLimitBelowOneIsClampedRatherThanRejected() {
        person(ALICE);

        assertEquals(1, directory.people(0).size(), "0 would be an empty list that looks like an empty database");
        assertEquals(1, directory.people(-5).size());
    }

    @Test
    void theDiscordAndMinecraftProfileCacheRideAlongToo() {
        // steward/44 added six columns - three on discord_user, two on account_link, all nullable -
        // caching what discord-bot and the proxy last observed. steward/45's identity display
        // needs them in the same statement people() already is, for the reason the class comment
        // gives: a few hundred round trips for a page nobody scrolls to the end of.
        person(ALICE);
        execute("INSERT INTO account_link (discord_id, mc_uuid) VALUES ('"
                + ALICE + "', '" + ALICE_MC + "')");
        execute("UPDATE discord_user SET discord_username = 'alice#0', "
                + "discord_username_updated = now(), discord_display_name = 'Ally', "
                + "discord_display_name_updated = now(), "
                + "discord_avatar_url = 'https://cdn.discordapp.com/a.png', "
                + "discord_avatar_url_updated = now() WHERE discord_id = '" + ALICE + "'");
        execute("UPDATE account_link SET mc_name = 'AliceMC', mc_name_updated = now() "
                + "WHERE discord_id = '" + ALICE + "'");

        final Person alice = directory.people(10).getFirst();

        assertEquals("alice#0", alice.discordUsername());
        assertNotNull(alice.discordUsernameUpdated());
        assertEquals("Ally", alice.discordDisplayName());
        assertNotNull(alice.discordDisplayNameUpdated());
        assertEquals("https://cdn.discordapp.com/a.png", alice.discordAvatarUrl());
        assertNotNull(alice.discordAvatarUrlUpdated());
        assertEquals("AliceMC", alice.mcName());
        assertNotNull(alice.mcNameUpdated());
    }

    @Test
    void aPersonNobodyHasEverMirroredAProfileOntoReadsAllSixColumnsAsNull() {
        person(BOB);

        final Person bob = directory.people(10).getFirst();

        assertNull(bob.discordUsername());
        assertNull(bob.discordUsernameUpdated());
        assertNull(bob.discordDisplayName());
        assertNull(bob.discordDisplayNameUpdated());
        assertNull(bob.discordAvatarUrl());
        assertNull(bob.discordAvatarUrlUpdated());
        assertNull(bob.mcName());
        assertNull(bob.mcNameUpdated());
    }

    @Test
    void oneRowPerPersonEvenWithSeveralGrants() {
        person(ALICE);
        grant(ALICE, "-60 days", "-30 days", false);
        grant(ALICE, "-30 days", "-10 days", true);
        grant(ALICE, "-1 hours", "+47 hours", false);

        assertEquals(1, directory.people(10).size(),
                "the lateral must not multiply the person out once per grant");
    }

    @Test
    void personOfIsTheSameRowPeopleWouldPrint() {
        // steward/91: /api/me reads this row by discord id rather than paging the whole roster for
        // one avatar. Same columns, same joins - proven here by comparing it against people().
        person(ALICE);
        execute("INSERT INTO account_link (discord_id, mc_uuid) VALUES ('"
                + ALICE + "', '" + ALICE_MC + "')");
        execute("UPDATE discord_user SET discord_avatar_url = 'https://cdn.discordapp.com/a.png', "
                + "discord_avatar_url_updated = now() WHERE discord_id = '" + ALICE + "'");
        grant(ALICE, "-1 hours", "+47 hours", false);
        person(BOB);

        final Person alice = directory.personOf(ALICE).orElseThrow();

        assertEquals(directory.people(10).stream()
                        .filter(p -> p.discordId().equals(ALICE)).findFirst().orElseThrow(), alice);
        assertEquals("https://cdn.discordapp.com/a.png", alice.discordAvatarUrl());
        assertTrue(alice.accessActive());
    }

    @Test
    void personOfSomebodyUnknownIsEmptyRatherThanAFailure() {
        person(ALICE);

        assertTrue(directory.personOf("999999999999999999").isEmpty());
    }

    // ---------------------------------------------------------------- payments

    @Test
    void paymentsComeBackNewestFirstWithEveryColumn() {
        person(ALICE);
        person(BOB);
        execute("""
                INSERT INTO payment_request (reference, discord_id, days, amount_cents,
                                             donation_cents, status, bunq_tab_id, share_url,
                                             created, expires, settled)
                VALUES ('NT-AAAAAA', '%s', 30, 500, 150, 'PAID', 4242, 'https://bunq.me/x',
                        now() - interval '2 days', now() + interval '1 days', now() - interval '2 days')
                """.formatted(ALICE));
        execute("""
                INSERT INTO payment_request (reference, discord_id, days, amount_cents,
                                             donation_cents, status, created, expires)
                VALUES ('NT-BBBBBB', '%s', 60, 900, 0, 'OPEN',
                        now() - interval '1 days', now() + interval '1 days')
                """.formatted(BOB));

        final List<Payment> payments = directory.payments(10);

        assertEquals(List.of("NT-BBBBBB", "NT-AAAAAA"), payments.stream().map(Payment::reference).toList());

        final Payment open = payments.getFirst();
        assertEquals(BOB, open.discordId());
        assertEquals(60, open.days());
        assertEquals(900, open.amountCents());
        assertEquals(0, open.donationCents());
        assertEquals("OPEN", open.status());
        assertNull(open.bunqTabId(), "no tab asked for yet - and 0 is a tab id, so null must be null");
        assertNull(open.shareUrl());
        assertNull(open.settled());
        assertNotNull(open.id());
        assertNotNull(open.created());
        assertNotNull(open.expires());

        final Payment paid = payments.get(1);
        assertEquals(ALICE, paid.discordId());
        assertEquals("PAID", paid.status());
        assertEquals(150, paid.donationCents());
        assertEquals(4242L, paid.bunqTabId());
        assertEquals("https://bunq.me/x", paid.shareUrl());
        assertNotNull(paid.settled());
    }

    @Test
    void thePaymentLimitIsHonouredAndClamped() {
        person(ALICE);
        execute("""
                INSERT INTO payment_request (reference, discord_id, days, amount_cents, status, created, expires)
                VALUES ('NT-000001', '%1$s', 30, 500, 'EXPIRED', now() - interval '3 days', now() - interval '2 days'),
                       ('NT-000002', '%1$s', 30, 500, 'EXPIRED', now() - interval '2 days', now() - interval '1 days'),
                       ('NT-000003', '%1$s', 30, 500, 'OPEN',    now() - interval '1 days', now() + interval '1 days')
                """.formatted(ALICE));

        assertEquals(List.of("NT-000003", "NT-000002"),
                directory.payments(2).stream().map(Payment::reference).toList());
        assertEquals(1, directory.payments(0).size());
    }

    // ---------------------------------------------------------------- grantsOf

    @Test
    void grantsOfOnePersonComeBackNewestFirstAndOnlyTheirs() {
        person(ALICE);
        person(BOB);
        grant(ALICE, "-60 days", "-30 days", false);
        grant(ALICE, "-1 hours", "+47 hours", true);
        grant(BOB, "-1 hours", "+47 hours", false);

        final List<Grant> grants = directory.grantsOf(ALICE);

        assertEquals(2, grants.size(), "Bob's grant is not Alice's");
        assertTrue(grants.getFirst().validFrom().isAfter(grants.get(1).validFrom()), "newest first");
        assertNotNull(grants.getFirst().revoked(), "the revoked one is the newest here");
        assertNull(grants.get(1).revoked());
        assertEquals("PURCHASE", grants.getFirst().source());
        assertEquals(ALICE, grants.getFirst().discordId());
        assertNotNull(grants.getFirst().id());
        assertNotNull(grants.getFirst().created());
        assertNull(grants.getFirst().paymentRequestId(), "no payment behind a hand-written grant");
    }

    @Test
    void grantsOfSomebodyUnknownIsEmptyRatherThanAFailure() {
        assertTrue(directory.grantsOf("999999999999999999").isEmpty());
    }

    // ---------------------------------------------------------------- helpers

    private static List<String> ids(final List<Person> people) {
        return people.stream().map(Person::discordId).toList();
    }

    private static void person(final String discordId) {
        execute("INSERT INTO discord_user (discord_id) VALUES ('" + discordId + "')");
    }

    /** Moves a person's {@code updated} column, which is what {@code people} orders by. */
    private static void touched(final String discordId, final String interval) {
        execute("UPDATE discord_user SET updated = now() + interval '" + interval
                + "' WHERE discord_id = '" + discordId + "'");
    }

    /**
     * Writes one {@code access_grant} row with a window stated relative to the database's clock.
     * Deliberately raw SQL and not {@code AccessDirectory#grantAccess}: that method appends onto
     * whatever is already there and anchors on {@code season_phase.smp_start}, so it cannot express
     * "a window that ended yesterday", which is half of what is being tested here.
     */
    private static void grant(final String discordId, final String from, final String until,
                              final boolean revoked) {
        execute("""
                INSERT INTO access_grant (discord_id, valid_from, valid_until, source, revoked)
                VALUES ('%s', now() + interval '%s', now() + interval '%s', 'PURCHASE', %s)
                """.formatted(discordId, from, until, revoked ? "now()" : "NULL"));
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
