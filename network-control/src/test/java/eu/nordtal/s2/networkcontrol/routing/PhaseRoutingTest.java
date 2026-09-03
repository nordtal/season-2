package eu.nordtal.s2.networkcontrol.routing;

import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.common.access.AccessState;
import eu.nordtal.s2.common.access.MemberState;
import eu.nordtal.s2.networkcontrol.routing.RouteDecision.Action;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * docs/season-phases.md's "where they land" column and its routing section, asserted in memory.
 * <p>
 * Two things are being pinned here. The first is the 2026-08-31 maintenance reversal seen from the
 * routing side: a non-admin during {@code MAINTENANCE} is <b>connected to {@code limbo}</b> rather
 * than refused, which is what makes the gate's new {@code ALLOW} mean something. The second is the
 * exception that was <em>not</em> reversed: a switch to {@code SMP} disconnects a player without
 * access and must never become a redirect to {@code limbo}.
 * </p>
 * <p>
 * What this does not prove is that Velocity connects anybody anywhere. {@link PlayerRouter} is the
 * class that talks to the proxy and nothing in this repository can drive one; the split exists so
 * that the rules are testable even though the plumbing is not.
 * </p>
 */
class PhaseRoutingTest {

    private static final UUID PLAYER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String DISCORD_ID = "300000000000000002";

    /** What a healthy proxy has registered. */
    private static final Set<String> ALL = Set.of("limbo", "hunger-games", "smp");

    private final PhaseRouting routing = new PhaseRouting(new PhaseServers("limbo", "hunger-games", "smp"));

    // ---------------------------------------------------------------- the phase table

    @Test
    void eachPhaseHasItsOwnBackend() {
        final PhaseServers servers = new PhaseServers("limbo", "hunger-games", "smp");

        assertEquals("hunger-games", servers.forPhase(SeasonPhase.PRE_EVENT));
        assertEquals("hunger-games", servers.forPhase(SeasonPhase.START_EVENT));
        assertEquals("smp", servers.forPhase(SeasonPhase.SMP));
        assertEquals("limbo", servers.forPhase(SeasonPhase.MAINTENANCE));
        // PRE_LAUNCH has no backend of its own: nobody but an admin is on the network, and limbo is
        // the harmless place to name for the players who never get that far.
        assertEquals("limbo", servers.forPhase(SeasonPhase.PRE_LAUNCH));
    }

    @Test
    void aBlankServerNameIsRejectedWhereItIsCheapToNotice() {
        assertThrows(IllegalArgumentException.class, () -> new PhaseServers("", "hunger-games", "smp"));
        assertThrows(IllegalArgumentException.class, () -> new PhaseServers("limbo", null, "smp"));
    }

    @Test
    void theNamesAreConfigurableEvenThoughTheMappingIsNot() {
        // Nothing in docs/ says what velocity.toml calls these servers, so the names have to be
        // settable; which phase uses which is the document and is not.
        final PhaseServers renamed = new PhaseServers("wait", "hg", "survival");

        assertEquals("wait", renamed.forPhase(SeasonPhase.MAINTENANCE));
        assertEquals("hg", renamed.forPhase(SeasonPhase.START_EVENT));
        assertEquals("survival", renamed.forPhase(SeasonPhase.SMP));
    }

    // ---------------------------------------------------------------- the reversal

    @Test
    void aPlainMemberInMaintenanceIsSentToLimboRatherThanRefused() {
        final RouteDecision decision = routing.decide(member(SeasonPhase.MAINTENANCE, false), ALL);

        assertEquals(Action.CONNECT, decision.action(),
                "decided 2026-08-31: hold them in limbo, do not disconnect them");
        assertEquals("limbo", decision.server());
        assertTrue(decision.connects());
    }

    @Test
    void havingBoughtAccessDoesNotExemptAnybodyFromTheWaitingRoom() {
        assertEquals("limbo", routing.decide(member(SeasonPhase.MAINTENANCE, true), ALL).server());
    }

    @Test
    void anAdminIsTheOnePlayerMaintenanceDoesNotMove() {
        final RouteDecision decision =
                routing.decide(state(SeasonPhase.MAINTENANCE, MemberState.MEMBER, false, true), ALL);

        assertEquals(Action.STAY, decision.action(), "admins get in normally, which is not limbo");
        assertNull(decision.server());
    }

    @Test
    void theAdminExemptionIsMaintenanceOnly() {
        // An admin in SMP without access is refused exactly like anybody else - the flag is not a
        // free access period, and it is not a routing exemption outside maintenance either.
        assertEquals(Action.REFUSE_NO_ACCESS,
                routing.decide(state(SeasonPhase.SMP, MemberState.MEMBER, false, true), ALL).action());
        assertEquals("hunger-games",
                routing.decide(state(SeasonPhase.PRE_EVENT, MemberState.MEMBER, false, true), ALL).server());
    }

    // ---------------------------------------------------------------- what was NOT reversed

    @Test
    void aSwitchToSmpDisconnectsAPlayerWithoutAccessAndNeverRedirectsThem() {
        final RouteDecision decision = routing.decide(member(SeasonPhase.SMP, false), ALL);

        assertEquals(Action.REFUSE_NO_ACCESS, decision.action(),
                "docs/season-phases.md#routing, settled 2026-08-31: it does not push them to limbo");
        assertNull(decision.server(), "a refusal carries no destination at all");
        assertTrue(decision.refuses());
    }

    @Test
    void aSwitchToSmpStillDisconnectsThemEvenWhenLimboIsPerfectlyAvailable() {
        // The tempting bug: "we have a waiting room now, so put them in it". limbo is for waiting
        // on something that ends, and not having bought access does not end by waiting.
        assertEquals(Action.REFUSE_NO_ACCESS, routing.decide(member(SeasonPhase.SMP, false), ALL).action());
        assertEquals(Action.REFUSE_NO_ACCESS,
                routing.decide(member(SeasonPhase.SMP, false), Set.of("limbo")).action());
    }

    @Test
    void aMemberWithAccessGoesToTheSmp() {
        assertEquals("smp", routing.decide(member(SeasonPhase.SMP, true), ALL).server());
    }

    @Test
    void theTwoEventPhasesGoToHungerGamesWithNothingBought() {
        assertEquals("hunger-games", routing.decide(member(SeasonPhase.PRE_EVENT, false), ALL).server());
        assertEquals("hunger-games", routing.decide(member(SeasonPhase.START_EVENT, false), ALL).server());
    }

    // ---------------------------------------------------------------- admission still comes first

    @Test
    void anUnlinkedOrBannedPlayerIsNeverRoutedAnywhere() {
        for (final SeasonPhase phase : SeasonPhase.values()) {
            assertEquals(Action.REFUSE_UNLINKED,
                    routing.decide(AccessState.unlinked(PLAYER, phase), ALL).action(), phase.name());
            assertEquals(Action.REFUSE_NOT_MEMBER,
                    routing.decide(state(phase, MemberState.BANNED, true, true), ALL).action(), phase.name());
            assertEquals(Action.REFUSE_NOT_MEMBER,
                    routing.decide(state(phase, MemberState.LEFT, false, false), ALL).action(), phase.name());
        }
    }

    // ---------------------------------------------------------------- limbo is not built

    @Test
    void maintenanceWithNoLimboServerFallsBackToTheDisconnectItUsedToBe() {
        // The honest half of the reversal. `limbo` is a scaffold module, so "route them to limbo"
        // can only mean "connect them to the configured backend" - and that backend may not be
        // registered on this proxy at all. Rather than an undefined state or a raw Velocity error,
        // the player gets the maintenance screen: the "disconnect" half of the either/or
        // docs/season-phases.md used to leave open, kept for exactly the case where holding them
        // is impossible.
        final RouteDecision decision =
                routing.decide(member(SeasonPhase.MAINTENANCE, false), Set.of("hunger-games", "smp"));

        assertEquals(Action.REFUSE_MAINTENANCE_UNAVAILABLE, decision.action());
        assertTrue(decision.refuses());
    }

    @Test
    void anAdminIsUnaffectedByAMissingLimbo() {
        // They were never going there, so a missing waiting room cannot lock them out of the
        // network they are maintaining. This is the branch that must not regress: if maintenance
        // ever locked admins out because limbo is unbuilt, nobody could fix anything.
        assertEquals(Action.STAY, routing.decide(
                state(SeasonPhase.MAINTENANCE, MemberState.MEMBER, false, true), Set.of()).action());
    }

    @Test
    void aMissingBackendInAnyOtherPhaseIsItsOwnScreen() {
        assertEquals(Action.REFUSE_NO_SERVER,
                routing.decide(member(SeasonPhase.PRE_EVENT, false), Set.of("limbo")).action());
        assertEquals(Action.REFUSE_NO_SERVER,
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

    // ---------------------------------------------------------------- the admitted-only form

    @Test
    void theAdmittedFormAgreesWithTheFullOneForEveryPhaseAndAdminFlag() {
        // PlayerRouter uses decideAdmitted() at PlayerChooseInitialServerEvent, where re-reading the
        // database would be a second round trip on a login path pinned to one. If the two ever
        // drift, a player is routed one way at login and another way on the next phase change.
        for (final SeasonPhase phase : SeasonPhase.values()) {
            for (final boolean admin : new boolean[]{false, true}) {
                if (phase == SeasonPhase.PRE_LAUNCH && !admin) {
                    // Not a drift, an unreachable combination. decideAdmitted() is only ever asked
                    // about a player the gate has already let in, and before the opening that is an
                    // admin and nobody else (GateOutcome). decide() is asked about everybody,
                    // because it is also the phase-change re-route - which is exactly where a
                    // non-admin caught by a switch back to PRE_LAUNCH gets their countdown screen.
                    continue;
                }
                final AccessState state = state(phase, MemberState.MEMBER, true, admin);

                assertEquals(routing.decide(state, ALL), routing.decideAdmitted(phase, admin, ALL),
                        phase + "/admin=" + admin);
                assertEquals(routing.decide(state, Set.of()), routing.decideAdmitted(phase, admin, Set.of()),
                        "with nothing registered either - " + phase + "/admin=" + admin);
            }
        }
    }

    @Test
    void aRefusalMayNotCarryAServerAndAConnectionMayNotOmitOne() {
        assertThrows(IllegalArgumentException.class,
                () -> new RouteDecision(Action.REFUSE_NO_ACCESS, "smp"));
        assertThrows(IllegalArgumentException.class, () -> new RouteDecision(Action.CONNECT, null));
    }

    // ---------------------------------------------------------------- the limbo-first login route

    @Test
    void everyLoginLandsInTheWaitingRoomWhateverThePhase() {
        // docs/architecture.md#the-login-path-end-to-end, built 2026-09-01. Before the pack station
        // existed this was true of MAINTENANCE only and every other phase kept velocity.toml's own
        // try list, which is what let a player onto a backend without the resource pack.
        for (final SeasonPhase phase : SeasonPhase.values()) {
            final RouteDecision decision = routing.decideInitial(phase, false, ALL);

            assertEquals(Action.CONNECT, decision.action(), phase.toString());
            assertEquals("limbo", decision.server(), phase.toString());
        }
    }

    @Test
    void anAdminIsNotSentToTheWaitingRoomWhileTheNetworkIsClosed() {
        // The two phases where there is nothing to wait for: maintenance (the network is running,
        // the admin is here to look at what is being worked on) and PRE_LAUNCH (the admin is the
        // only player there is). In both, holding them in limbo would hold them nowhere.
        assertEquals(Action.STAY, routing.decideInitial(SeasonPhase.MAINTENANCE, true, ALL).action());
        assertEquals(Action.STAY, routing.decideInitial(SeasonPhase.PRE_LAUNCH, true, ALL).action());
    }

    @Test
    void anAdminInEveryOtherPhaseGoesThroughTheWaitingRoomLikeEverybodyElse() {
        // The admin exemption is about not being moved while the network is closed, not about
        // skipping the pack: an admin joining a running network needs the glyphs as much as anybody.
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
        assertEquals(Action.REFUSE_MAINTENANCE_UNAVAILABLE,
                routing.decideInitial(SeasonPhase.MAINTENANCE, false, withoutLimbo).action());

        // And the three phases that used to fall through to velocity.toml now refuse instead. The
        // phase's own backend being registered is deliberately not enough - the waiting room is
        // where the pack is applied, so skipping it is the failure this refuses.
        for (final SeasonPhase phase : new SeasonPhase[]{SeasonPhase.PRE_EVENT, SeasonPhase.START_EVENT,
                SeasonPhase.SMP}) {
            final RouteDecision decision = routing.decideInitial(phase, false, withoutLimbo);

            assertEquals(Action.REFUSE_NO_SERVER, decision.action(), phase.toString());
            assertNull(decision.server(), phase.toString());
        }
    }

    @Test
    void theInitialRouteIgnoresThePhasesOwnBackendEntirely() {
        // Only limbo has to exist to get a player in. Whether hunger-games or smp is up is asked
        // again at release time, and until then it is the pack station's BACKEND wait, not a login
        // refusal - a player whose backend is down should sit in the waiting room, not be kicked.
        assertEquals(Action.CONNECT, routing.decideInitial(SeasonPhase.SMP, false, Set.of("limbo")).action());
        assertEquals(Action.CONNECT,
                routing.decideInitial(SeasonPhase.PRE_EVENT, false, Set.of("limbo")).action());
    }

    @Test
    void theInitialRouteAndTheReleaseRouteDisagreeInEveryPhaseButMaintenance() {
        // The two methods exist precisely because they differ; if they ever agreed everywhere, one
        // of them would be sending players back into the waiting room they had just left.
        for (final SeasonPhase phase : SeasonPhase.values()) {
            final boolean same = routing.decideInitial(phase, false, ALL)
                    .equals(routing.decideAdmitted(phase, false, ALL));

            // The two phases whose destination IS the waiting room are the two where the routes
            // agree: there is nowhere to release a player to. PRE_LAUNCH joined MAINTENANCE there
            // on 2026-09-03 - and for a non-admin it is theory anyway, because the gate refuses
            // them before either route is taken.
            final boolean destinationIsLimbo =
                    phase == SeasonPhase.MAINTENANCE || phase == SeasonPhase.PRE_LAUNCH;
            assertEquals(destinationIsLimbo, same, phase.toString());
        }
    }

    // ---------------------------------------------------------------- helpers

    private static AccessState member(final SeasonPhase phase, final boolean accessActive) {
        return state(phase, MemberState.MEMBER, accessActive, false);
    }

    private static AccessState state(final SeasonPhase phase, final MemberState membership,
                                     final boolean accessActive, final boolean admin) {
        return new AccessState(PLAYER, DISCORD_ID, membership, accessActive,
                accessActive ? Instant.now().plus(Duration.ofDays(1)) : null,
                false, admin, Locale.ENGLISH, phase, null);
    }
}
