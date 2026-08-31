package eu.nordtal.s2.networkcontrol.gate;

import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.common.access.AccessState;
import eu.nordtal.s2.common.access.MemberState;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * docs/season-phases.md's phase table, asserted phase by phase.
 * <p>
 * This is the regression for finding 1 in docs/state-of-play.md: the gate used to refuse every
 * linked member without active access, unconditionally, so a {@code PRE_EVENT} network would have
 * turned away everyone who had not paid - in the phase whose entire purpose is being free. Every
 * combination below would have answered {@code NO_ACCESS} before 2026-08-31.
 * </p>
 * <p>
 * <b>The maintenance rule was reversed on 2026-08-31 and these assertions were rewritten, not
 * deleted.</b> {@code MAINTENANCE} used to refuse every non-admin with {@code gate.maintenance};
 * it now admits the same linked member every other phase does, and the player is held in
 * {@code limbo} by {@code eu.nordtal.s2.networkcontrol.routing.PhaseRouting} instead. The full
 * decision table after that change:
 * </p>
 * <table>
 *   <caption>Gate outcome per phase and player kind</caption>
 *   <tr><th></th><th>unlinked</th><th>left / banned</th><th>member, no access</th>
 *       <th>member, access</th><th>admin, no access</th></tr>
 *   <tr><th>{@code PRE_EVENT}</th><td>NOT_LINKED</td><td>NOT_MEMBER</td><td>ALLOW</td>
 *       <td>ALLOW</td><td>ALLOW</td></tr>
 *   <tr><th>{@code START_EVENT}</th><td>NOT_LINKED</td><td>NOT_MEMBER</td><td>ALLOW</td>
 *       <td>ALLOW</td><td>ALLOW</td></tr>
 *   <tr><th>{@code SMP}</th><td>NOT_LINKED</td><td>NOT_MEMBER</td><td>NO_ACCESS</td>
 *       <td>ALLOW</td><td>NO_ACCESS</td></tr>
 *   <tr><th>{@code MAINTENANCE}</th><td>NOT_LINKED</td><td>NOT_MEMBER</td><td>ALLOW</td>
 *       <td>ALLOW</td><td>ALLOW</td></tr>
 * </table>
 * <p>
 * In memory and exhaustive: {@link GateOutcome#of(AccessState)} is a total function of one record,
 * which is why the decision lives there and not inside a {@code LoginEvent} handler. What this
 * does <b>not</b> prove is that the resulting disconnect screens render, or that Velocity honours
 * the denial - that needs a running proxy and a real client.
 * </p>
 */
class GateOutcomeTest {

    private static final UUID PLAYER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String DISCORD_ID = "300000000000000001";

    // ---------------------------------------------------------------- unlinked, in every phase

    @Test
    void anUnlinkedAccountIsRefusedAsUnlinkedInEveryPhase() {
        for (final SeasonPhase phase : SeasonPhase.values()) {
            assertEquals(GateOutcome.NOT_LINKED, GateOutcome.of(AccessState.unlinked(PLAYER, phase)),
                    "linking is the one requirement no phase waives - " + phase);
        }
    }

    @Test
    void anUnlinkedAccountIsToldToLinkEvenDuringMaintenance() {
        // Order matters: "here is your link code" is more useful than "the network is closed", and
        // it is what the flowchart in docs/season-phases.md asks first.
        assertEquals(GateOutcome.NOT_LINKED,
                GateOutcome.of(AccessState.unlinked(PLAYER, SeasonPhase.MAINTENANCE)));
    }

    // ---------------------------------------------------------------- not a member, in every phase

    @Test
    void aBannedAccountIsRefusedInEveryPhaseEvenWithAccessAndTheAdminFlag() {
        for (final SeasonPhase phase : SeasonPhase.values()) {
            final AccessState banned = state(phase, MemberState.BANNED, true, true);
            assertEquals(GateOutcome.NOT_MEMBER, GateOutcome.of(banned),
                    "a ban outranks paid access and the admin flag - " + phase);
        }
    }

    @Test
    void anAccountThatLeftTheGuildIsRefusedTheSameWayABannedOneIs() {
        assertEquals(GateOutcome.NOT_MEMBER,
                GateOutcome.of(state(SeasonPhase.PRE_EVENT, MemberState.LEFT, false, false)));
    }

    // ---------------------------------------------------------------- PRE_EVENT and START_EVENT

    @Test
    void theTwoEventPhasesLetInAnyLinkedMemberWithNothingBought() {
        for (final SeasonPhase phase : new SeasonPhase[]{SeasonPhase.PRE_EVENT, SeasonPhase.START_EVENT}) {
            assertEquals(GateOutcome.ALLOW, GateOutcome.of(member(phase, false)),
                    "access is only required from SMP onwards; " + phase + " is free");
        }
    }

    @Test
    void havingBoughtAccessEarlyChangesNothingBeforeTheSmp() {
        // "Selling access before the SMP begins is still possible and simply banks days."
        assertEquals(GateOutcome.ALLOW, GateOutcome.of(member(SeasonPhase.PRE_EVENT, true)));
    }

    // ---------------------------------------------------------------- SMP

    @Test
    void theSmpIsTheOnlyPhaseThatAsksForAccess() {
        assertEquals(GateOutcome.NO_ACCESS, GateOutcome.of(member(SeasonPhase.SMP, false)));
        assertEquals(GateOutcome.ALLOW, GateOutcome.of(member(SeasonPhase.SMP, true)));
    }

    @Test
    void theAdminFlagIsNotAFreeAccessPeriod() {
        final AccessState adminWithoutAccess = state(SeasonPhase.SMP, MemberState.MEMBER, false, true);

        assertEquals(GateOutcome.NO_ACCESS, GateOutcome.of(adminWithoutAccess),
                "SMP asks every linked member for access, admin flag included - the flag decides "
                        + "where a player goes during maintenance, never whether they may join");
    }

    // ---------------------------------------------------------------- the whole table at once

    @Test
    void theFullDecisionTableIsWhatThisClassProduces() {
        // Every cell of the table in this class's documentation, asserted rather than described.
        // Four phases times five player kinds; the reversal is auditable by reading this method.
        assertRow(SeasonPhase.PRE_EVENT, GateOutcome.ALLOW, GateOutcome.ALLOW, GateOutcome.ALLOW);
        assertRow(SeasonPhase.START_EVENT, GateOutcome.ALLOW, GateOutcome.ALLOW, GateOutcome.ALLOW);
        assertRow(SeasonPhase.SMP, GateOutcome.NO_ACCESS, GateOutcome.ALLOW, GateOutcome.NO_ACCESS);
        assertRow(SeasonPhase.MAINTENANCE, GateOutcome.ALLOW, GateOutcome.ALLOW, GateOutcome.ALLOW);
    }

    private static void assertRow(final SeasonPhase phase, final GateOutcome memberNoAccess,
                                  final GateOutcome memberWithAccess, final GateOutcome adminNoAccess) {
        assertEquals(GateOutcome.NOT_LINKED, GateOutcome.of(AccessState.unlinked(PLAYER, phase)),
                phase + " / unlinked");
        assertEquals(GateOutcome.NOT_MEMBER,
                GateOutcome.of(state(phase, MemberState.LEFT, true, true)), phase + " / left");
        assertEquals(GateOutcome.NOT_MEMBER,
                GateOutcome.of(state(phase, MemberState.BANNED, true, true)), phase + " / banned");
        assertEquals(memberNoAccess, GateOutcome.of(state(phase, MemberState.MEMBER, false, false)),
                phase + " / member without access");
        assertEquals(memberWithAccess, GateOutcome.of(state(phase, MemberState.MEMBER, true, false)),
                phase + " / member with access");
        assertEquals(adminNoAccess, GateOutcome.of(state(phase, MemberState.MEMBER, false, true)),
                phase + " / admin without access");
    }

    // ---------------------------------------------------------------- MAINTENANCE

    @Test
    void maintenanceLetsAPlainLinkedMemberInSoTheyCanBeHeldInLimbo() {
        // Reversed 2026-08-31. docs/season-phases.md's flowchart left "disconnect OR hold in limbo"
        // open while its own phase table already said non-admins land in `limbo`; the owner settled
        // it on holding them. This assertion used to be MAINTENANCE_CLOSED, and the constant it
        // named no longer exists - maintenance is a routing decision now, not a gate decision.
        assertEquals(GateOutcome.ALLOW, GateOutcome.of(member(SeasonPhase.MAINTENANCE, false)),
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
    void theAdminFlagNoLongerChangesTheGateDecisionAnywhere() {
        // It decides where a player goes during maintenance (PhaseRouting), not whether they get in.
        // If this ever fails, admission and routing have started disagreeing about what admin means.
        for (final SeasonPhase phase : SeasonPhase.values()) {
            for (final boolean accessActive : new boolean[]{false, true}) {
                assertEquals(GateOutcome.of(state(phase, MemberState.MEMBER, accessActive, false)),
                        GateOutcome.of(state(phase, MemberState.MEMBER, accessActive, true)),
                        "the admin flag must not affect admission - " + phase + "/access=" + accessActive);
            }
        }
    }

    @Test
    void aBannedAdminIsStillRefusedDuringMaintenance() {
        assertEquals(GateOutcome.NOT_MEMBER,
                GateOutcome.of(state(SeasonPhase.MAINTENANCE, MemberState.BANNED, true, true)),
                "linkedMember() is still asked before the phase, so the reversal did not open a hole");
    }

    // ---------------------------------------------------------------- the boolean form agrees

    @Test
    void mayJoinAgreesWithTheOutcomeForEveryPhaseAndEveryAccountState() {
        // AccessState#mayJoin() is the same table collapsed to one boolean, and the fallback cache
        // and the expiry sweep decide on it. If the two ever drift, a player let in at login is
        // kicked by the sweep a minute later - so this asserts they cannot.
        for (final SeasonPhase phase : SeasonPhase.values()) {
            for (final MemberState membership : MemberState.values()) {
                for (final boolean accessActive : new boolean[]{false, true}) {
                    for (final boolean admin : new boolean[]{false, true}) {
                        final AccessState state = state(phase, membership, accessActive, admin);
                        assertEquals(GateOutcome.of(state).allowed(), state.mayJoin(),
                                "disagreement for " + phase + "/" + membership + "/access=" + accessActive
                                        + "/admin=" + admin);
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
        final AccessState state = new AccessState(PLAYER, DISCORD_ID, MemberState.MEMBER, true, null,
                false, false, Locale.ENGLISH, null);

        assertEquals(SeasonPhase.MAINTENANCE, state.phase());
        // The guess still lands on MAINTENANCE, but since 2026-08-31 that no longer means "nobody
        // gets in" - it means "everybody waits in limbo", which is the harmless place to put a
        // player the proxy cannot yet locate.
        assertEquals(GateOutcome.ALLOW, GateOutcome.of(state));
        assertTrue(state.mayJoin());
    }

    @Test
    void thereIsNoOutcomeLeftThatOnlyMaintenanceCouldProduce() {
        // The enum has four constants, not five: MAINTENANCE_CLOSED was deleted rather than left
        // unreachable, so nothing can accidentally start returning it again.
        assertEquals(4, GateOutcome.values().length,
                "ALLOW, NOT_LINKED, NOT_MEMBER, NO_ACCESS - and nothing about maintenance");
    }

    // ---------------------------------------------------------------- helpers

    private static AccessState member(final SeasonPhase phase, final boolean accessActive) {
        return state(phase, MemberState.MEMBER, accessActive, false);
    }

    private static AccessState state(final SeasonPhase phase, final MemberState membership,
                                     final boolean accessActive, final boolean admin) {
        return new AccessState(PLAYER, DISCORD_ID, membership, accessActive,
                accessActive ? Instant.now().plus(Duration.ofDays(1)) : null,
                false, admin, Locale.ENGLISH, phase);
    }
}
