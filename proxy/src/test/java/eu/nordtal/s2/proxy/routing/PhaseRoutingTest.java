package eu.nordtal.s2.proxy.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.common.access.AccessState;
import eu.nordtal.s2.common.access.MemberState;
import eu.nordtal.s2.proxy.PhaseServers;
import eu.nordtal.s2.proxy.routing.RouteDecision.Action;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Where each phase puts a player, asserted in memory.
 *
 * {@code MAINTENANCE} holds a non-admin in {@code limbo}; {@code SMP} disconnects one without access.
 */
class PhaseRoutingTest {

    private static final UUID PLAYER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String DISCORD_ID = "300000000000000002";

    /** What a healthy proxy has registered. */
    private static final Set<String> ALL = Set.of("limbo", "hunger-games", "smp");

    private final PhaseRouting routing =
            new PhaseRouting(new PhaseServers("limbo", "limbo-standby", "hunger-games", "smp"));

    // the phase table

    @Test
    void eachPhaseHasItsOwnBackend() {
        final PhaseServers servers = new PhaseServers("limbo", "limbo-standby", "hunger-games", "smp");

        assertEquals("hunger-games", servers.forPhase(SeasonPhase.PRE_EVENT));
        assertEquals("hunger-games", servers.forPhase(SeasonPhase.START_EVENT));
        assertEquals("smp", servers.forPhase(SeasonPhase.SMP));
        assertEquals("limbo", servers.forPhase(SeasonPhase.MAINTENANCE));
        // PRE_LAUNCH has no backend of its own, so limbo is the harmless name.
        assertEquals("limbo", servers.forPhase(SeasonPhase.PRE_LAUNCH));

        // An admin leaves the waiting room for the SMP in those two phases.
        assertEquals("smp", servers.forAdmitted(SeasonPhase.MAINTENANCE, true));
        assertEquals("smp", servers.forAdmitted(SeasonPhase.PRE_LAUNCH, true));
        assertEquals("limbo", servers.forAdmitted(SeasonPhase.MAINTENANCE, false));
        assertEquals("limbo", servers.forAdmitted(SeasonPhase.PRE_LAUNCH, false));
        for (final SeasonPhase phase :
                new SeasonPhase[] {SeasonPhase.PRE_EVENT, SeasonPhase.START_EVENT, SeasonPhase.SMP}) {
            assertEquals(servers.forPhase(phase), servers.forAdmitted(phase, true), phase.toString());
        }
    }

    @Test
    void aBlankServerNameIsRejectedWhereItIsCheapToNotice() {
        assertThrows(
                IllegalArgumentException.class, () -> new PhaseServers("", "limbo-standby", "hunger-games", "smp"));
        assertThrows(IllegalArgumentException.class, () -> new PhaseServers("limbo", "limbo-standby", null, "smp"));
    }

    @Test
    void theNamesAreConfigurableEvenThoughTheMappingIsNot() {
        // velocity.toml chooses the names; which phase uses which is fixed.
        final PhaseServers renamed = new PhaseServers("wait", "wait-standby", "hg", "survival");

        assertEquals("wait", renamed.forPhase(SeasonPhase.MAINTENANCE));
        assertEquals("hg", renamed.forPhase(SeasonPhase.START_EVENT));
        assertEquals("survival", renamed.forPhase(SeasonPhase.SMP));
    }

    // the reversal

    @Test
    void aPlainMemberInMaintenanceIsSentToLimboRatherThanRefused() {
        final RouteDecision decision = routing.decide(member(SeasonPhase.MAINTENANCE, false), ALL);

        assertEquals(Action.CONNECT, decision.action(), "the rule is to hold them in limbo, not disconnect them");
        assertEquals("limbo", decision.server());
        assertTrue(decision.connects());
    }

    @Test
    void havingBoughtAccessDoesNotExemptAnybodyFromTheWaitingRoom() {
        assertEquals(
                "limbo",
                routing.decide(member(SeasonPhase.MAINTENANCE, true), ALL).server());
    }

    @Test
    void anAdminIsTheOnePlayerMaintenanceDoesNotMove() {
        final RouteDecision decision =
                routing.decide(state(SeasonPhase.MAINTENANCE, MemberState.MEMBER, false, true), ALL);

        assertEquals(Action.STAY, decision.action(), "admins get in normally, which is not limbo");
        assertNull(decision.server());
    }

    @Test
    void anAdminWithoutAccessIsRoutedLikeOneWithIt() {
        // The admin flag is a free access period, so a switch to SMP moves an admin like any paying player.
        assertEquals(
                "smp",
                routing.decide(state(SeasonPhase.SMP, MemberState.MEMBER, false, true), ALL)
                        .server());
        assertEquals(
                "hunger-games",
                routing.decide(state(SeasonPhase.PRE_EVENT, MemberState.MEMBER, false, true), ALL)
                        .server());
    }

    // what was NOT reversed

    @Test
    void aSwitchToSmpDisconnectsAPlayerWithoutAccessAndNeverRedirectsThem() {
        final RouteDecision decision = routing.decide(member(SeasonPhase.SMP, false), ALL);

        assertEquals(
                Action.REFUSE_NO_ACCESS,
                decision.action(),
                "a player without access is disconnected, never pushed to limbo");
        assertNull(decision.server(), "a refusal carries no destination at all");
        assertTrue(decision.refuses());
    }

    @Test
    void aSwitchToSmpStillDisconnectsThemEvenWhenLimboIsPerfectlyAvailable() {
        // Not having bought access does not end by waiting.
        assertEquals(
                Action.REFUSE_NO_ACCESS,
                routing.decide(member(SeasonPhase.SMP, false), ALL).action());
        assertEquals(
                Action.REFUSE_NO_ACCESS,
                routing.decide(member(SeasonPhase.SMP, false), Set.of("limbo")).action());
    }

    @Test
    void aMemberWithAccessGoesToTheSmp() {
        assertEquals("smp", routing.decide(member(SeasonPhase.SMP, true), ALL).server());
    }

    @Test
    void theTwoEventPhasesGoToHungerGamesWithNothingBought() {
        assertEquals(
                "hunger-games",
                routing.decide(member(SeasonPhase.PRE_EVENT, false), ALL).server());
        assertEquals(
                "hunger-games",
                routing.decide(member(SeasonPhase.START_EVENT, false), ALL).server());
    }

    // admission still comes first

    @Test
    void anUnlinkedOrBannedPlayerIsNeverRoutedAnywhere() {
        for (final SeasonPhase phase : SeasonPhase.values()) {
            assertEquals(
                    Action.REFUSE_UNLINKED,
                    routing.decide(AccessState.unlinked(PLAYER, phase), ALL).action(),
                    phase.name());
            assertEquals(
                    Action.REFUSE_NOT_MEMBER,
                    routing.decide(state(phase, MemberState.BANNED, true, true), ALL)
                            .action(),
                    phase.name());
            assertEquals(
                    Action.REFUSE_NOT_MEMBER,
                    routing.decide(state(phase, MemberState.LEFT, false, false), ALL)
                            .action(),
                    phase.name());
        }
    }

    // limbo is not built

    @Test
    void maintenanceWithNoLimboServerFallsBackToTheDisconnectItUsedToBe() {
        // Limbo may not be registered at all.
        final RouteDecision decision =
                routing.decide(member(SeasonPhase.MAINTENANCE, false), Set.of("hunger-games", "smp"));

        assertEquals(Action.REFUSE_MAINTENANCE_UNAVAILABLE, decision.action());
        assertTrue(decision.refuses());
    }

    @Test
    void anAdminIsUnaffectedByAMissingLimbo() {
        // A missing waiting room must not lock out the admin maintaining it.
        assertEquals(
                Action.STAY,
                routing.decide(state(SeasonPhase.MAINTENANCE, MemberState.MEMBER, false, true), Set.of())
                        .action());
    }

    @Test
    void aMissingBackendInAnyOtherPhaseIsItsOwnScreen() {
        assertEquals(
                Action.REFUSE_NO_SERVER,
                routing.decide(member(SeasonPhase.PRE_EVENT, false), Set.of("limbo"))
                        .action());
        assertEquals(
                Action.REFUSE_NO_SERVER,
                routing.decide(member(SeasonPhase.SMP, true), Set.of("limbo")).action());
    }

    @Test
    void aProxyWithNoServersAtAllRefusesEveryPhaseRatherThanDroppingPlayersNowhere() {
        for (final SeasonPhase phase : SeasonPhase.values()) {
            final RouteDecision decision = routing.decide(member(phase, true), Set.of());
            assertTrue(decision.refuses(), phase + " must not silently do nothing");
            assertNull(decision.server());
        }
    }

    // the admitted-only form

    @Test
    void theAdmittedFormAgreesWithTheFullOneForEveryPhaseAndAdminFlag() {
        // PlayerRouter uses decideAdmitted() at login instead of re-reading the database, so the two must agree.
        for (final SeasonPhase phase : SeasonPhase.values()) {
            for (final boolean admin : new boolean[] {false, true}) {
                if (phase == SeasonPhase.PRE_LAUNCH && !admin) {
                    // Unreachable: before the opening the gate admits only admins.
                    continue;
                }
                final AccessState state = state(phase, MemberState.MEMBER, true, admin);

                assertEquals(
                        routing.decide(state, ALL),
                        routing.decideAdmitted(phase, admin, ALL),
                        phase + "/admin=" + admin);
                assertEquals(
                        routing.decide(state, Set.of()),
                        routing.decideAdmitted(phase, admin, Set.of()),
                        "with nothing registered either - " + phase + "/admin=" + admin);
            }
        }
    }

    @Test
    void aRefusalMayNotCarryAServerAndAConnectionMayNotOmitOne() {
        assertThrows(IllegalArgumentException.class, () -> new RouteDecision(Action.REFUSE_NO_ACCESS, "smp"));
        assertThrows(IllegalArgumentException.class, () -> new RouteDecision(Action.CONNECT, null));
    }

    // the limbo-first login route

    @Test
    void everyLoginLandsInTheWaitingRoomWhateverThePhase() {
        // Every login lands in the waiting room; velocity.toml's own list would skip the pack.
        for (final SeasonPhase phase : SeasonPhase.values()) {
            final RouteDecision decision = routing.decideInitial(phase, false, ALL);

            assertEquals(Action.CONNECT, decision.action(), phase.toString());
            assertEquals("limbo", decision.server(), phase.toString());
        }
    }

    @Test
    void anAdminGoesThroughTheWaitingRoomWhileTheNetworkIsClosedToo() {
        // Not STAY: velocity.toml's `try` list is the waiting room, so everybody passes through it.
        assertEquals(
                "limbo",
                routing.decideInitial(SeasonPhase.MAINTENANCE, true, ALL).server());
        assertEquals(
                "limbo",
                routing.decideInitial(SeasonPhase.PRE_LAUNCH, true, ALL).server());
    }

    @Test
    void anAdminIsReleasedOntoTheSmpWhileTheNetworkIsClosed() {
        // The SMP, fixed: it is the server built before the opening and worked on during maintenance.
        assertEquals(
                "smp", routing.decideRelease(SeasonPhase.MAINTENANCE, true, ALL).server());
        assertEquals(
                "smp", routing.decideRelease(SeasonPhase.PRE_LAUNCH, true, ALL).server());
        // Everybody else is released where the phase says, which during maintenance is the room itself.
        assertEquals(
                "limbo",
                routing.decideRelease(SeasonPhase.MAINTENANCE, false, ALL).server());
        assertEquals(
                "hunger-games",
                routing.decideRelease(SeasonPhase.PRE_EVENT, true, ALL).server());
        assertEquals("smp", routing.decideRelease(SeasonPhase.SMP, false, ALL).server());
    }

    @Test
    void aReleaseNeverSaysStay() {
        // STAY on a release keeps the last title the room drew, a black screen.
        for (final SeasonPhase phase : SeasonPhase.values()) {
            for (final boolean admin : new boolean[] {false, true}) {
                assertNotEquals(
                        Action.STAY, routing.decideRelease(phase, admin, ALL).action(), phase + "/admin=" + admin);
                assertNotEquals(
                        Action.STAY,
                        routing.decideRelease(phase, admin, Set.of()).action(),
                        phase + "/admin=" + admin + "/nothing registered");
            }
        }
    }

    @Test
    void anAdminWithoutAWaitingRoomGoesStraightToTheSmp() {
        // If a missing limbo locked admins out, nobody could register one; PlayerRouter logs the skipped pack.
        assertEquals(
                "smp",
                routing.decideInitial(SeasonPhase.MAINTENANCE, true, Set.of("smp", "hunger-games"))
                        .server());
        assertEquals(
                "smp",
                routing.decideInitial(SeasonPhase.PRE_LAUNCH, true, Set.of("smp"))
                        .server());
        assertEquals(
                "smp",
                routing.decideInitial(SeasonPhase.SMP, true, Set.of("smp")).server());
        // A non-admin never skips the pack.
        assertEquals(
                Action.REFUSE_MAINTENANCE_UNAVAILABLE,
                routing.decideInitial(SeasonPhase.MAINTENANCE, false, Set.of("smp"))
                        .action());
        assertEquals(
                Action.REFUSE_NO_SERVER,
                routing.decideInitial(SeasonPhase.SMP, false, Set.of("smp")).action());
        // And an admin with neither is refused, since there is nowhere to send them.
        assertEquals(
                Action.REFUSE_NO_SERVER,
                routing.decideInitial(SeasonPhase.PRE_LAUNCH, true, Set.of("hunger-games"))
                        .action());
    }

    @Test
    void anAdminOnABackendIsNotMovedWhenTheNetworkIsClosedUnderThem() {
        // The phase-change half keeps STAY: an admin standing on a server when the phase closes stays on it.
        assertEquals(
                Action.STAY,
                routing.decideAdmitted(SeasonPhase.MAINTENANCE, true, ALL).action());
        assertEquals(
                Action.STAY,
                routing.decideAdmitted(SeasonPhase.PRE_LAUNCH, true, ALL).action());
    }

    @Test
    void anAdminInEveryOtherPhaseGoesThroughTheWaitingRoomLikeEverybodyElse() {
        // The admin exemption is about not being moved while closed, not about skipping the pack.
        for (final SeasonPhase phase : SeasonPhase.values()) {
            if (phase == SeasonPhase.MAINTENANCE || phase == SeasonPhase.PRE_LAUNCH) {
                continue;
            }
            assertEquals(Action.CONNECT, routing.decideInitial(phase, true, ALL).action(), phase.toString());
            assertEquals("limbo", routing.decideInitial(phase, true, ALL).server(), phase.toString());
        }
    }

    @Test
    void aMissingWaitingRoomRefusesEveryLoginRatherThanLettingThemInWithoutThePack() {
        final Set<String> withoutLimbo = Set.of("hunger-games", "smp");

        // MAINTENANCE keeps the screen it has always had for this case.
        assertEquals(
                Action.REFUSE_MAINTENANCE_UNAVAILABLE,
                routing.decideInitial(SeasonPhase.MAINTENANCE, false, withoutLimbo)
                        .action());

        // Registered is not enough: the waiting room is where the pack is applied.
        for (final SeasonPhase phase :
                new SeasonPhase[] {SeasonPhase.PRE_EVENT, SeasonPhase.START_EVENT, SeasonPhase.SMP}) {
            final RouteDecision decision = routing.decideInitial(phase, false, withoutLimbo);

            assertEquals(Action.REFUSE_NO_SERVER, decision.action(), phase.toString());
            assertNull(decision.server(), phase.toString());
        }
    }

    @Test
    void theInitialRouteIgnoresThePhasesOwnBackendEntirely() {
        // Only limbo must exist to get in; a down backend is the pack station's wait, not a refusal.
        assertEquals(
                Action.CONNECT,
                routing.decideInitial(SeasonPhase.SMP, false, Set.of("limbo")).action());
        assertEquals(
                Action.CONNECT,
                routing.decideInitial(SeasonPhase.PRE_EVENT, false, Set.of("limbo"))
                        .action());
    }

    @Test
    void theInitialRouteAndTheReleaseRouteDisagreeInEveryPhaseButMaintenance() {
        // The two methods differ; agreeing everywhere would send somebody back where they just left.
        for (final SeasonPhase phase : SeasonPhase.values()) {
            final boolean same =
                    routing.decideInitial(phase, false, ALL).equals(routing.decideAdmitted(phase, false, ALL));

            // The routes agree only where the destination is the waiting room.
            final boolean destinationIsLimbo = phase == SeasonPhase.MAINTENANCE || phase == SeasonPhase.PRE_LAUNCH;
            assertEquals(destinationIsLimbo, same, phase.toString());
        }
    }

    // helpers

    private static AccessState member(final SeasonPhase phase, final boolean accessActive) {
        return state(phase, MemberState.MEMBER, accessActive, false);
    }

    private static AccessState state(
            final SeasonPhase phase, final MemberState membership, final boolean accessActive, final boolean admin) {
        return new AccessState(
                PLAYER,
                DISCORD_ID,
                membership,
                accessActive,
                accessActive ? Instant.now().plus(Duration.ofDays(1)) : null,
                false,
                admin,
                false,
                Locale.ENGLISH,
                phase,
                null);
    }
}
