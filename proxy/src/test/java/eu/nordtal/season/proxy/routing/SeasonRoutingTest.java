package eu.nordtal.season.proxy.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.common.season.SeasonMode;
import eu.nordtal.season.common.season.SeasonState;
import eu.nordtal.season.database.access.AccessState;
import eu.nordtal.season.database.access.MemberState;
import eu.nordtal.season.proxy.NetworkServers;
import eu.nordtal.season.proxy.gate.GateOutcome;
import eu.nordtal.season.proxy.routing.RouteDecision.Action;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Where the season state puts a player, asserted in memory.
 *
 * Maintenance holds an admitted non-admin in {@code limbo}; the server of the active mode takes everybody else.
 */
class SeasonRoutingTest {

    private static final UUID PLAYER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String DISCORD_ID = "300000000000000002";
    private static final Instant NOW = Instant.parse("2026-11-05T12:00:00Z");

    private static final SeasonState CLOSED = new SeasonState(null, false, SeasonMode.SMP);
    private static final SeasonState COUNTDOWN = new SeasonState(NOW.plus(Duration.ofDays(2)), false, SeasonMode.SMP);
    private static final SeasonState SMP = new SeasonState(NOW.minus(Duration.ofDays(1)), false, SeasonMode.SMP);
    private static final SeasonState HUNGER_GAMES = SMP.withMode(SeasonMode.HUNGER_GAMES);
    private static final SeasonState MAINTENANCE = SMP.withMaintenance(true);

    /** What a healthy proxy has registered. */
    private static final Set<String> ALL = Set.of("limbo", "hunger-games", "smp");

    private final SeasonRouting routing =
            new SeasonRouting(new NetworkServers("limbo", "limbo-standby", "hunger-games", "smp"));

    // the servers

    @Test
    void eachModeHasItsOwnServer() {
        final NetworkServers servers = new NetworkServers("limbo", "limbo-standby", "hunger-games", "smp");

        assertEquals("hunger-games", servers.forMode(SeasonMode.HUNGER_GAMES));
        assertEquals("smp", servers.forMode(SeasonMode.SMP));
    }

    @Test
    void aBlankServerNameIsRejectedWhereItIsCheapToNotice() {
        assertThrows(
                IllegalArgumentException.class, () -> new NetworkServers("", "limbo-standby", "hunger-games", "smp"));
        assertThrows(IllegalArgumentException.class, () -> new NetworkServers("limbo", "limbo-standby", " ", "smp"));
    }

    @Test
    void theNamesAreConfigurableEvenThoughTheMappingIsNot() {
        // velocity.toml chooses the names; which mode uses which is fixed.
        final NetworkServers renamed = new NetworkServers("wait", "wait-standby", "hg", "survival");

        assertEquals("hg", renamed.forMode(SeasonMode.HUNGER_GAMES));
        assertEquals("survival", renamed.forMode(SeasonMode.SMP));
    }

    // a change of the season state

    @Test
    void aMemberWithAccessGoesToTheServerOfTheActiveMode() {
        assertEquals(RouteDecision.connectTo("smp"), routing.decide(member(SMP, true), ALL, NOW));
        assertEquals(RouteDecision.connectTo("hunger-games"), routing.decide(member(HUNGER_GAMES, true), ALL, NOW));
    }

    @Test
    void hungerGamesTakesEveryLinkedMemberWithNothingBought() {
        assertEquals(RouteDecision.connectTo("hunger-games"), routing.decide(member(HUNGER_GAMES, false), ALL, NOW));
    }

    @Test
    void aSwitchToSmpDisconnectsAPlayerWithoutAccessAndNeverRedirectsThem() {
        // Not having bought access does not end by waiting.
        assertEquals(RouteDecision.of(Action.REFUSE_NO_ACCESS), routing.decide(member(SMP, false), ALL, NOW));
    }

    @Test
    void aPlainMemberInMaintenanceIsSentToLimboRatherThanRefused() {
        assertEquals(RouteDecision.connectTo("limbo"), routing.decide(member(MAINTENANCE, true), ALL, NOW));
        assertEquals(
                RouteDecision.connectTo("limbo"),
                routing.decide(member(MAINTENANCE.withMode(SeasonMode.HUNGER_GAMES), false), ALL, NOW));
    }

    @Test
    void anAdminStaysWhereTheAdminIs() {
        for (final SeasonState season : new SeasonState[] {CLOSED, COUNTDOWN, SMP, HUNGER_GAMES, MAINTENANCE}) {
            assertEquals(RouteDecision.of(Action.STAY), routing.decide(admin(season), ALL, NOW), season.toString());
        }
    }

    @Test
    void closingTheSeasonSendsAMemberToTheClosedScreen() {
        assertEquals(RouteDecision.of(Action.REFUSE_CLOSED), routing.decide(member(CLOSED, true), ALL, NOW));
        assertEquals(
                RouteDecision.of(Action.REFUSE_COUNTDOWN_READY), routing.decide(member(COUNTDOWN, true), ALL, NOW));
        assertEquals(RouteDecision.of(Action.REFUSE_COUNTDOWN_BUY), routing.decide(member(COUNTDOWN, false), ALL, NOW));
    }

    @Test
    void anUnlinkedOrBannedPlayerIsNeverRoutedAnywhere() {
        assertEquals(
                RouteDecision.of(Action.REFUSE_UNLINKED),
                routing.decide(AccessState.unlinked(PLAYER, MAINTENANCE), ALL, NOW));
        assertEquals(
                RouteDecision.of(Action.REFUSE_NOT_MEMBER),
                routing.decide(state(SMP, MemberState.BANNED, true, true), ALL, NOW));
    }

    @Test
    void maintenanceWithoutAWaitingRoomHasItsOwnScreen() {
        assertEquals(
                RouteDecision.of(Action.REFUSE_MAINTENANCE_UNAVAILABLE),
                routing.decide(member(MAINTENANCE, true), Set.of("smp", "hunger-games"), NOW));
    }

    @Test
    void aMissingServerOfTheModeIsItsOwnScreen() {
        assertEquals(
                RouteDecision.of(Action.REFUSE_NO_SERVER), routing.decide(member(SMP, true), Set.of("limbo"), NOW));
    }

    @Test
    void everyRefusalOfTheGateHasARouteRefusal() {
        for (final GateOutcome outcome : GateOutcome.values()) {
            if (outcome == GateOutcome.FULL || outcome == GateOutcome.TROUBLE) {
                assertThrows(IllegalStateException.class, () -> SeasonRouting.refusalOf(outcome));
            } else if (outcome == GateOutcome.ALLOW) {
                assertNull(SeasonRouting.refusalOf(outcome));
            } else {
                final RouteDecision refusal = SeasonRouting.refusalOf(outcome);
                assertNotNull(refusal, outcome.toString());
                assertNull(refusal.server(), outcome.toString());
            }
        }
    }

    @Test
    void aRefusalMayNotCarryAServerAndAConnectionMayNotOmitOne() {
        assertThrows(IllegalArgumentException.class, () -> new RouteDecision(Action.REFUSE_NO_ACCESS, "smp"));
        assertThrows(IllegalArgumentException.class, () -> new RouteDecision(Action.CONNECT, null));
    }

    // the login

    @Test
    void everyLoginLandsInTheWaitingRoomWhateverTheSeason() {
        // velocity.toml's own list would skip the pack.
        for (final SeasonState season : new SeasonState[] {SMP, HUNGER_GAMES, MAINTENANCE, CLOSED}) {
            for (final boolean admin : new boolean[] {false, true}) {
                assertEquals(
                        RouteDecision.connectTo("limbo"),
                        routing.decideInitial(season, admin, ALL),
                        season + "/admin=" + admin);
            }
        }
    }

    @Test
    void anAdminWithoutAWaitingRoomGoesStraightToTheServerOfTheMode() {
        // If a missing limbo locked admins out, nobody could register one.
        assertEquals(RouteDecision.connectTo("smp"), routing.decideInitial(MAINTENANCE, true, Set.of("smp")));
        assertEquals(
                RouteDecision.connectTo("hunger-games"),
                routing.decideInitial(HUNGER_GAMES, true, Set.of("smp", "hunger-games")));
    }

    @Test
    void aMissingWaitingRoomRefusesEveryOtherLogin() {
        assertEquals(
                RouteDecision.of(Action.REFUSE_MAINTENANCE_UNAVAILABLE),
                routing.decideInitial(MAINTENANCE, false, Set.of("smp")));
        assertEquals(RouteDecision.of(Action.REFUSE_NO_SERVER), routing.decideInitial(SMP, false, Set.of("smp")));
        assertEquals(RouteDecision.of(Action.REFUSE_NO_SERVER), routing.decideInitial(SMP, true, Set.of()));
    }

    // the release through the gate

    @Test
    void aReleaseGoesToTheServerOfTheActiveModeAdminsIncluded() {
        // After a mode switch the release goes to the new mode, not to the server the player came from.
        assertEquals(
                RouteDecision.connectTo("hunger-games"), routing.decideRelease(member(HUNGER_GAMES, false), ALL, NOW));
        assertEquals(RouteDecision.connectTo("hunger-games"), routing.decideRelease(admin(HUNGER_GAMES), ALL, NOW));
        assertEquals(RouteDecision.connectTo("smp"), routing.decideRelease(admin(MAINTENANCE), ALL, NOW));
    }

    @Test
    void aReleaseOntoSmpRefusesAPlayerWithoutAccess() {
        assertEquals(RouteDecision.of(Action.REFUSE_NO_ACCESS), routing.decideRelease(member(SMP, false), ALL, NOW));
    }

    @Test
    void aReleaseKeepsAPlayerWhomMaintenanceHolds() {
        assertEquals(RouteDecision.of(Action.STAY), routing.decideRelease(member(MAINTENANCE, true), ALL, NOW));
    }

    @Test
    void aReleaseBeforeTheOpeningShowsTheScreenOfTheGate() {
        assertEquals(RouteDecision.of(Action.REFUSE_CLOSED), routing.decideRelease(member(CLOSED, true), ALL, NOW));
        assertEquals(
                RouteDecision.of(Action.REFUSE_COUNTDOWN_BUY),
                routing.decideRelease(member(COUNTDOWN, false), ALL, NOW));
    }

    @Test
    void aReleaseToAMissingServerIsRefused() {
        assertEquals(
                RouteDecision.of(Action.REFUSE_NO_SERVER),
                routing.decideRelease(member(SMP, true), Set.of("limbo"), NOW));
    }

    @Test
    void aReleaseWithoutTheAccessOfThePlayerFollowsMaintenanceAndTheMode() {
        assertEquals(RouteDecision.of(Action.STAY), routing.decideRemembered(MAINTENANCE, false, ALL));
        assertEquals(RouteDecision.connectTo("smp"), routing.decideRemembered(MAINTENANCE, true, ALL));
        assertEquals(RouteDecision.connectTo("hunger-games"), routing.decideRemembered(HUNGER_GAMES, false, ALL));
        assertEquals(RouteDecision.of(Action.REFUSE_NO_SERVER), routing.decideRemembered(SMP, false, Set.of("limbo")));
    }

    // helpers

    private static AccessState member(final SeasonState season, final boolean accessActive) {
        return state(season, MemberState.MEMBER, accessActive, false);
    }

    private static AccessState admin(final SeasonState season) {
        return state(season, MemberState.MEMBER, false, true);
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
