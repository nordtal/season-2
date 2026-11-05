package eu.nordtal.season.database.access;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.Jdbis;
import eu.nordtal.season.database.TestDatabase;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** Exercises {@link AccessDirectory} against PostgreSQL with the real migrations. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccessDirectoryIntegrationTest {

    private static final String DISCORD_ID = "100000000000000001";
    private static final UUID MC_UUID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static DataSource dataSource;

    private AccessDirectory directory;

    @BeforeAll
    static void startDatabase() {
        dataSource = TestDatabase.fresh().dataSource();
    }

    @BeforeEach
    void freshDirectory() {
        // Truncating keeps the migration applied once per class.
        execute("TRUNCATE TABLE access_grant, account_link, link_code, payment_request, journal_entry, "
                + "player_playtime, admin_grant, discord_user CASCADE");

        // season_state is a seeded singleton that is not truncated: launched a day ago, on smp, where access decides.
        execute(
                "UPDATE season_state SET launch = now() - interval '1 day', maintenance = false, mode = 'SMP' WHERE id");
        directory = AccessDirectory.using(dataSource, Clock.systemUTC());
    }

    /** Sets the launch date, in the past for a negative duration. */
    private static void launchIn(final Duration fromNow) {
        execute("UPDATE season_state SET launch = now() + interval '" + fromNow.toSeconds() + " seconds' WHERE id");
    }

    /** Clears the launch date, which closes the season. */
    private static void closed() {
        execute("UPDATE season_state SET launch = NULL WHERE id");
    }

    @Test
    void grantingWithNoAccessRunningStartsNow() {
        final AccessGrant grant = directory.grantAccess(DiscordId.of(DISCORD_ID), 30, AccessSource.PURCHASE, null);

        assertNotNull(grant.id());
        assertEquals(AccessSource.PURCHASE, grant.source());
        assertNull(grant.paymentRequestId());
        assertWithinSeconds(Instant.now(), grant.validFrom(), 5);
        assertDaysApart(30, grant.validFrom(), grant.validUntil());
    }

    @Test
    void grantingWhileAccessIsRunningAppendsInsteadOfRestarting() {
        // 30 days bought and 18 used leave 12 days.
        directory.grantAccess(DiscordId.of(DISCORD_ID), 30, AccessSource.PURCHASE, null);
        execute("""
                UPDATE access_grant
                SET valid_from = now() - interval '432 hours',
                    valid_until = now() + interval '288 hours'
                WHERE discord_id = '%s'
                """.formatted(DISCORD_ID));

        final AccessGrant appended = directory.grantAccess(DiscordId.of(DISCORD_ID), 30, AccessSource.PURCHASE, null);

        // The new period starts where the running one ends: 12 + 30 = 42 days out.
        assertWithinSeconds(Instant.now().plus(Duration.ofDays(12)), appended.validFrom(), 60);
        assertWithinSeconds(Instant.now().plus(Duration.ofDays(42)), appended.validUntil(), 60);

        final AccessState state = linkedState();
        assertTrue(state.accessActive());
        assertWithinSeconds(Instant.now().plus(Duration.ofDays(42)), state.accessValidUntil(), 60);
    }

    @Test
    void grantingAfterAccessLapsedStartsNowRatherThanInThePast() {
        directory.grantAccess(DiscordId.of(DISCORD_ID), 30, AccessSource.PURCHASE, null);
        execute("""
                UPDATE access_grant
                SET valid_from = now() - interval '1440 hours',
                    valid_until = now() - interval '720 hours'
                WHERE discord_id = '%s'
                """.formatted(DISCORD_ID));

        final AccessGrant grant = directory.grantAccess(DiscordId.of(DISCORD_ID), 30, AccessSource.PURCHASE, null);

        assertWithinSeconds(Instant.now(), grant.validFrom(), 5);
        assertDaysApart(30, grant.validFrom(), grant.validUntil());
    }

    @Test
    void aPurchaseBeforeTheSeasonStartsBeginsWhenTheSeasonDoes() {
        // A thirty-day purchase made a fortnight before the launch is thirty days of the season.
        launchIn(Duration.ofDays(14));

        final AccessGrant grant = directory.grantAccess(DiscordId.of(DISCORD_ID), 30, AccessSource.PURCHASE, null);

        assertWithinSeconds(Instant.now().plus(Duration.ofDays(14)), grant.validFrom(), 60);
        assertDaysApart(30, grant.validFrom(), grant.validUntil());
    }

    @Test
    void twoPurchasesBeforeTheSeasonStartStackIntoOneRunFromTheOpening() {
        // Two purchases made weeks in advance are sixty days of season from the launch.
        launchIn(Duration.ofDays(14));

        final AccessGrant first = directory.grantAccess(DiscordId.of(DISCORD_ID), 30, AccessSource.PURCHASE, null);
        final AccessGrant second = directory.grantAccess(DiscordId.of(DISCORD_ID), 30, AccessSource.PURCHASE, null);

        assertWithinSeconds(Instant.now().plus(Duration.ofDays(14)), first.validFrom(), 60);
        assertWithinSeconds(first.validUntil(), second.validFrom(), 2);
        assertWithinSeconds(Instant.now().plus(Duration.ofDays(74)), second.validUntil(), 60);
    }

    @Test
    void aPurchaseAfterTheSeasonHasOpenedIgnoresTheStoredDate() {
        // Once the season runs, the launch stays set. In the past, now() is the greater of the two.
        launchIn(Duration.ofDays(-30));

        final AccessGrant grant = directory.grantAccess(DiscordId.of(DISCORD_ID), 30, AccessSource.PURCHASE, null);

        assertWithinSeconds(Instant.now(), grant.validFrom(), 5);
        assertDaysApart(30, grant.validFrom(), grant.validUntil());
    }

    @Test
    void withNoSeasonStartTheChainStillStartsNowSoTheShopWorksUndated() {
        // The database refuses no grant while closed: the bot shuts the shop, and an admin grant starts now.
        closed();

        final AccessGrant grant = directory.grantAccess(DiscordId.of(DISCORD_ID), 30, AccessSource.PURCHASE, null);

        assertWithinSeconds(Instant.now(), grant.validFrom(), 5);
    }

    @Test
    void aLapseAfterTheOpeningStartsTodayRatherThanBackAtTheSeasonStart() {
        // Periods are never summed, so an expired grant and a past anchor cannot drag a new period backwards.
        launchIn(Duration.ofDays(-90));
        directory.grantAccess(DiscordId.of(DISCORD_ID), 30, AccessSource.PURCHASE, null);
        execute("""
                UPDATE access_grant
                SET valid_from = now() - interval '2160 hours',
                    valid_until = now() - interval '1440 hours'
                WHERE discord_id = '%s'
                """.formatted(DISCORD_ID));

        final AccessGrant grant = directory.grantAccess(DiscordId.of(DISCORD_ID), 30, AccessSource.PURCHASE, null);

        assertWithinSeconds(Instant.now(), grant.validFrom(), 5);
        assertDaysApart(30, grant.validFrom(), grant.validUntil());
    }

    @Test
    void aDayIsExactlyTwentyFourHoursEvenAcrossADaylightSavingChange() {
        // Day arithmetic on a timestamptz follows the session time zone and makes a period across DST 1 h short.
        final AccessGrant grant = directory.grantAccess(DiscordId.of(DISCORD_ID), 365, AccessSource.ADMIN, null);

        assertEquals(Duration.ofDays(365), Duration.between(grant.validFrom(), grant.validUntil()));
    }

    @Test
    void aGrantThatEndedOneSecondAgoIsNotActive() {
        directory.link(DiscordId.of(DISCORD_ID), MC_UUID);
        directory.grantAccess(DiscordId.of(DISCORD_ID), 30, AccessSource.PURCHASE, null);
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
        assertFalse(state.mayJoin(Instant.now()));
    }

    @Test
    void aGrantEndingOneSecondFromNowIsStillActive() {
        directory.link(DiscordId.of(DISCORD_ID), MC_UUID);
        directory.grantAccess(DiscordId.of(DISCORD_ID), 30, AccessSource.PURCHASE, null);
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
        directory.link(DiscordId.of(DISCORD_ID), MC_UUID);
        directory.grantAccess(DiscordId.of(DISCORD_ID), 30, AccessSource.PURCHASE, null);
        assertTrue(directory.accessState(MC_UUID).accessActive(), "precondition");

        assertEquals(1, directory.revokeAccess(DiscordId.of(DISCORD_ID)));

        final AccessState state = directory.accessState(MC_UUID);
        assertFalse(state.accessActive(), "the window still covers now, but the grant is revoked");
        assertNull(state.accessValidUntil());
        assertFalse(state.mayJoin(Instant.now()));

        final List<AccessGrant> grants = directory.grantsOf(DiscordId.of(DISCORD_ID));
        assertEquals(1, grants.size(), "revoking marks the row, it does not delete it");
        assertNotNull(grants.getFirst().revoked());
        assertFalse(grants.getFirst().coversAt(Instant.now()));
    }

    @Test
    void revokingTakesTheWholeAppendedChain() {
        directory.grantAccess(DiscordId.of(DISCORD_ID), 30, AccessSource.PURCHASE, null);
        directory.grantAccess(DiscordId.of(DISCORD_ID), 30, AccessSource.PURCHASE, null);
        directory.link(DiscordId.of(DISCORD_ID), MC_UUID);

        assertEquals(
                2,
                directory.revokeAccess(DiscordId.of(DISCORD_ID)),
                "a revoke that left the appended tail behind would report access as active later");
        assertFalse(directory.accessState(MC_UUID).accessActive());
    }

    @Test
    void grantingAfterARevokeStartsNowBecauseTheRevokedTailDoesNotCount() {
        directory.grantAccess(DiscordId.of(DISCORD_ID), 30, AccessSource.PURCHASE, null);
        directory.revokeAccess(DiscordId.of(DISCORD_ID));

        final AccessGrant grant = directory.grantAccess(DiscordId.of(DISCORD_ID), 7, AccessSource.ADMIN, null);

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
        assertFalse(state.mayJoin(Instant.now()));
    }

    @Test
    void accessStateOfALinkedAccountWithoutAccess() {
        directory.link(DiscordId.of(DISCORD_ID), MC_UUID);

        final AccessState state = directory.accessState(MC_UUID);

        assertTrue(state.linked());
        assertEquals(DiscordId.of(DISCORD_ID), state.discordId());
        assertEquals(MemberState.MEMBER, state.memberState());
        assertFalse(state.accessActive());
        assertFalse(state.mayJoin(Instant.now()));
    }

    @Test
    void accessStateOfABannedAccountWithValidAccess() {
        directory.link(DiscordId.of(DISCORD_ID), MC_UUID);
        directory.grantAccess(DiscordId.of(DISCORD_ID), 30, AccessSource.PURCHASE, null);
        directory.setMemberState(DiscordId.of(DISCORD_ID), MemberState.BANNED);

        final AccessState state = directory.accessState(MC_UUID);

        assertEquals(MemberState.BANNED, state.memberState());
        assertTrue(state.accessActive(), "a ban does not pause the paid period, it only blocks the join");
        assertFalse(state.mayJoin(Instant.now()));
    }

    @Test
    void accessStateOfALinkedActiveMember() {
        directory.link(DiscordId.of(DISCORD_ID), MC_UUID);
        directory.setLocale(DiscordId.of(DISCORD_ID), Locale.GERMAN);
        Jdbis.over(dataSource).useTransaction(handle -> Grants.markDonor(handle, DiscordId.of(DISCORD_ID)));
        directory.grantAccess(DiscordId.of(DISCORD_ID), 60, AccessSource.PURCHASE, null);

        final AccessState state = directory.accessState(MC_UUID);

        assertTrue(state.mayJoin(Instant.now()));
        assertTrue(state.donor());
        assertEquals(Locale.GERMAN, state.locale());
        assertWithinSeconds(Instant.now().plus(Duration.ofDays(60)), state.accessValidUntil(), 60);
    }

    @Test
    void theLinkIsOneToOneAndTheDatabaseIsWhatEnforcesIt() {
        assertTrue(directory.link(DiscordId.of(DISCORD_ID), MC_UUID));

        // Same Discord user, second Minecraft account.
        assertFalse(directory.link(DiscordId.of(DISCORD_ID), UUID.randomUUID()));
        // Same Minecraft account, second Discord user.
        assertFalse(directory.link(DiscordId.of("100000000000000002"), MC_UUID));

        assertEquals(
                MC_UUID,
                directory.linkedMinecraftAccount(DiscordId.of(DISCORD_ID)).orElseThrow());
        assertEquals(
                DiscordId.of(DISCORD_ID),
                directory.linkedDiscordAccount(MC_UUID).orElseThrow());
    }

    @Test
    void unlinkingLeavesTheUserAndTheGrantsBehind() {
        directory.link(DiscordId.of(DISCORD_ID), MC_UUID);
        directory.grantAccess(DiscordId.of(DISCORD_ID), 30, AccessSource.PURCHASE, null);

        assertTrue(directory.unlink(DiscordId.of(DISCORD_ID)));
        assertFalse(
                directory.unlink(DiscordId.of(DISCORD_ID)), "unlinking twice is not an error, it just does nothing");

        assertTrue(directory.linkedMinecraftAccount(DiscordId.of(DISCORD_ID)).isEmpty());
        assertFalse(directory.accessState(MC_UUID).linked());
        assertEquals(1, directory.grantsOf(DiscordId.of(DISCORD_ID)).size(), "paid time survives an unlink");
    }

    @Test
    void theLanguageOfAnUnknownAccountIsTheNetworksDefault() {
        assertEquals(Locale.ENGLISH, directory.language(DiscordId.of("999999999999999999")));
    }

    @Test
    void anAccountReadsTheNetworksDefaultUntilItChoosesALanguage() {
        directory.link(DiscordId.of(DISCORD_ID), MC_UUID);
        assertEquals(Locale.ENGLISH, directory.language(DiscordId.of(DISCORD_ID)), "no language is NULL, not 'en'");

        directory.setLocale(DiscordId.of(DISCORD_ID), Locale.GERMANY);
        assertEquals(
                Locale.GERMAN,
                directory.language(DiscordId.of(DISCORD_ID)),
                "only the language is stored, so de-DE and de-AT are one bundle");
    }

    @Test
    void donorIsFalseForAnUnknownUser() {
        assertFalse(directory.isDonor(DiscordId.of("999999999999999999")));
    }

    @Test
    void nobodyIsAnAdminUntilTheMirrorSaysSo() {
        directory.link(DiscordId.of(DISCORD_ID), MC_UUID);

        assertFalse(
                directory.accessState(MC_UUID).admin(),
                "the column defaults to false - a user the mirror has never run for is not an admin");
    }

    @Test
    void theAdminFlagRidesAlongOnTheQueryTheLoginPathAlreadyMakes() {
        directory.link(DiscordId.of(DISCORD_ID), MC_UUID);
        setAdmin(DiscordId.of(DISCORD_ID), true);

        final AccessState state = directory.accessState(MC_UUID);

        assertTrue(state.admin(), "this is what maintenance and the closed season let through");
        assertTrue(state.mayJoin(Instant.now()), "it is also an access period: on smp it stands in for a grant");
    }

    @Test
    void theAdminFlagIsClearedAgainUnlikeDonor() {
        directory.link(DiscordId.of(DISCORD_ID), MC_UUID);
        setAdmin(DiscordId.of(DISCORD_ID), true);
        Jdbis.over(dataSource).useTransaction(handle -> Grants.markDonor(handle, DiscordId.of(DISCORD_ID)));

        setAdmin(DiscordId.of(DISCORD_ID), false);

        final AccessState state = directory.accessState(MC_UUID);
        assertFalse(state.admin(), "losing the Discord role has to lose the permission");
        assertTrue(state.donor(), "the donor flag is permanent, and clearing admin must not touch it");
    }

    @Test
    void oneBunqPaymentCannotSettleTwoRequests() throws SQLException {
        directory.ensureUser(DiscordId.of(DISCORD_ID));
        insertSettledRequest("NT-AAAAAA", 4242L);

        final SQLException failure = assertThrows(SQLException.class, () -> insertSettledRequest("NT-BBBBBB", 4242L));

        assertTrue(
                failure.getMessage().contains("payment_request_bunq_payment_id_key"),
                "the partial unique index is what refuses the second booking, not application code: "
                        + failure.getMessage());
    }

    @Test
    void unsettledRequestsAreNotConstrainedAgainstEachOther() throws SQLException {
        directory.ensureUser(DiscordId.of(DISCORD_ID));
        directory.ensureUser(DiscordId.of("100000000000000002"));

        // The unique index on bunq_payment_id is partial, so two NULLs do not collide.
        assertDoesNotThrow(
                () -> {
                    insertOpenRequest(DiscordId.of(DISCORD_ID), "NT-CCCCCC");
                    insertOpenRequest(DiscordId.of("100000000000000002"), "NT-DDDDDD");
                },
                "two open requests of different people must not collide on the null payment id");
    }

    @Test
    void onePersonCannotHoldTwoOpenRequests() throws SQLException {
        directory.ensureUser(DiscordId.of(DISCORD_ID));
        insertOpenRequest(DiscordId.of(DISCORD_ID), "NT-EEEEEE");

        final SQLException failure =
                assertThrows(SQLException.class, () -> insertOpenRequest(DiscordId.of(DISCORD_ID), "NT-FFFFFF"));

        assertTrue(
                failure.getMessage().contains("payment_request_one_open_per_user_key"),
                "starting a new request has to supersede the old one in the same transaction: " + failure.getMessage());
    }

    @Test
    void onePaymentRequestCannotProduceTwoGrants() throws SQLException {
        directory.ensureUser(DiscordId.of(DISCORD_ID));
        final UUID requestId = insertSettledRequest("NT-123456", 77L);

        directory.grantAccess(DiscordId.of(DISCORD_ID), 30, AccessSource.PURCHASE, requestId);

        assertThrows(
                RuntimeException.class,
                () -> directory.grantAccess(DiscordId.of(DISCORD_ID), 30, AccessSource.PURCHASE, requestId),
                "access_grant_payment_request_id_key is the second half of the double-booking guard");
        assertEquals(1, directory.grantsOf(DiscordId.of(DISCORD_ID)).size());
    }

    private AccessState linkedState() {
        directory.link(DiscordId.of(DISCORD_ID), MC_UUID);
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
        // A column list that does not match the record fails only at runtime.
        directory.ensureUser(DiscordId.of(DISCORD_ID));
        assertTrue(
                directory.openPayment(DiscordId.of(DISCORD_ID)).isEmpty(),
                "an account that has started nothing has no open purchase");

        insertOpenRequest(DiscordId.of(DISCORD_ID), "NT-A1B2C3");

        final var pending = directory.openPayment(DiscordId.of(DISCORD_ID)).orElseThrow();
        assertEquals("NT-A1B2C3", pending.reference());
        assertEquals(30, pending.days());
        assertEquals(300, pending.amountCents());
        assertEquals(300, pending.amountCents());
        assertNotNull(pending.created());
        assertFalse(
                pending.hasTab(),
                "bunq_tab_id IS NULL is the difference between 'chose 30 days' and 'asked for a"
                        + " payment link', and it is what an admin chasing a stuck purchase needs");

        execute("UPDATE payment_request SET bunq_tab_id = 4242 WHERE reference = 'NT-A1B2C3'");
        assertTrue(directory.openPayment(DiscordId.of(DISCORD_ID)).orElseThrow().hasTab());

        // Only OPEN rows. payment_request_settled_iff_paid moves `settled` with the status.
        execute("UPDATE payment_request SET status = 'PAID', settled = now()" + " WHERE reference = 'NT-A1B2C3'");
        assertTrue(directory.openPayment(DiscordId.of(DISCORD_ID)).isEmpty());
    }

    private void insertOpenRequest(final DiscordId discordId, final String reference) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                var statement = connection.prepareStatement("""
                     INSERT INTO payment_request (reference, discord_id, days, amount_cents, expires)
                     VALUES (?, ?, 30, 300, now() + interval '24 hours')
                     """)) {
            statement.setString(1, reference);
            statement.setString(2, discordId.value());
            statement.executeUpdate();
        }
    }

    /** Makes an account an admin, granted by the root unless it is the first. */
    private void setAdmin(final DiscordId discordId, final boolean admin) {
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
                AdminTree.Grant.GRANTED,
                tree.grant(tree.admins().getFirst().discordId().value(), discordId.value()));
    }

    private static void execute(final String sql) {
        try {
            executeChecked(sql);
        } catch (final SQLException exception) {
            throw new IllegalStateException("Test setup statement failed: " + sql, exception);
        }
    }

    /** Like {@link #execute(String)}, but returns the failure for a constraint assertion. */
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
        // This signal reaches a player who is online. It is part of the write.
        try (Connection listening = dataSource.getConnection()) {
            try (Statement statement = listening.createStatement()) {
                statement.execute("LISTEN nordtal_admin");
            }

            setAdmin(DiscordId.of(DISCORD_ID), true);

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
        setAdmin(DiscordId.of(DISCORD_ID), true);

        try (Connection listening = dataSource.getConnection()) {
            try (Statement statement = listening.createStatement()) {
                statement.execute("LISTEN nordtal_admin");
            }

            setAdmin(DiscordId.of(DISCORD_ID), false);

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

        setAdmin(DiscordId.of(DISCORD_ID), true);
        setAdmin(DiscordId.of("100000000000000002"), true);
        setAdmin(DiscordId.of("100000000000000003"), false);

        assertEquals(
                java.util.Set.of(DISCORD_ID, "100000000000000002"),
                directory.admins(),
                "one query for the whole set is what makes the refresh idempotent - a lost"
                        + " notification then costs latency rather than correctness");

        setAdmin(DiscordId.of("100000000000000002"), false);
        assertEquals(java.util.Set.of(DISCORD_ID), directory.admins());
    }

    @Test
    void theBackendsAskForAdminsByMinecraftAccountAndGetOnlyTheLinkedOnes() {
        // A Paper server knows only a UUID, so this queries account_link and does not map admins().
        assertTrue(directory.adminMinecraftAccounts().isEmpty());

        setAdmin(DiscordId.of(DISCORD_ID), true);
        assertTrue(
                directory.adminMinecraftAccounts().isEmpty(),
                "an admin with no account link cannot be online anywhere, so nothing on a backend"
                        + " should be told about them");

        directory.link(DiscordId.of(DISCORD_ID), MC_UUID);
        assertEquals(java.util.Set.of(MC_UUID), directory.adminMinecraftAccounts());

        // This removes operator from somebody who is online.
        setAdmin(DiscordId.of(DISCORD_ID), false);
        assertTrue(
                directory.adminMinecraftAccounts().isEmpty(),
                "a revoked admin has to leave this set immediately - AdminWatch hands it straight to"
                        + " AdminOperators#refresh, and whoever is not in it loses operator");
    }
}
