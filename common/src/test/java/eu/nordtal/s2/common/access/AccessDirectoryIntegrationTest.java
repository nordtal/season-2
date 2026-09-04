package eu.nordtal.s2.common.access;

import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.common.message.PlayerLocales;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
        execute("TRUNCATE TABLE access_grant, account_link, link_code, payment_request, audit_log, "
                + "player_playtime, discord_user CASCADE");

        // season_phase is NOT truncated - it is a singleton the migration seeds, and the login
        // query now reads it (docs/season-phases.md: one round trip carries both). SMP is the
        // baseline here on purpose: it is the one phase in which access decides anything, so every
        // access assertion below keeps meaning exactly what it meant before the merge. The tests
        // that are about the phase itself set their own.
        phase(SeasonPhase.SMP);
        // Nor is smp_start, for the same reason - so it is cleared explicitly here. A test that
        // sets it would otherwise anchor every test that ran after it, which is exactly the kind
        // of order-dependent green that a truncate-per-test exists to prevent.
        execute("UPDATE season_phase SET smp_start = NULL WHERE id");
        directory = AccessDirectory.using(dataSource);
    }

    /** Puts the season_phase singleton into one phase for the duration of a test. */
    private static void phase(final SeasonPhase phase) {
        execute("UPDATE season_phase SET phase = '" + phase.name() + "' WHERE id");
    }

    /** Announces when paid access starts running - {@code V9__smp_start.sql}, set by hand in life. */
    private static void smpStartsIn(final Duration fromNow) {
        execute("UPDATE season_phase SET smp_start = now() + interval '"
                + fromNow.toSeconds() + " seconds' WHERE id");
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

    // ---------------------------------------------------------------- the season start anchor

    @Test
    void aPurchaseBeforeTheSeasonStartsBeginsWhenTheSeasonDoes() {
        // The point of the whole anchor: access is only asked for in SMP, so a thirty-day purchase
        // made a fortnight before the opening must still be thirty days of SMP.
        phase(SeasonPhase.PRE_LAUNCH);
        smpStartsIn(Duration.ofDays(14));

        final AccessGrant grant = directory.grantAccess(DISCORD_ID, 30, AccessSource.PURCHASE, null);

        assertWithinSeconds(Instant.now().plus(Duration.ofDays(14)), grant.validFrom(), 60);
        assertDaysApart(30, grant.validFrom(), grant.validUntil());
    }

    @Test
    void twoPurchasesBeforeTheSeasonStartStackIntoOneRunFromTheOpening() {
        // Buying 30 and then another 30 weeks in advance has to be 60 days of season, not 60 days
        // of calendar starting today and not 30 days twice over the same fortnight.
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
        // smp_start is deliberately never cleared once the season is running, so it has to stop
        // mattering on its own. It does: it is in the past, and now() is the greater of the two.
        execute("UPDATE season_phase SET smp_start = now() - interval '30 days' WHERE id");

        final AccessGrant grant = directory.grantAccess(DISCORD_ID, 30, AccessSource.PURCHASE, null);

        assertWithinSeconds(Instant.now(), grant.validFrom(), 5);
        assertDaysApart(30, grant.validFrom(), grant.validUntil());
    }

    @Test
    void withNoSeasonStartTheChainStillStartsNowSoTheShopWorksUndated() {
        // Decided 2026-09-03: selling is not blocked on somebody having picked a date, so that the
        // payment path can be exercised internally. The bot says so loudly on every such grant -
        // see SeasonStart - and that warning is the only thing standing between this and a real
        // customer losing the weeks before an opening nobody dated.
        phase(SeasonPhase.PRE_LAUNCH);

        final AccessGrant grant = directory.grantAccess(DISCORD_ID, 30, AccessSource.PURCHASE, null);

        assertWithinSeconds(Instant.now(), grant.validFrom(), 5);
    }

    @Test
    void aLapseAfterTheOpeningStartsTodayRatherThanBackAtTheSeasonStart() {
        // Periods are never summed. Somebody who bought before the season, let it run out and buys
        // again gets a period starting today - the anchor is in the past by then and the expired
        // grant is invisible to the subquery, so neither can drag the new period backwards.
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

    // ------------------------------------------- the merged login query and the phase-aware gate

    @Test
    void theLoginQueryCarriesThePhaseSoTheProxyNeverMakesASecondRoundTrip() {
        // docs/season-phases.md:61 - "one database round trip on the login path carries both the
        // access state and the phase". This is that requirement as an assertion: the phase comes
        // back on the same record, for a linked account and for a UUID nobody has ever seen.
        directory.link(DISCORD_ID, MC_UUID);
        phase(SeasonPhase.START_EVENT);

        assertEquals(SeasonPhase.START_EVENT, directory.accessState(MC_UUID).phase());
        assertEquals(SeasonPhase.START_EVENT, directory.accessState(UUID.randomUUID()).phase(),
                "an unlinked UUID still has to learn the phase - the disconnect screen depends on it");
    }

    @Test
    void aLinkedMemberWithNoAccessGetsInBeforeTheSmpAndNotAfterIt() {
        // The whole reason the phase model exists (docs/season-phases.md, the phase table): the
        // pre-event and the start event are free for anyone who has linked their account.
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
            assertFalse(directory.accessState(stranger).mayJoin(),
                    "linking is the one requirement no phase waives, and " + each + " is no exception");
        }
    }

    @Test
    void aBannedMemberIsRefusedInEveryPhaseEvenWithAccessAndTheAdminFlag() {
        directory.link(DISCORD_ID, MC_UUID);
        directory.grantAccess(DISCORD_ID, 30, AccessSource.PURCHASE, null);
        directory.setAdmin(DISCORD_ID, true);
        directory.setMemberState(DISCORD_ID, MemberState.BANNED);

        for (final SeasonPhase each : SeasonPhase.values()) {
            phase(each);
            assertFalse(directory.accessState(MC_UUID).mayJoin(),
                    "a ban outranks paid access and the admin flag, in " + each);
        }
    }

    @Test
    void maintenanceAdmitsAnyLinkedMemberSoTheProxyCanHoldThemInLimbo() {
        // Reversed 2026-08-31. This used to assert "MAINTENANCE is admins only"; docs/season-phases
        // .md left "disconnect OR hold in limbo" open while its own phase table already said
        // non-admins land in `limbo`, and the owner settled it on holding them. Admission is
        // therefore identical to the two event phases, and the admin flag has moved out of
        // mayJoin() entirely - it now only decides where a player goes, which is network-control's
        // PhaseRouting and not this record's business.
        directory.link(DISCORD_ID, MC_UUID);
        phase(SeasonPhase.MAINTENANCE);

        assertTrue(directory.accessState(MC_UUID).mayJoin(),
                "a linked member is let in during maintenance and then routed to limbo");

        directory.grantAccess(DISCORD_ID, 30, AccessSource.PURCHASE, null);
        assertTrue(directory.accessState(MC_UUID).mayJoin(), "buying access changes nothing here");

        directory.setAdmin(DISCORD_ID, true);
        assertTrue(directory.accessState(MC_UUID).mayJoin(), "and neither does the admin flag");
    }

    @Test
    void anAdminWithoutAccessStillGetsIntoMaintenanceButNotIntoTheSmp() {
        directory.link(DISCORD_ID, MC_UUID);
        directory.setAdmin(DISCORD_ID, true);

        phase(SeasonPhase.MAINTENANCE);
        assertTrue(directory.accessState(MC_UUID).mayJoin(),
                "an admin gets in during maintenance - as does everybody else, since 2026-08-31");

        phase(SeasonPhase.SMP);
        assertFalse(directory.accessState(MC_UUID).mayJoin(),
                "the admin flag is not a free access period: SMP is the one phase that asks every "
                        + "linked member for access, admin or not");
    }

    @Test
    void aDeletedPhaseRowReadsAsMaintenanceRatherThanLookingLikeAnUnlinkedAccount() {
        // The phase now rides on the login query, so the query has to survive the one row it reads
        // being gone. Two things must hold: the account still reads as linked (otherwise every
        // player would be handed a link code they do not need), and the phase reads as MAINTENANCE.
        //
        // Since the 2026-08-31 reversal MAINTENANCE is no longer "the state that lets nobody in" -
        // it is the state that puts everybody somewhere harmless. A proxy that cannot read the
        // phase therefore parks players in limbo rather than guessing them onto a game server, and
        // mayJoin() is true here where it used to be false.
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

    // ---------------------------------------------------------------- the admin flag (V4)

    @Test
    void nobodyIsAnAdminUntilTheMirrorSaysSo() {
        directory.link(DISCORD_ID, MC_UUID);

        assertFalse(directory.accessState(MC_UUID).admin(),
                "the column defaults to false - a user the mirror has never run for is not an admin");
    }

    @Test
    void theAdminFlagRidesAlongOnTheQueryTheLoginPathAlreadyMakes() {
        directory.link(DISCORD_ID, MC_UUID);
        directory.setAdmin(DISCORD_ID, true);

        final AccessState state = directory.accessState(MC_UUID);

        assertTrue(state.admin(),
                "this is what MAINTENANCE and the proxy's emergency /phase command are authorised by");
        assertFalse(state.mayJoin(),
                "being an admin is not access: in SMP an admin without a running grant is refused "
                        + "like anybody else. MAINTENANCE is the only phase the flag lets somebody in");
    }

    @Test
    void theAdminFlagIsClearedAgainUnlikeDonor() {
        directory.link(DISCORD_ID, MC_UUID);
        directory.setAdmin(DISCORD_ID, true);
        directory.setDonor(DISCORD_ID, true);

        directory.setAdmin(DISCORD_ID, false);

        final AccessState state = directory.accessState(MC_UUID);
        assertFalse(state.admin(), "losing the Discord role has to lose the permission");
        assertTrue(state.donor(), "the donor flag is permanent, and clearing admin must not touch it");
    }

    @Test
    void settingTheAdminFlagCreatesTheUserRowIfItIsNotThereYet() {
        directory.setAdmin("400000000000000001", true);

        assertEquals(1, count("SELECT count(*) FROM discord_user WHERE discord_id = '400000000000000001' AND admin"));
    }

    // ---------------------------------------------------------------- the join-time locale component

    @Test
    void playerLocalesReadsTheLanguageFromTheDatabaseAtJoin() {
        directory.link(DISCORD_ID, MC_UUID);
        directory.setLocale(DISCORD_ID, Locale.GERMAN);

        // The wiring every module uses: the access directory is the LocaleSource.
        final PlayerLocales locales = new PlayerLocales(directory::locale);

        assertEquals(Locale.GERMAN, locales.join(MC_UUID));
        assertEquals(Locale.GERMAN, locales.of(MC_UUID));
    }

    @Test
    void playerLocalesHoldsTheLanguageForTheSessionAndPicksAChangeUpOnTheNextJoin() {
        directory.link(DISCORD_ID, MC_UUID);
        directory.setLocale(DISCORD_ID, Locale.GERMAN);

        final PlayerLocales locales = new PlayerLocales(directory::locale);
        locales.join(MC_UUID);

        // The player picks the English role in Discord; the bot mirrors it.
        directory.setLocale(DISCORD_ID, Locale.ENGLISH);
        assertEquals(Locale.GERMAN, locales.of(MC_UUID),
                "docs/i18n.md: a language changed mid-session takes effect on the next join, which is "
                        + "the trade for not re-querying on every message");

        locales.quit(MC_UUID);
        assertEquals(Locale.ENGLISH, locales.join(MC_UUID));
    }

    @Test
    void playerLocalesFallsBackToEnglishForAnAccountNobodyHasLinked() {
        final PlayerLocales locales = new PlayerLocales(directory::locale);

        assertEquals(Locale.ENGLISH, locales.join(UUID.randomUUID()));
    }

    // ---------------------------------------------------------------- player_playtime (V4)

    @Test
    void playtimeHangsOffDiscordUserAndNotOffTheMinecraftUuid() throws SQLException {
        final SQLException orphan = assertThrows(SQLException.class,
                () -> executeChecked("INSERT INTO player_playtime (discord_id, seconds) VALUES ('999999999999999999', 60)"));
        assertTrue(orphan.getMessage().contains("player_playtime_discord_id_fkey"), orphan.getMessage());

        directory.ensureUser(DISCORD_ID);
        executeChecked("INSERT INTO player_playtime (discord_id, seconds) VALUES ('" + DISCORD_ID + "', 60)");
        assertEquals(1, count("SELECT count(*) FROM player_playtime WHERE seconds = 60"));
    }

    @Test
    void playtimeIsAnIntegerCountOfSecondsThatCannotGoBackwardsPastZero() {
        directory.ensureUser(DISCORD_ID);

        final SQLException negative = assertThrows(SQLException.class,
                () -> executeChecked("INSERT INTO player_playtime (discord_id, seconds) VALUES ('" + DISCORD_ID + "', -1)"));
        assertTrue(negative.getMessage().contains("player_playtime_seconds_not_negative"), negative.getMessage());

        // Seconds, not an interval: the proxy's periodic flush is a plain addition, and no part of
        // it is calendar arithmetic in whatever time zone the writing JVM happens to be in.
        execute("INSERT INTO player_playtime (discord_id, seconds) VALUES ('" + DISCORD_ID + "', 0)");
        execute("UPDATE player_playtime SET seconds = seconds + 86400, updated = now() WHERE discord_id = '"
                + DISCORD_ID + "'");
        assertEquals(86400, count("SELECT seconds FROM player_playtime WHERE discord_id = '" + DISCORD_ID + "'"));
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

    @Test
    @DisplayName("an open purchase is readable from outside the bot, tab or no tab")
    void theOpenPurchaseIsReadable() throws SQLException {
        // What /smp access prints as its third line, and the reason that command exists: "has not
        // paid" and "is halfway through paying" produce the same disconnect screen. Driven against
        // a real database because the whole of it is SQL plus a constructor mapper - a column list
        // that does not match the record is exactly the kind of thing that compiles, passes every
        // unit test, and throws the first time an admin runs it.
        directory.ensureUser(DISCORD_ID);
        assertTrue(directory.openPayment(DISCORD_ID).isEmpty(),
                "an account that has started nothing has no open purchase");

        insertOpenRequest(DISCORD_ID, "NT-A1B2C3");

        final var pending = directory.openPayment(DISCORD_ID).orElseThrow();
        assertEquals("NT-A1B2C3", pending.reference());
        assertEquals(30, pending.days());
        assertEquals(300, pending.amountCents());
        assertEquals("3.00", pending.amount());
        assertNotNull(pending.created());
        assertFalse(pending.hasTab(),
                "bunq_tab_id IS NULL is the difference between 'chose 30 days' and 'asked for a"
                        + " payment link', and it is what an admin chasing a stuck purchase needs");

        execute("UPDATE payment_request SET bunq_tab_id = 4242 WHERE reference = 'NT-A1B2C3'");
        assertTrue(directory.openPayment(DISCORD_ID).orElseThrow().hasTab());

        // Only OPEN rows. A settled purchase is not something in progress, and reporting one as
        // pending would send an admin looking for a payment that already arrived. `settled` moves
        // with the status because payment_request_settled_iff_paid ties the two together - which is
        // itself worth knowing here: there is no way to write a PAID row that looks unsettled.
        execute("UPDATE payment_request SET status = 'PAID', settled = now()"
                + " WHERE reference = 'NT-A1B2C3'");
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

    private static long count(final String sql) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             var rs = statement.executeQuery(sql)) {
            assertTrue(rs.next(), "expected a row from: " + sql);
            return rs.getLong(1);
        } catch (final SQLException exception) {
            throw new IllegalStateException("Test query failed: " + sql, exception);
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

    // ---------------------------------------------------------------- M9: the admin flag notifies

    @Test
    @DisplayName("M9: setting the admin flag notifies nordtal_admin with the Discord id")
    void theAdminFlagAnnouncesItself() throws Exception {
        // The proxy fills LoginRoster from the login query and never again, so a revoked admin kept
        // every power until they disconnected. This is the signal that lets a revocation reach a
        // player who is already online, and it rides inside the write - as the phase's does - so it
        // is only ever emitted for something that committed.
        try (Connection listening = dataSource.getConnection()) {
            try (Statement statement = listening.createStatement()) {
                statement.execute("LISTEN nordtal_admin");
            }

            directory.setAdmin(DISCORD_ID, true);

            final org.postgresql.PGNotification[] arrived = listening
                    .unwrap(org.postgresql.PGConnection.class)
                    .getNotifications(5000);

            assertNotNull(arrived, "no notification arrived on nordtal_admin within 5s");
            assertEquals(1, arrived.length);
            assertEquals("nordtal_admin", arrived[0].getName());
            assertEquals(DISCORD_ID, arrived[0].getParameter(),
                    "the payload is the Discord id - the proxy does not act on it, but a payload"
                            + " that names the wrong account is worse than none");
        }
    }

    @Test
    @DisplayName("M9: a revocation notifies as loudly as a grant")
    void losingTheFlagNotifiesToo() throws Exception {
        directory.setAdmin(DISCORD_ID, true);

        try (Connection listening = dataSource.getConnection()) {
            try (Statement statement = listening.createStatement()) {
                statement.execute("LISTEN nordtal_admin");
            }

            directory.setAdmin(DISCORD_ID, false);

            final org.postgresql.PGNotification[] arrived = listening
                    .unwrap(org.postgresql.PGConnection.class)
                    .getNotifications(5000);

            assertNotNull(arrived, "a revocation produced no notification, which is the direction"
                    + " that actually matters");
            assertEquals(DISCORD_ID, arrived[0].getParameter());
        }
    }

    @Test
    @DisplayName("M9: admins() is the whole set the proxy re-derives every session from")
    void theAdminSetIsReadableInOneQuery() {
        assertTrue(directory.admins().isEmpty());

        directory.setAdmin(DISCORD_ID, true);
        directory.setAdmin("100000000000000002", true);
        directory.setAdmin("100000000000000003", false);

        assertEquals(java.util.Set.of(DISCORD_ID, "100000000000000002"), directory.admins(),
                "one query for the whole set is what makes the refresh idempotent - a lost"
                        + " notification then costs latency rather than correctness");

        directory.setAdmin(DISCORD_ID, false);
        assertEquals(java.util.Set.of("100000000000000002"), directory.admins());
    }

    @Test
    @DisplayName("the backends ask for admins by Minecraft account, and get only the linked ones")
    void adminsAreAlsoReadableAsMinecraftAccounts() {
        // The proxy knows a session by its Discord id because the login gate resolved it there. A
        // Paper server knows only a UUID, and the join through account_link is the one thing that
        // connects them - so this is a second query rather than a mapping of admins().
        assertTrue(directory.adminMinecraftAccounts().isEmpty());

        directory.setAdmin(DISCORD_ID, true);
        assertTrue(directory.adminMinecraftAccounts().isEmpty(),
                "an admin with no account link cannot be online anywhere, so nothing on a backend"
                        + " should be told about them");

        directory.link(DISCORD_ID, MC_UUID);
        assertEquals(java.util.Set.of(MC_UUID), directory.adminMinecraftAccounts());

        // The direction that actually matters: this is what removes operator from somebody who is
        // online right now, without waiting for them to disconnect.
        directory.setAdmin(DISCORD_ID, false);
        assertTrue(directory.adminMinecraftAccounts().isEmpty(),
                "a revoked admin has to leave this set immediately - AdminWatch hands it straight to"
                        + " AdminOperators#refresh, and whoever is not in it loses operator");
    }

}
