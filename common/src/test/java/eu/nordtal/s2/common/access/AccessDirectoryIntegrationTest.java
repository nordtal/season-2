package eu.nordtal.s2.common.access;

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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Exercises {@link AccessDirectory} against a real PostgreSQL instance running the real migration.
 * <p>
 * Nothing here can be done in memory: the append rule, the expiry comparison and the double-book
 * guard are all evaluated by PostgreSQL - {@code GREATEST(now(), ...)}, {@code make_interval} and
 * a partial unique index have no in-JVM stand-in. Testcontainers is driven by hand from
 * {@link BeforeAll} because the {@code org.testcontainers:junit-jupiter} extension is built
 * against JUnit 5 and this repo is on the JUnit 6 BOM.
 * </p>
 * <p>
 * These tests <b>skip themselves</b> when no Docker daemon is reachable. A green build on a
 * machine without Docker proves nothing about any of this.
 * </p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccessDirectoryIntegrationTest {

    private static final String DISCORD_ID = "100000000000000001";
    private static final UUID MC_UUID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private static PostgreSQLContainer<?> postgres;
    private static PGSimpleDataSource dataSource;

    private AccessDirectory directory;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "No Docker daemon reachable - skipping the PostgreSQL-backed access tests");

        postgres = new PostgreSQLContainer<>("postgres:17-alpine")
                .withDatabaseName("access")
                .withUsername("access")
                .withPassword("access");
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
        // TRUNCATE ... CASCADE rather than dropping the schema: it keeps the migration applied
        // once per class while every test still starts from an empty database.
        execute("TRUNCATE TABLE access_grant, account_link, link_code, payment_request, audit_log, discord_user CASCADE");
        directory = AccessDirectory.using(dataSource);
    }

    // ---------------------------------------------------------------- appending

    @Test
    void grantingWithNoAccessRunningStartsNow() {
        final AccessGrant grant = directory.grantAccess(DISCORD_ID, 30, AccessSource.PURCHASE, null);

        assertNotNull(grant.id());
        assertEquals(AccessSource.PURCHASE, grant.source());
        assertNull(grant.paymentRequestId());
        assertWithinSeconds(Instant.now(), grant.validFrom(), 5);
        assertDaysApart(30, grant.validFrom(), grant.validUntil());
    }

    @Test
    void grantingWhileAccessIsRunningAppendsInsteadOfRestarting() {
        // 30 days bought, then 18 of them used up: 12 days left.
        directory.grantAccess(DISCORD_ID, 30, AccessSource.PURCHASE, null);
        execute("""
                UPDATE access_grant
                SET valid_from = now() - interval '432 hours',
                    valid_until = now() + interval '288 hours'
                WHERE discord_id = '%s'
                """.formatted(DISCORD_ID));

        final AccessGrant appended = directory.grantAccess(DISCORD_ID, 30, AccessSource.PURCHASE, null);

        // The new period starts where the running one ends, not now: 12 + 30 = 42 days out.
        assertWithinSeconds(Instant.now().plus(Duration.ofDays(12)), appended.validFrom(), 60);
        assertWithinSeconds(Instant.now().plus(Duration.ofDays(42)), appended.validUntil(), 60);

        final AccessState state = linkedState();
        assertTrue(state.accessActive());
        assertWithinSeconds(Instant.now().plus(Duration.ofDays(42)), state.accessValidUntil(), 60);
    }

    @Test
    void grantingAfterAccessLapsedStartsNowRatherThanInThePast() {
        directory.grantAccess(DISCORD_ID, 30, AccessSource.PURCHASE, null);
        execute("""
                UPDATE access_grant
                SET valid_from = now() - interval '1440 hours',
                    valid_until = now() - interval '720 hours'
                WHERE discord_id = '%s'
                """.formatted(DISCORD_ID));

        final AccessGrant grant = directory.grantAccess(DISCORD_ID, 30, AccessSource.PURCHASE, null);

        assertWithinSeconds(Instant.now(), grant.validFrom(), 5);
        assertDaysApart(30, grant.validFrom(), grant.validUntil());
    }

    @Test
    void aDayIsExactlyTwentyFourHoursEvenAcrossADaylightSavingChange() {
        // Regression test. The first version of grantAccess used make_interval(days => :days),
        // which on a timestamptz is calendar arithmetic evaluated in the *session's* time zone -
        // and the PostgreSQL JDBC driver takes that time zone from the JVM's default. A period
        // spanning the end of European summer time therefore came out an hour long, and the same
        // purchase would have differed between the bot's host and the proxy's host.
        final AccessGrant grant = directory.grantAccess(DISCORD_ID, 365, AccessSource.ADMIN, null);

        assertEquals(Duration.ofHours(365 * 24), Duration.between(grant.validFrom(), grant.validUntil()));
    }

    // ---------------------------------------------------------------- expiry and revocation

    @Test
    void aGrantThatEndedOneSecondAgoIsNotActive() {
        directory.link(DISCORD_ID, MC_UUID);
        directory.grantAccess(DISCORD_ID, 30, AccessSource.PURCHASE, null);
        execute("""
                UPDATE access_grant
                SET valid_from = now() - interval '720 hours',
                    valid_until = now() - interval '1 second'
                WHERE discord_id = '%s'
                """.formatted(DISCORD_ID));

        final AccessState state = directory.accessState(MC_UUID);

        assertTrue(state.linked(), "the account is still linked, it just has no access left");
        assertFalse(state.accessActive());
        assertNull(state.accessValidUntil());
        assertFalse(state.mayJoin());
    }

    @Test
    void aGrantEndingOneSecondFromNowIsStillActive() {
        directory.link(DISCORD_ID, MC_UUID);
        directory.grantAccess(DISCORD_ID, 30, AccessSource.PURCHASE, null);
        execute("""
                UPDATE access_grant
                SET valid_from = now() - interval '720 hours',
                    valid_until = now() + interval '1 second'
                WHERE discord_id = '%s'
                """.formatted(DISCORD_ID));

        assertTrue(directory.accessState(MC_UUID).accessActive());
    }

    @Test
    void aRevokedGrantNeverCountsEvenInsideItsWindow() {
        directory.link(DISCORD_ID, MC_UUID);
        directory.grantAccess(DISCORD_ID, 30, AccessSource.PURCHASE, null);
        assertTrue(directory.accessState(MC_UUID).accessActive(), "precondition");

        assertEquals(1, directory.revokeAccess(DISCORD_ID));

        final AccessState state = directory.accessState(MC_UUID);
        assertFalse(state.accessActive(), "the window still covers now, but the grant is revoked");
        assertNull(state.accessValidUntil());
        assertFalse(state.mayJoin());

        final List<AccessGrant> grants = directory.grantsOf(DISCORD_ID);
        assertEquals(1, grants.size(), "revoking marks the row, it does not delete it");
        assertNotNull(grants.getFirst().revoked());
        assertFalse(grants.getFirst().coversAt(Instant.now()));
    }

    @Test
    void revokingTakesTheWholeAppendedChain() {
        directory.grantAccess(DISCORD_ID, 30, AccessSource.PURCHASE, null);
        directory.grantAccess(DISCORD_ID, 30, AccessSource.PURCHASE, null);
        directory.link(DISCORD_ID, MC_UUID);

        assertEquals(2, directory.revokeAccess(DISCORD_ID),
                "a revoke that left the appended tail behind would report access as active later");
        assertFalse(directory.accessState(MC_UUID).accessActive());
    }

    @Test
    void grantingAfterARevokeStartsNowBecauseTheRevokedTailDoesNotCount() {
        directory.grantAccess(DISCORD_ID, 30, AccessSource.PURCHASE, null);
        directory.revokeAccess(DISCORD_ID);

        final AccessGrant grant = directory.grantAccess(DISCORD_ID, 7, AccessSource.ADMIN, null);

        assertWithinSeconds(Instant.now(), grant.validFrom(), 5);
        assertDaysApart(7, grant.validFrom(), grant.validUntil());
    }

    // ---------------------------------------------------------------- the login path

    @Test
    void accessStateOfAnUnknownUuidIsUnlinked() {
        final UUID unknown = UUID.randomUUID();

        final AccessState state = directory.accessState(unknown);

        assertEquals(unknown, state.minecraftAccount());
        assertFalse(state.linked());
        assertNull(state.discordId());
        assertNull(state.memberState());
        assertFalse(state.accessActive());
        assertFalse(state.donor());
        assertEquals(Locale.ENGLISH, state.locale());
        assertFalse(state.mayJoin());
    }

    @Test
    void accessStateOfALinkedAccountWithoutAccess() {
        directory.link(DISCORD_ID, MC_UUID);

        final AccessState state = directory.accessState(MC_UUID);

        assertTrue(state.linked());
        assertEquals(DISCORD_ID, state.discordId());
        assertEquals(MemberState.MEMBER, state.memberState());
        assertFalse(state.accessActive());
        assertFalse(state.mayJoin());
    }

    @Test
    void accessStateOfABannedAccountWithValidAccess() {
        directory.link(DISCORD_ID, MC_UUID);
        directory.grantAccess(DISCORD_ID, 30, AccessSource.PURCHASE, null);
        directory.setMemberState(DISCORD_ID, MemberState.BANNED);

        final AccessState state = directory.accessState(MC_UUID);

        assertEquals(MemberState.BANNED, state.memberState());
        assertTrue(state.accessActive(), "a ban does not pause the paid period, it only blocks the join");
        assertFalse(state.mayJoin());
    }

    @Test
    void accessStateOfALinkedActiveMember() {
        directory.link(DISCORD_ID, MC_UUID);
        directory.setLocale(DISCORD_ID, Locale.GERMAN);
        directory.setDonor(DISCORD_ID, true);
        directory.grantAccess(DISCORD_ID, 60, AccessSource.PURCHASE, null);

        final AccessState state = directory.accessState(MC_UUID);

        assertTrue(state.mayJoin());
        assertTrue(state.donor());
        assertEquals(Locale.GERMAN, state.locale());
        assertWithinSeconds(Instant.now().plus(Duration.ofDays(60)), state.accessValidUntil(), 60);
    }

    // ---------------------------------------------------------------- linking

    @Test
    void theLinkIsOneToOneAndTheDatabaseIsWhatEnforcesIt() {
        assertTrue(directory.link(DISCORD_ID, MC_UUID));

        // Same Discord user, second Minecraft account.
        assertFalse(directory.link(DISCORD_ID, UUID.randomUUID()));
        // Same Minecraft account, second Discord user.
        assertFalse(directory.link("100000000000000002", MC_UUID));

        assertEquals(MC_UUID, directory.linkedMinecraftAccount(DISCORD_ID).orElseThrow());
        assertEquals(DISCORD_ID, directory.linkedDiscordAccount(MC_UUID).orElseThrow());
    }

    @Test
    void unlinkingLeavesTheUserAndTheGrantsBehind() {
        directory.link(DISCORD_ID, MC_UUID);
        directory.grantAccess(DISCORD_ID, 30, AccessSource.PURCHASE, null);

        assertTrue(directory.unlink(DISCORD_ID));
        assertFalse(directory.unlink(DISCORD_ID), "unlinking twice is not an error, it just does nothing");

        assertTrue(directory.linkedMinecraftAccount(DISCORD_ID).isEmpty());
        assertFalse(directory.accessState(MC_UUID).linked());
        assertEquals(1, directory.grantsOf(DISCORD_ID).size(), "paid time survives an unlink");
    }

    // ---------------------------------------------------------------- locale and donor

    @Test
    void localeOfAnUnknownUuidIsEnglishAndNeverThrows() {
        assertEquals(Locale.ENGLISH, directory.locale(UUID.randomUUID()));
        assertEquals(Locale.ENGLISH, directory.locale(null));
    }

    @Test
    void localeFollowsTheLinkedDiscordUser() {
        directory.link(DISCORD_ID, MC_UUID);
        assertEquals(Locale.ENGLISH, directory.locale(MC_UUID), "the column defaults to 'en'");

        directory.setLocale(DISCORD_ID, Locale.GERMANY);
        assertEquals(Locale.GERMAN, directory.locale(MC_UUID),
                "only the language is stored, so de-DE and de-AT are one bundle");
    }

    @Test
    void donorIsFalseForAnUnknownUser() {
        assertFalse(directory.isDonor("999999999999999999"));
    }

    // ---------------------------------------------------------------- the double-booking guard

    @Test
    void oneBunqPaymentCannotSettleTwoRequests() throws SQLException {
        directory.ensureUser(DISCORD_ID);
        insertSettledRequest("NT-AAAAAA", 4242L);

        final SQLException failure = assertThrows(SQLException.class,
                () -> insertSettledRequest("NT-BBBBBB", 4242L));

        assertTrue(failure.getMessage().contains("payment_request_bunq_payment_id_key"),
                "the partial unique index is what refuses the second booking, not application code: "
                        + failure.getMessage());
    }

    @Test
    void unsettledRequestsAreNotConstrainedAgainstEachOther() throws SQLException {
        directory.ensureUser(DISCORD_ID);
        directory.ensureUser("100000000000000002");

        // Two open requests for two people, both with a NULL bunq_payment_id: the unique index is
        // partial, so NULLs do not collide.
        insertOpenRequest(DISCORD_ID, "NT-CCCCCC");
        insertOpenRequest("100000000000000002", "NT-DDDDDD");
    }

    @Test
    void onePersonCannotHoldTwoOpenRequests() throws SQLException {
        directory.ensureUser(DISCORD_ID);
        insertOpenRequest(DISCORD_ID, "NT-EEEEEE");

        final SQLException failure = assertThrows(SQLException.class,
                () -> insertOpenRequest(DISCORD_ID, "NT-FFFFFF"));

        assertTrue(failure.getMessage().contains("payment_request_one_open_per_user_key"),
                "starting a new request has to supersede the old one in the same transaction: "
                        + failure.getMessage());
    }

    @Test
    void onePaymentRequestCannotProduceTwoGrants() throws SQLException {
        directory.ensureUser(DISCORD_ID);
        final UUID requestId = insertSettledRequest("NT-123456", 77L);

        directory.grantAccess(DISCORD_ID, 30, AccessSource.PURCHASE, requestId);

        assertThrows(RuntimeException.class,
                () -> directory.grantAccess(DISCORD_ID, 30, AccessSource.PURCHASE, requestId),
                "access_grant_payment_request_id_key is the second half of the double-booking guard");
        assertEquals(1, directory.grantsOf(DISCORD_ID).size());
    }

    // ---------------------------------------------------------------- helpers

    private AccessState linkedState() {
        directory.link(DISCORD_ID, MC_UUID);
        return directory.accessState(MC_UUID);
    }

    private UUID insertSettledRequest(final String reference, final long bunqPaymentId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     INSERT INTO payment_request
                         (reference, discord_id, days, amount_cents, status, bunq_payment_id, expires, settled)
                     VALUES (?, ?, 30, 300, 'PAID', ?, now() + interval '24 hours', now())
                     RETURNING id
                     """)) {
            statement.setString(1, reference);
            statement.setString(2, DISCORD_ID);
            statement.setLong(3, bunqPaymentId);
            try (var rs = statement.executeQuery()) {
                assertTrue(rs.next());
                return rs.getObject(1, UUID.class);
            }
        }
    }

    private void insertOpenRequest(final String discordId, final String reference) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     INSERT INTO payment_request (reference, discord_id, days, amount_cents, expires)
                     VALUES (?, ?, 30, 300, now() + interval '24 hours')
                     """)) {
            statement.setString(1, reference);
            statement.setString(2, discordId);
            statement.executeUpdate();
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

    private static void assertWithinSeconds(final Instant expected, final Instant actual, final long tolerance) {
        assertNotNull(actual, "expected a timestamp around " + expected + ", got null");
        final long off = Math.abs(Duration.between(expected, actual).toSeconds());
        assertTrue(off <= tolerance,
                "expected " + actual + " to be within " + tolerance + "s of " + expected + ", was off by " + off + "s");
    }

    private static void assertDaysApart(final long days, final Instant from, final Instant to) {
        final long actual = Duration.between(from, to).toDays();
        assertEquals(days, actual, "expected " + days + " days between " + from + " and " + to);
    }
}
