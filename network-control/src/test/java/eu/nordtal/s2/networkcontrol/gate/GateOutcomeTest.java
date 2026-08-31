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
                "docs/season-phases.md's table gives MAINTENANCE the admin exemption and SMP none");
    }

    // ---------------------------------------------------------------- MAINTENANCE

    @Test
    void maintenanceIsAdminsOnlyAndPaidAccessDoesNotHelp() {
        assertEquals(GateOutcome.MAINTENANCE_CLOSED, GateOutcome.of(member(SeasonPhase.MAINTENANCE, true)),
                "having bought access is not being an admin");
    }

    @Test
    void anAdminGetsIntoMaintenanceWithNothingBought() {
        final AccessState admin = state(SeasonPhase.MAINTENANCE, MemberState.MEMBER, false, true);

        assertEquals(GateOutcome.ALLOW, GateOutcome.of(admin));
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
        assertEquals(GateOutcome.MAINTENANCE_CLOSED, GateOutcome.of(state));
        assertTrue(GateOutcome.of(state) != GateOutcome.ALLOW,
                "the state that lets nobody in is the safe one to guess");
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
