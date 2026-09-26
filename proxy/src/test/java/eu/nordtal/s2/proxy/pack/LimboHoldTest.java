package eu.nordtal.s2.proxy.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.common.limbo.WaitReason;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The rule that decides how long a player stares at a black screen, asserted exhaustively.
 *
 * Every combination of the four inputs is covered below, which is cheap here (24 cases) and
 * impossible anywhere else: the rest of {@link PackStation} is Velocity events, a connection
 * request and a resource-pack offer, none of which this repository can drive. What is worth pinning
 * is not that a title appears but <b>which</b> one, because two of the three reasons look identical
 * from inside the waiting room and only the title tells the player whether to wait or to go and
 * read Discord.
 */
class LimboHoldTest {

    @Test
    void anUnappliedPackOutranksEveryOtherReason() {
        // Including maintenance, deliberately: the download is on the player's own machine, and quitting loses it.
        for (final SeasonPhase phase : SeasonPhase.values()) {
            assertEquals(
                    Optional.of(WaitReason.PACK),
                    LimboHold.reason(false, phase, false, false, true, false, false),
                    phase.toString());
            assertEquals(
                    Optional.of(WaitReason.PACK),
                    LimboHold.reason(false, phase, false, false, false, false, false),
                    phase.toString());
        }
    }

    @Test
    void maintenanceHoldsAPlayerWhosePackIsDone() {
        assertEquals(
                Optional.of(WaitReason.MAINTENANCE),
                LimboHold.reason(true, SeasonPhase.MAINTENANCE, false, false, true, false, false));
    }

    @Test
    void maintenanceOutranksAMissingBackend() {
        // In MAINTENANCE the phase's own backend is limbo, so even "not available" there still means maintenance.
        assertEquals(
                Optional.of(WaitReason.MAINTENANCE),
                LimboHold.reason(true, SeasonPhase.MAINTENANCE, false, false, false, false, false));
    }

    @Test
    void aMissingBackendIsItsOwnReasonInEveryPlayablePhase() {
        for (final SeasonPhase phase :
                new SeasonPhase[] {SeasonPhase.PRE_EVENT, SeasonPhase.START_EVENT, SeasonPhase.SMP}) {
            assertEquals(
                    Optional.of(WaitReason.BACKEND),
                    LimboHold.reason(true, phase, false, false, false, false, false),
                    phase.toString());
        }
    }

    @Test
    void nothingLeftToWaitForReleasesThePlayer() {
        for (final SeasonPhase phase :
                new SeasonPhase[] {SeasonPhase.PRE_EVENT, SeasonPhase.START_EVENT, SeasonPhase.SMP}) {
            assertEquals(
                    Optional.empty(),
                    LimboHold.reason(true, phase, false, false, true, false, false),
                    phase.toString());
        }
    }

    @Test
    void maintenanceNeverReleasesAnybodyNoMatterWhatElseIsTrue() {
        // The one reason that does not end on its own; only a phase switch ends it, never this method returning empty.
        assertEquals(
                Optional.of(WaitReason.MAINTENANCE),
                LimboHold.reason(true, SeasonPhase.MAINTENANCE, false, false, true, false, false));
        assertEquals(
                Optional.of(WaitReason.PACK),
                LimboHold.reason(false, SeasonPhase.MAINTENANCE, false, false, true, false, false));
    }

    @Test
    void maintenanceDoesNotHoldTheAdminItIsBeingDoneBy() {
        // Maintenance alone is no reason to hold the admin it is being done by; pack and destination still count.
        assertEquals(
                Optional.empty(), LimboHold.reason(true, SeasonPhase.MAINTENANCE, true, false, true, false, false));
        assertEquals(
                Optional.of(WaitReason.BACKEND),
                LimboHold.reason(true, SeasonPhase.MAINTENANCE, true, false, false, false, false));
        assertEquals(
                Optional.of(WaitReason.PACK),
                LimboHold.reason(false, SeasonPhase.MAINTENANCE, true, false, true, false, false));
    }

    @Test
    void theAdminFlagChangesNothingOutsideMaintenance() {
        // PRE_LAUNCH included: whether the SMP is available is the caller's input, read the same for everybody.
        for (final SeasonPhase phase : SeasonPhase.values()) {
            if (phase == SeasonPhase.MAINTENANCE) {
                continue;
            }
            for (final boolean settled : new boolean[] {false, true}) {
                for (final boolean available : new boolean[] {false, true}) {
                    assertEquals(
                            LimboHold.reason(settled, phase, false, false, available, false, false),
                            LimboHold.reason(settled, phase, true, false, available, false, false),
                            phase + "/settled=" + settled + "/available=" + available);
                }
            }
        }
    }

    @Test
    void aDisabledPackIsNotASpecialCaseButAWaitWithOneFewerThingInIt() {
        // PackStation passes `offer == null || applied` as packSettled: a disabled pack behaves as already applied.
        assertEquals(Optional.empty(), LimboHold.reason(true, SeasonPhase.SMP, false, false, true, false, false));
        assertEquals(
                Optional.of(WaitReason.BACKEND),
                LimboHold.reason(true, SeasonPhase.SMP, false, false, false, false, false));
    }

    @Test
    void unknownIsNeverProducedHereBecauseTheProxyAlwaysKnowsWhy() {
        // WaitReason.UNKNOWN exists for limbo's own first tick; this rule always knows why it is holding somebody.
        for (final SeasonPhase phase : SeasonPhase.values()) {
            for (final boolean settled : new boolean[] {false, true}) {
                for (final boolean admin : new boolean[] {false, true}) {
                    for (final boolean available : new boolean[] {false, true}) {
                        assertNotEquals(
                                Optional.of(WaitReason.UNKNOWN),
                                LimboHold.reason(settled, phase, admin, false, available, false, false),
                                phase + "/settled=" + settled + "/admin=" + admin + "/available=" + available);
                    }
                }
            }
        }
    }

    // an update is running

    @Test
    void anUpdateIsItsOwnReasonAndOutranksAMissingBackend() {
        // From out here BACKEND and UPDATE are the same fact, but the difference is what the player cares about.
        for (final SeasonPhase phase :
                new SeasonPhase[] {SeasonPhase.PRE_EVENT, SeasonPhase.START_EVENT, SeasonPhase.SMP}) {
            assertEquals(
                    Optional.of(WaitReason.UPDATE),
                    LimboHold.reason(true, phase, false, false, false, true, false),
                    phase.toString());
        }
    }

    @Test
    void anUpdateHoldsEvenWhileTheBackendStillLooksAvailable() {
        // The window this closes: a server stays registered while its container is down; releasing here avoids it.
        assertEquals(
                Optional.of(WaitReason.UPDATE),
                LimboHold.reason(true, SeasonPhase.SMP, false, false, true, true, false));
    }

    @Test
    void maintenanceOutranksAnUpdate() {
        // Deliberate: maintenance is the longer-lived truth, so "update" first expires into a worse sentence.
        assertEquals(
                Optional.of(WaitReason.MAINTENANCE),
                LimboHold.reason(true, SeasonPhase.MAINTENANCE, false, false, false, true, false));
    }

    @Test
    void anUnappliedPackStillOutranksAnUpdate() {
        // Same argument as elsewhere: the download is on the player's own machine, the only thing influenced.
        assertEquals(
                Optional.of(WaitReason.PACK),
                LimboHold.reason(false, SeasonPhase.SMP, false, false, false, true, false));
    }

    @Test
    void anUpdateOfSomebodyElsesBackendHoldsNobody() {
        // The flag is per destination, not per network; moving hunger-games must not hold a player headed for the SMP.
        assertEquals(Optional.empty(), LimboHold.reason(true, SeasonPhase.SMP, false, false, true, false, false));
    }

    // a backend somebody is holding down

    @Test
    void aHeldBackendIsItsOwnReason() {
        // The failure this prevents: an unheld player sees "something has gone wrong" for a server put there on purpose
        assertEquals(
                Optional.of(WaitReason.HELD),
                LimboHold.reason(true, SeasonPhase.SMP, false, false, false, false, true));
    }

    @Test
    void theHoldOutranksTheUpdate() {
        // Both are true during the hold; the update screen promises a return that will not happen, so the truth wins.
        assertEquals(
                Optional.of(WaitReason.HELD), LimboHold.reason(true, SeasonPhase.SMP, false, false, false, true, true));
    }

    @Test
    void maintenanceStillWins() {
        assertEquals(
                Optional.of(WaitReason.MAINTENANCE),
                LimboHold.reason(true, SeasonPhase.MAINTENANCE, false, false, false, false, true));
    }

    @Test
    void aHoldHoldsEvenWhileAvailable() {
        // Same reason the update question is not guarded by availability: registered does not mean the container is up.
        assertEquals(
                Optional.of(WaitReason.HELD), LimboHold.reason(true, SeasonPhase.SMP, false, false, true, false, true));
    }
}
