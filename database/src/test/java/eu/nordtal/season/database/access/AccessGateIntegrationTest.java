package eu.nordtal.season.database.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.common.season.SeasonMode;
import eu.nordtal.season.common.season.SeasonState;
import eu.nordtal.season.database.TestDatabase;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** Exercises the season half of {@link AccessState}: who the gate lets in, closed, counting down and launched. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccessGateIntegrationTest {

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

    private static void maintenance(final boolean on) {
        execute("UPDATE season_state SET maintenance = " + on + " WHERE id");
    }

    private static void mode(final SeasonMode mode) {
        execute("UPDATE season_state SET mode = '" + mode.name() + "' WHERE id");
    }

    /** Runs {@code check} closed, counting down, launched and in maintenance, each on both modes. */
    private static void inEverySeason(final java.util.function.Consumer<String> check) {
        for (final SeasonMode each : SeasonMode.values()) {
            mode(each);
            closed();
            check.accept("closed on " + each);
            launchIn(Duration.ofDays(3));
            check.accept("counting down on " + each);
            launchIn(Duration.ofDays(-3));
            check.accept("launched on " + each);
            maintenance(true);
            check.accept("in maintenance on " + each);
            maintenance(false);
        }
    }

    @Test
    void theLoginQueryCarriesTheSeasonSoTheProxyNeverMakesASecondRoundTrip() {
        // One round trip on the login path returns the access state and the season's three switches.
        directory.link(DiscordId.of(DISCORD_ID), MC_UUID);
        launchIn(Duration.ofDays(2));
        maintenance(true);
        mode(SeasonMode.HUNGER_GAMES);

        final AccessState linked = directory.accessState(MC_UUID);
        final AccessState stranger = directory.accessState(UUID.randomUUID());

        assertWithinSeconds(
                Instant.now().plus(Duration.ofDays(2)), linked.season().launch(), 60);
        assertTrue(linked.season().maintenance());
        assertEquals(SeasonMode.HUNGER_GAMES, linked.season().mode());
        assertEquals(
                linked.season(),
                stranger.season(),
                "an unlinked UUID still has to learn the season, as the disconnect screen depends on it");
    }

    @Test
    void aLinkedMemberWithNoAccessGetsIntoTheHungerGamesButNotIntoTheSmp() {
        directory.link(DiscordId.of(DISCORD_ID), MC_UUID);

        mode(SeasonMode.HUNGER_GAMES);
        assertTrue(directory.accessState(MC_UUID).mayJoin(Instant.now()), "the hunger games need no access");

        mode(SeasonMode.SMP);
        assertFalse(directory.accessState(MC_UUID).mayJoin(Instant.now()), "the smp is what access is for");
    }

    @Test
    void beforeTheLaunchOnlyAnAdminGetsInWhateverWasBought() {
        directory.link(DiscordId.of(DISCORD_ID), MC_UUID);
        directory.grantAccess(DiscordId.of(DISCORD_ID), 30, AccessSource.PURCHASE, null);

        for (final SeasonMode each : SeasonMode.values()) {
            mode(each);
            closed();
            assertFalse(directory.accessState(MC_UUID).mayJoin(Instant.now()), "closed on " + each);
            launchIn(Duration.ofHours(1));
            assertFalse(directory.accessState(MC_UUID).mayJoin(Instant.now()), "counting down on " + each);
        }

        setAdmin(DiscordId.of(DISCORD_ID), true);
        closed();
        assertTrue(directory.accessState(MC_UUID).mayJoin(Instant.now()), "an admin is always let in");
    }

    @Test
    void aSetDateOpensTheSeasonByItselfWhenItPasses() {
        directory.link(DiscordId.of(DISCORD_ID), MC_UUID);
        mode(SeasonMode.HUNGER_GAMES);
        launchIn(Duration.ofMinutes(10));

        final AccessState state = directory.accessState(MC_UUID);

        assertFalse(state.mayJoin(Instant.now()));
        assertTrue(state.mayJoin(Instant.now().plus(Duration.ofMinutes(11))), "no switch is flipped at the launch");
    }

    @Test
    void anUnlinkedAccountIsRefusedInEverySeasonIncludingTheFreeOnes() {
        final UUID stranger = UUID.randomUUID();

        inEverySeason(each -> assertFalse(
                directory.accessState(stranger).mayJoin(Instant.now()),
                "linking is the one requirement nothing waives, also " + each));
    }

    @Test
    void aBannedMemberIsRefusedInEverySeasonEvenWithAccessAndTheAdminFlag() {
        directory.link(DiscordId.of(DISCORD_ID), MC_UUID);
        directory.grantAccess(DiscordId.of(DISCORD_ID), 30, AccessSource.PURCHASE, null);
        setAdmin(DiscordId.of(DISCORD_ID), true);
        directory.setMemberState(DiscordId.of(DISCORD_ID), MemberState.BANNED);

        inEverySeason(each -> assertFalse(
                directory.accessState(MC_UUID).mayJoin(Instant.now()),
                "a ban outranks paid access and the admin flag, " + each));
    }

    @Test
    void maintenanceHoldsWhoWouldBeAdmittedInTheWaitingRoomAndLetsAdminsThrough() {
        directory.link(DiscordId.of(DISCORD_ID), MC_UUID);
        directory.grantAccess(DiscordId.of(DISCORD_ID), 30, AccessSource.PURCHASE, null);
        maintenance(true);

        final AccessState member = directory.accessState(MC_UUID);
        assertTrue(member.mayJoin(Instant.now()), "a member with access is let in during maintenance");
        assertTrue(member.waits(Instant.now()), "and waits in the waiting room");

        directory.revokeAccess(DiscordId.of(DISCORD_ID));
        final AccessState lapsed = directory.accessState(MC_UUID);
        assertFalse(lapsed.mayJoin(Instant.now()), "maintenance admits nobody the smp would refuse");
        assertFalse(lapsed.waits(Instant.now()));

        setAdmin(DiscordId.of(DISCORD_ID), true);
        final AccessState admin = directory.accessState(MC_UUID);
        assertTrue(admin.mayJoin(Instant.now()));
        assertFalse(admin.waits(Instant.now()), "an admin plays through maintenance");
    }

    @Test
    void anAdminWithoutAccessGetsIntoTheSmpAndLosesItWithTheRole() {
        directory.link(DiscordId.of(DISCORD_ID), MC_UUID);
        setAdmin(DiscordId.of(DISCORD_ID), true);

        assertTrue(directory.accessState(MC_UUID).mayJoin(Instant.now()), "the admin flag is a free pass");
        setAdmin(DiscordId.of(DISCORD_ID), false);
        assertFalse(
                directory.accessState(MC_UUID).mayJoin(Instant.now()),
                "and losing the role loses the pass, with nothing bought underneath it");
    }

    @Test
    void aDeletedSeasonRowReadsAsUnreadRatherThanLookingLikeAnUnlinkedAccount() {
        // Without the row the account reads as linked and the season as UNREAD: launched, in maintenance.
        directory.link(DiscordId.of(DISCORD_ID), MC_UUID);
        directory.grantAccess(DiscordId.of(DISCORD_ID), 30, AccessSource.PURCHASE, null);
        execute("DELETE FROM season_state");
        try {
            final AccessState state = directory.accessState(MC_UUID);

            assertTrue(state.linked(), "a missing season row must not make a linked player look unlinked");
            assertTrue(state.accessActive());
            assertEquals(SeasonState.UNREAD, state.season());
            assertTrue(state.mayJoin(Instant.now()), "admitted");
            assertTrue(state.waits(Instant.now()), "and then held in the waiting room");
        } finally {
            execute("INSERT INTO season_state (launch, maintenance, mode) VALUES (now(), false, 'SMP')");
        }
    }

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
}
