package eu.nordtal.s2.proxy.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.common.access.AccessState;
import eu.nordtal.s2.common.access.MemberState;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The gate's phase table, asserted phase by phase.
 *
 * {@code MAINTENANCE} admits the same linked member every other phase does; the player is held in
 * {@code limbo} by {@code eu.nordtal.s2.proxy.routing.PhaseRouting} instead.
 *
 * Gate outcome per phase and player kind - columns are unlinked, left/banned, member with no
 * access, member with access, admin with no access:
 *
 * {@code PRE_LAUNCH}:   NOT_LINKED, NOT_MEMBER, PRE_LAUNCH_BUY, PRE_LAUNCH_READY, ALLOW
 * {@code PRE_EVENT}:    NOT_LINKED, NOT_MEMBER, ALLOW, ALLOW, ALLOW
 * {@code START_EVENT}:  NOT_LINKED, NOT_MEMBER, ALLOW, ALLOW, ALLOW
 * {@code SMP}:          NOT_LINKED, NOT_MEMBER, NO_ACCESS, ALLOW, ALLOW
 * {@code MAINTENANCE}:  NOT_LINKED, NOT_MEMBER, ALLOW, ALLOW, ALLOW
 *
 * In memory and exhaustive: {@link GateOutcome#of(AccessState)} is a total function of one record,
 * which is why the decision lives there and not inside a {@code LoginEvent} handler. What this
 * does <b>not</b> prove is that the resulting disconnect screens render, or that Velocity honours
 * the denial - that needs a running proxy and a real client.
 */
class GateOutcomeTest {

    private static final UUID PLAYER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String DISCORD_ID = "300000000000000001";

    // unlinked, in every phase

    @Test
    void anUnlinkedAccountIsRefusedAsUnlinkedInEveryPhase() {
        for (final SeasonPhase phase : SeasonPhase.values()) {
            assertEquals(
                    GateOutcome.NOT_LINKED,
                    GateOutcome.of(AccessState.unlinked(PLAYER, phase)),
                    "linking is the one requirement no phase waives - " + phase);
        }
    }

    @Test
    void anUnlinkedAccountIsToldToLinkEvenDuringMaintenance() {
        // Order matters: "here is your link code" is more useful than "the network is closed".
        assertEquals(GateOutcome.NOT_LINKED, GateOutcome.of(AccessState.unlinked(PLAYER, SeasonPhase.MAINTENANCE)));
    }

    // not a member, in every phase

    @Test
    void aBannedAccountIsRefusedInEveryPhaseEvenWithAccessAndTheAdminFlag() {
        for (final SeasonPhase phase : SeasonPhase.values()) {
            final AccessState banned = state(phase, MemberState.BANNED, true, true);
            assertEquals(
                    GateOutcome.NOT_MEMBER,
                    GateOutcome.of(banned),
                    "a ban outranks paid access and the admin flag - " + phase);
        }
    }

    @Test
    void anAccountThatLeftTheGuildIsRefusedTheSameWayABannedOneIs() {
        assertEquals(
                GateOutcome.NOT_MEMBER, GateOutcome.of(state(SeasonPhase.PRE_EVENT, MemberState.LEFT, false, false)));
    }

    // PRE_EVENT and START_EVENT

    @Test
    void theTwoEventPhasesLetInAnyLinkedMemberWithNothingBought() {
        for (final SeasonPhase phase : new SeasonPhase[] {SeasonPhase.PRE_EVENT, SeasonPhase.START_EVENT}) {
            assertEquals(
                    GateOutcome.ALLOW,
                    GateOutcome.of(member(phase, false)),
                    "access is only required from SMP onwards; " + phase + " is free");
        }
    }

    @Test
    void havingBoughtAccessEarlyChangesNothingBeforeTheSmp() {
        // "Selling access before the SMP begins is still possible and simply banks days."
        assertEquals(GateOutcome.ALLOW, GateOutcome.of(member(SeasonPhase.PRE_EVENT, true)));
    }

    // SMP

    @Test
    void theSmpIsTheOnlyPhaseThatAsksForAccess() {
        assertEquals(GateOutcome.NO_ACCESS, GateOutcome.of(member(SeasonPhase.SMP, false)));
        assertEquals(GateOutcome.ALLOW, GateOutcome.of(member(SeasonPhase.SMP, true)));
    }

    @Test
    void theAdminFlagIsAFreeAccessPeriod() {
        // The admin flag is a free pass in SMP, else the admin who types /phase set SMP is disconnected by it.
        final AccessState adminWithoutAccess = state(SeasonPhase.SMP, MemberState.MEMBER, false, true);

        assertEquals(
                GateOutcome.ALLOW,
                GateOutcome.of(adminWithoutAccess),
                "an admin is on the network to run it, not to play a bought period");
    }

    // the whole table at once

    @Test
    void theFullDecisionTableIsWhatThisClassProduces() {
        // Every cell of the table in this class's documentation, asserted rather than described.
        assertRow(SeasonPhase.PRE_LAUNCH, GateOutcome.PRE_LAUNCH_BUY, GateOutcome.PRE_LAUNCH_READY, GateOutcome.ALLOW);
        assertRow(SeasonPhase.PRE_EVENT, GateOutcome.ALLOW, GateOutcome.ALLOW, GateOutcome.ALLOW);
        assertRow(SeasonPhase.START_EVENT, GateOutcome.ALLOW, GateOutcome.ALLOW, GateOutcome.ALLOW);
        assertRow(SeasonPhase.SMP, GateOutcome.NO_ACCESS, GateOutcome.ALLOW, GateOutcome.ALLOW);
        assertRow(SeasonPhase.MAINTENANCE, GateOutcome.ALLOW, GateOutcome.ALLOW, GateOutcome.ALLOW);
    }

    private static void assertRow(
            final SeasonPhase phase,
            final GateOutcome memberNoAccess,
            final GateOutcome memberWithAccess,
            final GateOutcome adminNoAccess) {
        assertEquals(
                GateOutcome.NOT_LINKED, GateOutcome.of(AccessState.unlinked(PLAYER, phase)), phase + " / unlinked");
        assertEquals(
                GateOutcome.NOT_MEMBER, GateOutcome.of(state(phase, MemberState.LEFT, true, true)), phase + " / left");
        assertEquals(
                GateOutcome.NOT_MEMBER,
                GateOutcome.of(state(phase, MemberState.BANNED, true, true)),
                phase + " / banned");
        assertEquals(
                memberNoAccess,
                GateOutcome.of(state(phase, MemberState.MEMBER, false, false)),
                phase + " / member without access");
        assertEquals(
                memberWithAccess,
                GateOutcome.of(state(phase, MemberState.MEMBER, true, false)),
                phase + " / member with access");
        assertEquals(
                adminNoAccess,
                GateOutcome.of(state(phase, MemberState.MEMBER, false, true)),
                phase + " / admin without access");
    }

    // MAINTENANCE

    @Test
    void maintenanceLetsAPlainLinkedMemberInSoTheyCanBeHeldInLimbo() {
        // Maintenance is a routing decision, not a gate decision: a non-admin is admitted and then held in limbo.
        assertEquals(
                GateOutcome.ALLOW,
                GateOutcome.of(member(SeasonPhase.MAINTENANCE, false)),
                "a linked member is admitted during maintenance and then routed to limbo");
    }

    @Test
    void maintenanceDoesNotAskWhetherAccessWasBought() {
        // The admission rule is the same one PRE_EVENT and START_EVENT use. Only SMP asks for more.
        assertEquals(GateOutcome.ALLOW, GateOutcome.of(member(SeasonPhase.MAINTENANCE, true)));
        assertEquals(GateOutcome.ALLOW, GateOutcome.of(member(SeasonPhase.MAINTENANCE, false)));
    }

    @Test
    void anAdminGetsIntoMaintenanceWithNothingBought() {
        final AccessState admin = state(SeasonPhase.MAINTENANCE, MemberState.MEMBER, false, true);

        assertEquals(GateOutcome.ALLOW, GateOutcome.of(admin));
    }

    @Test
    void theAdminFlagChangesTheGateDecisionInPreLaunchAndSmpAndNowhereElse() {
        // PRE_LAUNCH: admin IS the admission rule. SMP: it stands in for access. Elsewhere it only routes.
        for (final SeasonPhase phase : SeasonPhase.values()) {
            if (phase == SeasonPhase.PRE_LAUNCH || phase == SeasonPhase.SMP) {
                continue;
            }
            for (final boolean accessActive : new boolean[] {false, true}) {
                assertEquals(
                        GateOutcome.of(state(phase, MemberState.MEMBER, accessActive, false)),
                        GateOutcome.of(state(phase, MemberState.MEMBER, accessActive, true)),
                        "the admin flag must not affect admission - " + phase + "/access=" + accessActive);
            }
        }
    }

    @Test
    void aBannedAdminIsStillRefusedDuringMaintenance() {
        assertEquals(
                GateOutcome.NOT_MEMBER,
                GateOutcome.of(state(SeasonPhase.MAINTENANCE, MemberState.BANNED, true, true)),
                "linkedMember() is still asked before the phase, so the reversal did not open a hole");
    }

    // the boolean form agrees

    @Test
    void mayJoinAgreesWithTheOutcomeForEveryPhaseAndEveryAccountState() {
        // mayJoin() is the same table as one boolean; if the two drift, a player let in is kicked a minute later.
        for (final SeasonPhase phase : SeasonPhase.values()) {
            for (final MemberState membership : MemberState.values()) {
                for (final boolean accessActive : new boolean[] {false, true}) {
                    for (final boolean admin : new boolean[] {false, true}) {
                        final AccessState state = state(phase, membership, accessActive, admin);
                        assertEquals(
                                GateOutcome.of(state).allowed(),
                                state.mayJoin(),
                                "disagreement for " + phase + "/" + membership + "/access=" + accessActive + "/admin="
                                        + admin);
                    }
                }
            }

            final AccessState unlinked = AccessState.unlinked(PLAYER, phase);
            assertFalse(unlinked.mayJoin());
            assertFalse(GateOutcome.of(unlinked).allowed());
        }
    }

    @Test
    void aNullPhaseIsTreatedAsMaintenanceRatherThanCrashingTheLoginPath() {
        final AccessState state = new AccessState(
                PLAYER, DISCORD_ID, MemberState.MEMBER, true, null, false, false, false, Locale.ENGLISH, null, null);

        assertEquals(SeasonPhase.MAINTENANCE, state.phase());
        // The guess lands on MAINTENANCE: "everybody waits in limbo", harmless for an unlocatable player.
        assertEquals(GateOutcome.ALLOW, GateOutcome.of(state));
        assertTrue(state.mayJoin());
    }

    @Test
    void thereIsNoOutcomeLeftThatOnlyMaintenanceCouldProduce() {
        // No maintenance refusal constant exists, so nothing can accidentally start returning one.
        assertEquals(
                6,
                GateOutcome.values().length,
                "ALLOW, NOT_LINKED, NOT_MEMBER, NO_ACCESS, PRE_LAUNCH_BUY, PRE_LAUNCH_READY"
                        + " - and nothing about maintenance");
    }

    // PRE_LAUNCH

    @Test
    void preLaunchLetsNobodyInButAnAdmin() {
        assertEquals(
                GateOutcome.ALLOW,
                GateOutcome.of(state(SeasonPhase.PRE_LAUNCH, MemberState.MEMBER, false, true)),
                "somebody has to be able to get on the network before it opens, and that is the" + " whole of who");
        assertFalse(GateOutcome.of(member(SeasonPhase.PRE_LAUNCH, false)).allowed());
        assertFalse(GateOutcome.of(member(SeasonPhase.PRE_LAUNCH, true)).allowed());
    }

    @Test
    void preLaunchAsksWhetherAccessWasBoughtAndNotWhetherItIsRunning() {
        // A period bought before opening waits rather than burning; accessActive() would wrongly ask them to buy.
        final AccessState boughtButNotRunning = new AccessState(
                PLAYER,
                DISCORD_ID,
                MemberState.MEMBER,
                false,
                Instant.now().plus(Duration.ofDays(30)),
                false,
                false,
                false,
                Locale.ENGLISH,
                SeasonPhase.PRE_LAUNCH,
                null);

        assertEquals(GateOutcome.PRE_LAUNCH_READY, GateOutcome.of(boughtButNotRunning));
        assertEquals(
                GateOutcome.PRE_LAUNCH_BUY,
                GateOutcome.of(member(SeasonPhase.PRE_LAUNCH, false)),
                "nothing bought, so the screen is the invitation to buy");
    }

    @Test
    void preLaunchStillAsksAboutLinkingAndMembershipFirst() {
        assertEquals(
                GateOutcome.NOT_LINKED,
                GateOutcome.of(AccessState.unlinked(PLAYER, SeasonPhase.PRE_LAUNCH)),
                "an unlinked player gets their code before the network opens, not after - that is"
                        + " the first of the three countdown screens");
        assertEquals(
                GateOutcome.NOT_MEMBER,
                GateOutcome.of(state(SeasonPhase.PRE_LAUNCH, MemberState.BANNED, true, true)),
                "a banned admin is still banned, before the opening as after it");
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
