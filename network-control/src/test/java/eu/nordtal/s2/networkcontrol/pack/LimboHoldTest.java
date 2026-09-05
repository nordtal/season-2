package eu.nordtal.s2.networkcontrol.pack;

import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.common.limbo.WaitReason;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The rule that decides how long a player stares at a black screen, asserted exhaustively.
 * <p>
 * Every combination of the four inputs is covered below, which is cheap here (24 cases) and
 * impossible anywhere else: the rest of {@link PackStation} is Velocity events, a connection
 * request and a resource-pack offer, none of which this repository can drive. What is worth pinning
 * is not that a title appears but <b>which</b> one, because two of the three reasons look identical
 * from inside the waiting room and only the title tells the player whether to wait or to go and
 * read Discord.
 * </p>
 */
class LimboHoldTest {

    @Test
    void anUnappliedPackOutranksEveryOtherReason() {
        // Including maintenance, and deliberately. The download is happening on the player's own
        // machine right now; telling them the network is under maintenance describes the wrong half
        // of their situation and invites them to quit while a download is in flight.
        for (final SeasonPhase phase : SeasonPhase.values()) {
            assertEquals(Optional.of(WaitReason.PACK), LimboHold.reason(false, phase, false, true), phase.toString());
            assertEquals(Optional.of(WaitReason.PACK), LimboHold.reason(false, phase, false, false), phase.toString());
        }
    }

    @Test
    void maintenanceHoldsAPlayerWhosePackIsDone() {
        assertEquals(Optional.of(WaitReason.MAINTENANCE),
                LimboHold.reason(true, SeasonPhase.MAINTENANCE, false, true));
    }

    @Test
    void maintenanceOutranksAMissingBackend() {
        // In MAINTENANCE the phase's own backend IS limbo, so "not available" here means the player
        // is standing in a waiting room the proxy does not think exists - which cannot happen, but
        // if it ever did, the honest answer is still the reason they are not going anywhere.
        assertEquals(Optional.of(WaitReason.MAINTENANCE),
                LimboHold.reason(true, SeasonPhase.MAINTENANCE, false, false));
    }

    @Test
    void aMissingBackendIsItsOwnReasonInEveryPlayablePhase() {
        for (final SeasonPhase phase : new SeasonPhase[]{SeasonPhase.PRE_EVENT, SeasonPhase.START_EVENT,
                SeasonPhase.SMP}) {
            assertEquals(Optional.of(WaitReason.BACKEND), LimboHold.reason(true, phase, false, false),
                    phase.toString());
        }
    }

    @Test
    void nothingLeftToWaitForReleasesThePlayer() {
        for (final SeasonPhase phase : new SeasonPhase[]{SeasonPhase.PRE_EVENT, SeasonPhase.START_EVENT,
                SeasonPhase.SMP}) {
            assertEquals(Optional.empty(), LimboHold.reason(true, phase, false, true), phase.toString());
        }
    }

    @Test
    void maintenanceNeverReleasesAnybodyNoMatterWhatElseIsTrue() {
        // The one reason that does not end on its own. It ends when an admin switches the phase, at
        // which point PlayerRouter re-routes everybody - so this method returning empty for
        // MAINTENANCE would be a player quietly let onto the servers being worked on.
        assertEquals(Optional.of(WaitReason.MAINTENANCE),
                LimboHold.reason(true, SeasonPhase.MAINTENANCE, false, true));
        assertEquals(Optional.of(WaitReason.PACK),
                LimboHold.reason(false, SeasonPhase.MAINTENANCE, false, true));
    }

    @Test
    void maintenanceDoesNotHoldTheAdminItIsBeingDoneBy() {
        // The admin is the one player maintenance is FOR. Until 2026-09-05 they were kept out of the
        // room by routing instead (STAY at login), which velocity.toml quietly turned into "put them
        // in limbo" - so they were held here under a title that never changed. Now they pass
        // through like everybody else and this is the rule that lets them out: the pack still comes
        // first, the server they are released onto (the SMP, PhaseServers#forAdmitted) still has to
        // be there, and maintenance itself is no reason to wait.
        assertEquals(Optional.empty(), LimboHold.reason(true, SeasonPhase.MAINTENANCE, true, true));
        assertEquals(Optional.of(WaitReason.BACKEND),
                LimboHold.reason(true, SeasonPhase.MAINTENANCE, true, false));
        assertEquals(Optional.of(WaitReason.PACK),
                LimboHold.reason(false, SeasonPhase.MAINTENANCE, true, true));
    }

    @Test
    void theAdminFlagChangesNothingOutsideMaintenance() {
        // PRE_LAUNCH included: forAdmitted names the SMP for an admin there, but whether it is
        // available is the caller's input, and this rule reads it the same way for everybody.
        for (final SeasonPhase phase : SeasonPhase.values()) {
            if (phase == SeasonPhase.MAINTENANCE) {
                continue;
            }
            for (final boolean settled : new boolean[]{false, true}) {
                for (final boolean available : new boolean[]{false, true}) {
                    assertEquals(LimboHold.reason(settled, phase, false, available),
                            LimboHold.reason(settled, phase, true, available),
                            phase + "/settled=" + settled + "/available=" + available);
                }
            }
        }
    }

    @Test
    void aDisabledPackIsNotASpecialCaseButAWaitWithOneFewerThingInIt() {
        // PackStation passes `offer == null || applied` as packSettled, so a proxy with
        // pack.yml#enabled false behaves exactly like one whose players have all already applied it.
        assertEquals(Optional.empty(), LimboHold.reason(true, SeasonPhase.SMP, false, true));
        assertEquals(Optional.of(WaitReason.BACKEND), LimboHold.reason(true, SeasonPhase.SMP, false, false));
    }

    @Test
    void unknownIsNeverProducedHereBecauseTheProxyAlwaysKnowsWhy() {
        // WaitReason.UNKNOWN exists for limbo's own first tick, before any WAIT has arrived. If this
        // method ever returned it, it would mean the proxy had told the waiting room it did not know
        // why it was holding somebody, which is not a state this rule has.
        for (final SeasonPhase phase : SeasonPhase.values()) {
            for (final boolean settled : new boolean[]{false, true}) {
                for (final boolean admin : new boolean[]{false, true}) {
                    for (final boolean available : new boolean[]{false, true}) {
                        assertNotEquals(Optional.of(WaitReason.UNKNOWN),
                                LimboHold.reason(settled, phase, admin, available),
                                phase + "/settled=" + settled + "/admin=" + admin + "/available="
                                        + available);
                    }
                }
            }
        }
    }
}
