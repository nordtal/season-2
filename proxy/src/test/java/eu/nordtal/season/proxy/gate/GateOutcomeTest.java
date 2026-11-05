package eu.nordtal.season.proxy.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.common.season.SeasonMode;
import eu.nordtal.season.common.season.SeasonState;
import eu.nordtal.season.database.access.AccessState;
import eu.nordtal.season.database.access.MemberState;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** The gate's order, asserted over every opening, mode and maintenance flag of the season. */
class GateOutcomeTest {

    private static final UUID PLAYER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String DISCORD_ID = "300000000000000001";
    private static final Instant NOW = Instant.parse("2026-11-05T12:00:00Z");
    private static final Instant AHEAD = NOW.plus(Duration.ofDays(3));
    private static final Instant PAST = NOW.minus(Duration.ofDays(1));

    private static final SeasonState CLOSED = new SeasonState(null, false, SeasonMode.SMP);
    private static final SeasonState COUNTDOWN = new SeasonState(AHEAD, false, SeasonMode.SMP);
    private static final SeasonState SMP = new SeasonState(PAST, false, SeasonMode.SMP);
    private static final SeasonState HUNGER_GAMES = new SeasonState(PAST, false, SeasonMode.HUNGER_GAMES);

    // the whole table at once

    @Test
    void theOrderTableIsWhatThisClassProduces() {
        // One row per season: member without anything bought, member with a running period, admin.
        row(CLOSED, GateOutcome.CLOSED, GateOutcome.CLOSED, GateOutcome.ALLOW);
        row(COUNTDOWN, GateOutcome.COUNTDOWN_BUY, GateOutcome.COUNTDOWN_READY, GateOutcome.ALLOW);
        row(COUNTDOWN.withMode(SeasonMode.HUNGER_GAMES), ready(), ready(), GateOutcome.ALLOW);
        row(SMP, GateOutcome.NO_ACCESS, GateOutcome.ALLOW, GateOutcome.ALLOW);
        row(HUNGER_GAMES, GateOutcome.ALLOW, GateOutcome.ALLOW, GateOutcome.ALLOW);
        row(SMP.withMaintenance(true), GateOutcome.NO_ACCESS, GateOutcome.ALLOW, GateOutcome.ALLOW);
        row(HUNGER_GAMES.withMaintenance(true), GateOutcome.ALLOW, GateOutcome.ALLOW, GateOutcome.ALLOW);
        row(CLOSED.withMaintenance(true), GateOutcome.CLOSED, GateOutcome.CLOSED, GateOutcome.ALLOW);
    }

    private static GateOutcome ready() {
        return GateOutcome.COUNTDOWN_READY;
    }

    private static void row(
            final SeasonState season,
            final GateOutcome memberNothingBought,
            final GateOutcome memberWithAccess,
            final GateOutcome admin) {
        assertEquals(GateOutcome.NOT_LINKED, of(AccessState.unlinked(PLAYER, season)), season + " / unlinked");
        assertEquals(GateOutcome.NOT_MEMBER, of(state(season, MemberState.LEFT, true, true)), season + " / left");
        assertEquals(GateOutcome.NOT_MEMBER, of(state(season, MemberState.BANNED, true, true)), season + " / banned");
        assertEquals(memberNothingBought, of(member(season, false)), season + " / member without access");
        assertEquals(memberWithAccess, of(member(season, true)), season + " / member with access");
        assertEquals(admin, of(state(season, MemberState.MEMBER, false, true)), season + " / admin");
    }

    // link and membership come first

    @Test
    void anUnlinkedAccountIsToldToLinkEvenBeforeTheOpeningAndDuringMaintenance() {
        // "Here is your link code" is more useful than "the network is closed".
        for (final SeasonState season : seasons()) {
            assertEquals(GateOutcome.NOT_LINKED, of(AccessState.unlinked(PLAYER, season)), season.toString());
        }
    }

    @Test
    void aBanOutranksPaidAccessAndTheAdminFlag() {
        for (final SeasonState season : seasons()) {
            assertEquals(
                    GateOutcome.NOT_MEMBER,
                    of(state(season, MemberState.BANNED, true, true)),
                    "a banned admin is still banned - " + season);
        }
    }

    // the admin

    @Test
    void anAdminIsLetInBeforeTheOpeningAndWithoutAccess() {
        // Somebody has to be able to get on the network before it opens.
        for (final SeasonState season : seasons()) {
            assertEquals(GateOutcome.ALLOW, of(state(season, MemberState.MEMBER, false, true)), season.toString());
        }
    }

    // the opening

    @Test
    void aSeasonWithoutALaunchDateShowsTheClosedScreen() {
        assertEquals(GateOutcome.CLOSED, of(member(CLOSED, true)));
        assertEquals(GateOutcome.CLOSED, of(member(CLOSED.withMode(SeasonMode.HUNGER_GAMES), false)));
    }

    @Test
    void aLaunchDateAheadShowsTheCountdown() {
        assertEquals(GateOutcome.COUNTDOWN_BUY, of(member(COUNTDOWN, false)));
        assertEquals(GateOutcome.COUNTDOWN_READY, of(member(COUNTDOWN, true)));
    }

    @Test
    void theCountdownAsksWhetherAccessWasBoughtAndNotWhetherItIsRunning() {
        // A period bought before the opening waits rather than runs; accessActive() would ask them to buy.
        final AccessState boughtButNotRunning = new AccessState(
                PLAYER,
                DiscordId.of(DISCORD_ID),
                MemberState.MEMBER,
                false,
                AHEAD.plus(Duration.ofDays(30)),
                false,
                false,
                false,
                0L,
                Locale.ENGLISH,
                COUNTDOWN);

        assertEquals(GateOutcome.COUNTDOWN_READY, of(boughtButNotRunning));
    }

    @Test
    void theLaunchInstantItselfOpensTheNetwork() {
        final SeasonState launchingNow = new SeasonState(NOW, false, SeasonMode.SMP);

        assertEquals(GateOutcome.ALLOW, of(member(launchingNow, true)));
        assertEquals(GateOutcome.COUNTDOWN_READY, GateOutcome.of(member(launchingNow, true), NOW.minusMillis(1)));
    }

    @Test
    void everyBeforeLaunchOutcomeIsMarkedSo() {
        assertTrue(GateOutcome.CLOSED.beforeLaunch());
        assertTrue(GateOutcome.COUNTDOWN_BUY.beforeLaunch());
        assertTrue(GateOutcome.COUNTDOWN_READY.beforeLaunch());
        assertFalse(GateOutcome.NO_ACCESS.beforeLaunch());
        assertFalse(GateOutcome.ALLOW.beforeLaunch());
    }

    // the mode

    @Test
    void hungerGamesAdmitsEveryLinkedMemberAndSmpAsksForAccess() {
        assertEquals(GateOutcome.ALLOW, of(member(HUNGER_GAMES, false)));
        assertEquals(GateOutcome.NO_ACCESS, of(member(SMP, false)));
        assertEquals(GateOutcome.ALLOW, of(member(SMP, true)));
    }

    // maintenance

    @Test
    void maintenanceAdmitsAMemberSoTheWaitingRoomCanHoldThem() {
        // Maintenance is a routing decision: the member is admitted and waits in the waiting room.
        final AccessState member = member(SMP.withMaintenance(true), true);

        assertEquals(GateOutcome.ALLOW, of(member));
        assertTrue(member.waits(NOW));
    }

    @Test
    void maintenanceDoesNotLetInWhoTheModeRefuses() {
        // Admitting a player only to refuse them once maintenance ends would waste their wait.
        assertEquals(GateOutcome.NO_ACCESS, of(member(SMP.withMaintenance(true), false)));
    }

    @Test
    void anAdminDoesNotWaitForMaintenance() {
        assertFalse(state(SMP.withMaintenance(true), MemberState.MEMBER, false, true)
                .waits(NOW));
    }

    // the boolean form agrees

    @Test
    void mayJoinAgreesWithTheOutcomeForEverySeasonAndEveryAccount() {
        // mayJoin() is the same table as a boolean; if they drift, a player let in is kicked a minute later.
        for (final SeasonState season : seasons()) {
            for (final MemberState membership : MemberState.values()) {
                for (final boolean accessActive : new boolean[] {false, true}) {
                    for (final boolean admin : new boolean[] {false, true}) {
                        final AccessState state = state(season, membership, accessActive, admin);
                        assertEquals(
                                of(state).allowed(),
                                state.mayJoin(NOW),
                                season + "/" + membership + "/access=" + accessActive + "/admin=" + admin);
                    }
                }
            }
            assertFalse(AccessState.unlinked(PLAYER, season).mayJoin(NOW));
        }
    }

    @Test
    void aSeasonThatWasNeverReadLetsOnlyAnAdminIn() {
        // A null season reads as UNREAD: launched long ago, in maintenance, so members wait.
        final AccessState unread = new AccessState(
                PLAYER,
                DiscordId.of(DISCORD_ID),
                MemberState.MEMBER,
                true,
                null,
                false,
                false,
                false,
                0L,
                Locale.ENGLISH,
                null);

        assertEquals(SeasonState.UNREAD, unread.season());
        assertEquals(GateOutcome.ALLOW, of(unread));
        assertTrue(unread.waits(NOW));
    }

    // the player limit

    @Test
    void aFullNetworkRefusesAMemberTheTableWouldAdmit() {
        assertEquals(GateOutcome.FULL, GateOutcome.of(member(HUNGER_GAMES, false), NOW, 20, 20));
        assertEquals(GateOutcome.FULL, GateOutcome.of(member(SMP, true), NOW, 21, 20));
    }

    @Test
    void theLastFreePlaceStillAdmits() {
        assertEquals(GateOutcome.ALLOW, GateOutcome.of(member(SMP, true), NOW, 19, 20));
    }

    @Test
    void anAdminEntersAFullNetworkToFixIt() {
        assertEquals(GateOutcome.ALLOW, GateOutcome.of(state(SMP, MemberState.MEMBER, false, true), NOW, 20, 20));
    }

    @Test
    void aFullNetworkNeverHidesTheRefusalThatComesFirst() {
        assertEquals(GateOutcome.NOT_LINKED, GateOutcome.of(AccessState.unlinked(PLAYER, SMP), NOW, 20, 20));
        assertEquals(GateOutcome.NO_ACCESS, GateOutcome.of(member(SMP, false), NOW, 20, 20));
        assertEquals(GateOutcome.COUNTDOWN_BUY, GateOutcome.of(member(COUNTDOWN, false), NOW, 20, 20));
    }

    @Test
    void withoutAFullNetworkTheLimitChangesNothing() {
        for (final SeasonState season : seasons()) {
            for (final boolean accessActive : new boolean[] {false, true}) {
                final AccessState state = member(season, accessActive);
                assertEquals(of(state), GateOutcome.of(state, NOW, 0, 20), season + "/" + accessActive);
            }
        }
    }

    // the database does not answer

    @Test
    void aPlayerTheCacheDoesNotKnowGetsTheTroubleScreen() {
        assertEquals(GateOutcome.TROUBLE, GateOutcome.withoutDatabase(false, 0, 20));
        assertEquals(GateOutcome.TROUBLE, GateOutcome.withoutDatabase(false, 20, 20));
    }

    @Test
    void aPlayerTheCacheRemembersIsLetInWhileThereIsRoom() {
        assertEquals(GateOutcome.ALLOW, GateOutcome.withoutDatabase(true, 19, 20));
    }

    @Test
    void aRememberedPlayerIsRefusedOnAFullNetworkSinceTheAdminFlagCannotBeRead() {
        assertEquals(GateOutcome.FULL, GateOutcome.withoutDatabase(true, 20, 20));
    }

    // helpers

    private static List<SeasonState> seasons() {
        return List.of(
                CLOSED,
                COUNTDOWN,
                COUNTDOWN.withMode(SeasonMode.HUNGER_GAMES),
                SMP,
                HUNGER_GAMES,
                SMP.withMaintenance(true),
                HUNGER_GAMES.withMaintenance(true),
                CLOSED.withMaintenance(true));
    }

    private static GateOutcome of(final AccessState state) {
        return GateOutcome.of(state, NOW);
    }

    private static AccessState member(final SeasonState season, final boolean accessActive) {
        return state(season, MemberState.MEMBER, accessActive, false);
    }

    private static AccessState state(
            final SeasonState season, final MemberState membership, final boolean accessActive, final boolean admin) {
        final @Nullable Instant validUntil = accessActive ? NOW.plus(Duration.ofDays(1)) : null;
        return new AccessState(
                PLAYER,
                DiscordId.of(DISCORD_ID),
                membership,
                accessActive,
                validUntil,
                false,
                admin,
                false,
                0L,
                Locale.ENGLISH,
                season);
    }
}
