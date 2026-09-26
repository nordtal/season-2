package eu.nordtal.s2.common.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import eu.nordtal.s2.common.SeasonPhase;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Exercises {@link AccessDirectory} against a real PostgreSQL instance running the real migration.
 *
 * Nothing here can be done in memory: the append rule, the expiry comparison and the double-book
 * guard are all evaluated by PostgreSQL - {@code GREATEST(now(), ...)}, {@code make_interval} and
 * a partial unique index have no in-JVM stand-in. Testcontainers is driven by hand from
 * {@link BeforeAll} because the {@code org.testcontainers:junit-jupiter} extension is built
 * against JUnit 5 and this repo is on the JUnit 6 BOM.
 *
 * These tests <b>skip themselves</b> when no Docker daemon is reachable. A green build on a
 * machine without Docker proves nothing about any of this.
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
        assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
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
        // Truncating rather than dropping the schema keeps the migration applied once per class.
        execute("TRUNCATE TABLE access_grant, account_link, link_code, payment_request, audit_log, "
                + "player_playtime, admin_grant, discord_user CASCADE");

        // season_phase is a seeded singleton and is not truncated; SMP is the phase in which access decides.
        phase(SeasonPhase.SMP);
        // smp_start is cleared explicitly, or a test that sets it anchors every test after it.
        execute("UPDATE season_phase SET smp_start = NULL WHERE id");
        directory = AccessDirectory.using(dataSource);
    }

    /** Puts the season_phase singleton into one phase for the duration of a test. */
    private static void phase(final SeasonPhase phase) {
        execute("UPDATE season_phase SET phase = '" + phase.name() + "' WHERE id");
    }

    /** Announces when paid access starts running - {@code V9__smp_start.sql}, set by hand in life. */
    private static void smpStartsIn(final Duration fromNow) {
        execute("UPDATE season_phase SET smp_start = now() + interval '" + fromNow.toSeconds() + " seconds' WHERE id");
    }

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
    void aPurchaseBeforeTheSeasonStartsBeginsWhenTheSeasonDoes() {
        // A thirty-day purchase made a fortnight before the opening is still thirty days of SMP.
        phase(SeasonPhase.PRE_LAUNCH);
        smpStartsIn(Duration.ofDays(14));

        final AccessGrant grant = directory.grantAccess(DISCORD_ID, 30, AccessSource.PURCHASE, null);

        assertWithinSeconds(Instant.now().plus(Duration.ofDays(14)), grant.validFrom(), 60);
        assertDaysApart(30, grant.validFrom(), grant.validUntil());
    }

    @Test
    void twoPurchasesBeforeTheSeasonStartStackIntoOneRunFromTheOpening() {
        // Two purchases made weeks in advance are sixty days of season, starting at the opening.
        phase(SeasonPhase.PRE_LAUNCH);
        smpStartsIn(Duration.ofDays(14));

        final AccessGrant first = directory.grantAccess(DISCORD_ID, 30, AccessSource.PURCHASE, null);
        final AccessGrant second = directory.grantAccess(DISCORD_ID, 30, AccessSource.PURCHASE, null);

        assertWithinSeconds(Instant.now().plus(Duration.ofDays(14)), first.validFrom(), 60);
        assertWithinSeconds(first.validUntil(), second.validFrom(), 2);
        assertWithinSeconds(Instant.now().plus(Duration.ofDays(74)), second.validUntil(), 60);
    }

    @Test
    void aPurchaseAfterTheSeasonHasOpenedIgnoresTheStoredDate() {
        // smp_start is never cleared once the season runs; in the past, now() is the greater of the two.
        execute("UPDATE season_phase SET smp_start = now() - interval '30 days' WHERE id");

        final AccessGrant grant = directory.grantAccess(DISCORD_ID, 30, AccessSource.PURCHASE, null);

        assertWithinSeconds(Instant.now(), grant.validFrom(), 5);
        assertDaysApart(30, grant.validFrom(), grant.validUntil());
    }

    @Test
    void withNoSeasonStartTheChainStillStartsNowSoTheShopWorksUndated() {
        // Selling is not blocked on an unset date; the bot warns on every such grant (SeasonStart).
        phase(SeasonPhase.PRE_LAUNCH);

        final AccessGrant grant = directory.grantAccess(DISCORD_ID, 30, AccessSource.PURCHASE, null);

        assertWithinSeconds(Instant.now(), grant.validFrom(), 5);
    }

    @Test
    void aLapseAfterTheOpeningStartsTodayRatherThanBackAtTheSeasonStart() {
        // Periods are never summed: an expired grant and a past anchor cannot drag a new period backwards.
        execute("UPDATE season_phase SET smp_start = now() - interval '90 days' WHERE id");
        directory.grantAccess(DISCORD_ID, 30, AccessSource.PURCHASE, null);
        execute("""
                UPDATE access_grant
                SET valid_from = now() - interval '2160 hours',
                    valid_until = now() - interval '1440 hours'
                WHERE discord_id = '%s'
                """.formatted(DISCORD_ID));

        final AccessGrant grant = directory.grantAccess(DISCORD_ID, 30, AccessSource.PURCHASE, null);

        assertWithinSeconds(Instant.now(), grant.validFrom(), 5);
        assertDaysApart(30, grant.validFrom(), grant.validUntil());
    }

    @Test
    void aDayIsExactlyTwentyFourHoursEvenAcrossADaylightSavingChange() {
        // Day arithmetic on a timestamptz follows the session time zone and makes a DST-spanning period 1 h short.
        final AccessGrant grant = directory.grantAccess(DISCORD_ID, 365, AccessSource.ADMIN, null);

        assertEquals(Duration.ofDays(365), Duration.between(grant.validFrom(), grant.validUntil()));
    }

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

        assertEquals(
                2,
                directory.revokeAccess(DISCORD_ID),
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
        assertFalse(state.admin());
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

    @Test
    void theLoginQueryCarriesThePhaseSoTheProxyNeverMakesASecondRoundTrip() {
        // One round trip on the login path carries both the access state and the phase.
        directory.link(DISCORD_ID, MC_UUID);
        phase(SeasonPhase.START_EVENT);

        assertEquals(SeasonPhase.START_EVENT, directory.accessState(MC_UUID).phase());
        assertEquals(
                SeasonPhase.START_EVENT,
                directory.accessState(UUID.randomUUID()).phase(),
                "an unlinked UUID still has to learn the phase - the disconnect screen depends on it");
    }

    @Test
    void aLinkedMemberWithNoAccessGetsInBeforeTheSmpAndNotAfterIt() {
        // The pre-event and the start event are free for anyone who has linked their account.
        directory.link(DISCORD_ID, MC_UUID);

        phase(SeasonPhase.PRE_EVENT);
        assertTrue(directory.accessState(MC_UUID).mayJoin(), "PRE_EVENT needs no access");

        phase(SeasonPhase.START_EVENT);
        assertTrue(directory.accessState(MC_UUID).mayJoin(), "START_EVENT needs no access");

        phase(SeasonPhase.SMP);
        assertFalse(directory.accessState(MC_UUID).mayJoin(), "SMP is the phase access is for");
    }

    @Test
    void anUnlinkedAccountIsRefusedInEveryPhaseIncludingTheFreeOnes() {
        final UUID stranger = UUID.randomUUID();

        for (final SeasonPhase each : SeasonPhase.values()) {
            phase(each);
            assertFalse(
                    directory.accessState(stranger).mayJoin(),
                    "linking is the one requirement no phase waives, and " + each + " is no exception");
        }
    }

    @Test
    void aBannedMemberIsRefusedInEveryPhaseEvenWithAccessAndTheAdminFlag() {
        directory.link(DISCORD_ID, MC_UUID);
        directory.grantAccess(DISCORD_ID, 30, AccessSource.PURCHASE, null);
        setAdmin(DISCORD_ID, true);
        directory.setMemberState(DISCORD_ID, MemberState.BANNED);

        for (final SeasonPhase each : SeasonPhase.values()) {
            phase(each);
            assertFalse(
                    directory.accessState(MC_UUID).mayJoin(),
                    "a ban outranks paid access and the admin flag, in " + each);
        }
    }

    @Test
    void maintenanceAdmitsAnyLinkedMemberSoTheProxyCanHoldThemInLimbo() {
        // MAINTENANCE admits like the event phases; where a player goes is the proxy's PhaseRouting.
        directory.link(DISCORD_ID, MC_UUID);
        phase(SeasonPhase.MAINTENANCE);

        assertTrue(
                directory.accessState(MC_UUID).mayJoin(),
                "a linked member is let in during maintenance and then routed to limbo");

        directory.grantAccess(DISCORD_ID, 30, AccessSource.PURCHASE, null);
        assertTrue(directory.accessState(MC_UUID).mayJoin(), "buying access changes nothing here");

        setAdmin(DISCORD_ID, true);
        assertTrue(directory.accessState(MC_UUID).mayJoin(), "and neither does the admin flag");
    }

    @Test
    void anAdminWithoutAccessGetsIntoMaintenanceAndIntoTheSmp() {
        directory.link(DISCORD_ID, MC_UUID);
        setAdmin(DISCORD_ID, true);

        phase(SeasonPhase.MAINTENANCE);
        assertTrue(
                directory.accessState(MC_UUID).mayJoin(),
                "an admin gets in during maintenance - as does everybody else");

        // The admin flag is a free pass in SMP, so the admin who switches to SMP is not disconnected.
        phase(SeasonPhase.SMP);
        assertTrue(directory.accessState(MC_UUID).mayJoin(), "the admin flag is an access period");
        setAdmin(DISCORD_ID, false);
        assertFalse(
                directory.accessState(MC_UUID).mayJoin(),
                "and losing the role loses the pass, with nothing bought underneath it");
    }

    @Test
    void aDeletedPhaseRowReadsAsMaintenanceRatherThanLookingLikeAnUnlinkedAccount() {
        // Without the phase row the account still reads as linked and the phase as MAINTENANCE (limbo).
        directory.link(DISCORD_ID, MC_UUID);
        directory.grantAccess(DISCORD_ID, 30, AccessSource.PURCHASE, null);
        execute("DELETE FROM season_phase");
        try {
            final AccessState state = directory.accessState(MC_UUID);

            assertTrue(state.linked(), "a missing phase row must not make a linked player look unlinked");
            assertTrue(state.accessActive());
            assertEquals(SeasonPhase.MAINTENANCE, state.phase());
            assertTrue(state.mayJoin(), "admitted, and then held in the waiting room");
        } finally {
            execute("INSERT INTO season_phase (phase) VALUES ('PRE_EVENT')");
        }
    }

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
        assertEquals(
                Locale.GERMAN,
                directory.locale(MC_UUID),
                "only the language is stored, so de-DE and de-AT are one bundle");
    }

    @Test
    void donorIsFalseForAnUnknownUser() {
        assertFalse(directory.isDonor("999999999999999999"));
    }

    @Test
    void nobodyIsAnAdminUntilTheMirrorSaysSo() {
        directory.link(DISCORD_ID, MC_UUID);

        assertFalse(
                directory.accessState(MC_UUID).admin(),
                "the column defaults to false - a user the mirror has never run for is not an admin");
    }

    @Test
    void theAdminFlagRidesAlongOnTheQueryTheLoginPathAlreadyMakes() {
        directory.link(DISCORD_ID, MC_UUID);
        setAdmin(DISCORD_ID, true);

        final AccessState state = directory.accessState(MC_UUID);

        assertTrue(
                state.admin(), "this is what MAINTENANCE and the proxy's emergency /phase command are authorised by");
        assertTrue(
                state.mayJoin(),
                "it is also an access period: the seeded phase is PRE_LAUNCH, "
                        + "where the flag is the admission rule, and in SMP it stands in for a grant");
    }

    @Test
    void theAdminFlagIsClearedAgainUnlikeDonor() {
        directory.link(DISCORD_ID, MC_UUID);
        setAdmin(DISCORD_ID, true);
        directory.setDonor(DISCORD_ID, true);

        setAdmin(DISCORD_ID, false);

        final AccessState state = directory.accessState(MC_UUID);
        assertFalse(state.admin(), "losing the Discord role has to lose the permission");
        assertTrue(state.donor(), "the donor flag is permanent, and clearing admin must not touch it");
    }

    @Test
    void oneBunqPaymentCannotSettleTwoRequests() throws SQLException {
        directory.ensureUser(DISCORD_ID);
        insertSettledRequest("NT-AAAAAA", 4242L);

        final SQLException failure = assertThrows(SQLException.class, () -> insertSettledRequest("NT-BBBBBB", 4242L));

        assertTrue(
                failure.getMessage().contains("payment_request_bunq_payment_id_key"),
                "the partial unique index is what refuses the second booking, not application code: "
                        + failure.getMessage());
    }

    @Test
    void unsettledRequestsAreNotConstrainedAgainstEachOther() throws SQLException {
        directory.ensureUser(DISCORD_ID);
        directory.ensureUser("100000000000000002");

        // The unique index on bunq_payment_id is partial, so two NULLs do not collide.
        insertOpenRequest(DISCORD_ID, "NT-CCCCCC");
        insertOpenRequest("100000000000000002", "NT-DDDDDD");
    }

    @Test
    void onePersonCannotHoldTwoOpenRequests() throws SQLException {
        directory.ensureUser(DISCORD_ID);
        insertOpenRequest(DISCORD_ID, "NT-EEEEEE");

        final SQLException failure = assertThrows(SQLException.class, () -> insertOpenRequest(DISCORD_ID, "NT-FFFFFF"));

        assertTrue(
                failure.getMessage().contains("payment_request_one_open_per_user_key"),
                "starting a new request has to supersede the old one in the same transaction: " + failure.getMessage());
    }

    @Test
    void onePaymentRequestCannotProduceTwoGrants() throws SQLException {
        directory.ensureUser(DISCORD_ID);
        final UUID requestId = insertSettledRequest("NT-123456", 77L);

        directory.grantAccess(DISCORD_ID, 30, AccessSource.PURCHASE, requestId);

        assertThrows(
                RuntimeException.class,
                () -> directory.grantAccess(DISCORD_ID, 30, AccessSource.PURCHASE, requestId),
                "access_grant_payment_request_id_key is the second half of the double-booking guard");
        assertEquals(1, directory.grantsOf(DISCORD_ID).size());
    }

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

    @Test
    void anOpenPurchaseIsReadableFromOutsideTheBotTabOrNoTab() throws SQLException {
        // Against a real database: a column list that does not match the record only fails at runtime.
        directory.ensureUser(DISCORD_ID);
        assertTrue(
                directory.openPayment(DISCORD_ID).isEmpty(),
                "an account that has started nothing has no open purchase");

        insertOpenRequest(DISCORD_ID, "NT-A1B2C3");

        final var pending = directory.openPayment(DISCORD_ID).orElseThrow();
        assertEquals("NT-A1B2C3", pending.reference());
        assertEquals(30, pending.days());
        assertEquals(300, pending.amountCents());
        assertEquals("3.00", pending.amount());
        assertNotNull(pending.created());
        assertFalse(
                pending.hasTab(),
                "bunq_tab_id IS NULL is the difference between 'chose 30 days' and 'asked for a"
                        + " payment link', and it is what an admin chasing a stuck purchase needs");

        execute("UPDATE payment_request SET bunq_tab_id = 4242 WHERE reference = 'NT-A1B2C3'");
        assertTrue(directory.openPayment(DISCORD_ID).orElseThrow().hasTab());

        // Only OPEN rows; payment_request_settled_iff_paid moves `settled` together with the status.
        execute("UPDATE payment_request SET status = 'PAID', settled = now()" + " WHERE reference = 'NT-A1B2C3'");
        assertTrue(directory.openPayment(DISCORD_ID).isEmpty());
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

    /**
     * Makes an account an admin: the first becomes the root, every later one is granted by it.
     *
     * Clearing drops the account's branch.
     */
    private void setAdmin(final String discordId, final boolean admin) {
        final AdminTree tree = AdminTree.using(dataSource);
        if (!admin) {
            tree.dropWithBranch(discordId);
            return;
        }
        if (tree.claimRootIfNobody(discordId)) {
            return;
        }
        directory.setMemberState(discordId, MemberState.MEMBER);
        assertEquals(
                AdminTree.Grant.GRANTED, tree.grant(tree.admins().getFirst().discordId(), discordId));
    }

    private static void execute(final String sql) {
        try {
            executeChecked(sql);
        } catch (final SQLException exception) {
            throw new IllegalStateException("Test setup statement failed: " + sql, exception);
        }
    }

    /** Like {@link #execute(String)}, but hands the failure back so a constraint can be asserted on. */
    private static void executeChecked(final String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void assertWithinSeconds(final Instant expected, final Instant actual, final long tolerance) {
        assertNotNull(actual, "expected a timestamp around " + expected + ", got null");
        final long off = Math.abs(Duration.between(expected, actual).toSeconds());
        assertTrue(
                off <= tolerance,
                "expected " + actual + " to be within " + tolerance + "s of " + expected + ", was off by " + off + "s");
    }

    private static void assertDaysApart(final long days, final Instant from, final Instant to) {
        final long actual = Duration.between(from, to).toDays();
        assertEquals(days, actual, "expected " + days + " days between " + from + " and " + to);
    }

    @Test
    void m9SettingTheAdminFlagNotifiesNordtalAdminWithTheDiscordId() throws Exception {
        // This signal lets a revocation reach a player who is already online; it rides inside the write.
        try (Connection listening = dataSource.getConnection()) {
            try (Statement statement = listening.createStatement()) {
                statement.execute("LISTEN nordtal_admin");
            }

            setAdmin(DISCORD_ID, true);

            final org.postgresql.PGNotification[] arrived =
                    listening.unwrap(org.postgresql.PGConnection.class).getNotifications(5000);

            assertNotNull(arrived, "no notification arrived on nordtal_admin within 5s");
            assertEquals(1, arrived.length);
            assertEquals("nordtal_admin", arrived[0].getName());
            assertEquals(
                    DISCORD_ID,
                    arrived[0].getParameter(),
                    "the payload is the Discord id - the proxy does not act on it, but a payload"
                            + " that names the wrong account is worse than none");
        }
    }

    @Test
    void m9ARevocationNotifiesAsLoudlyAsAGrant() throws Exception {
        setAdmin(DISCORD_ID, true);

        try (Connection listening = dataSource.getConnection()) {
            try (Statement statement = listening.createStatement()) {
                statement.execute("LISTEN nordtal_admin");
            }

            setAdmin(DISCORD_ID, false);

            final org.postgresql.PGNotification[] arrived =
                    listening.unwrap(org.postgresql.PGConnection.class).getNotifications(5000);

            assertNotNull(
                    arrived,
                    "a revocation produced no notification, which is the direction" + " that actually matters");
            assertEquals(DISCORD_ID, arrived[0].getParameter());
        }
    }

    @Test
    void m9AdminsIsTheWholeSetTheProxyReDerivesEverySessionFrom() {
        assertTrue(directory.admins().isEmpty());

        setAdmin(DISCORD_ID, true);
        setAdmin("100000000000000002", true);
        setAdmin("100000000000000003", false);

        assertEquals(
                java.util.Set.of(DISCORD_ID, "100000000000000002"),
                directory.admins(),
                "one query for the whole set is what makes the refresh idempotent - a lost"
                        + " notification then costs latency rather than correctness");

        setAdmin("100000000000000002", false);
        assertEquals(java.util.Set.of(DISCORD_ID), directory.admins());
    }

    @Test
    void theBackendsAskForAdminsByMinecraftAccountAndGetOnlyTheLinkedOnes() {
        // A Paper server knows only a UUID, so this is a query through account_link, not a mapping of admins().
        assertTrue(directory.adminMinecraftAccounts().isEmpty());

        setAdmin(DISCORD_ID, true);
        assertTrue(
                directory.adminMinecraftAccounts().isEmpty(),
                "an admin with no account link cannot be online anywhere, so nothing on a backend"
                        + " should be told about them");

        directory.link(DISCORD_ID, MC_UUID);
        assertEquals(java.util.Set.of(MC_UUID), directory.adminMinecraftAccounts());

        // This is what removes operator from somebody who is online right now.
        setAdmin(DISCORD_ID, false);
        assertTrue(
                directory.adminMinecraftAccounts().isEmpty(),
                "a revoked admin has to leave this set immediately - AdminWatch hands it straight to"
                        + " AdminOperators#refresh, and whoever is not in it loses operator");
    }
}
