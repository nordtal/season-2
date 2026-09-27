package eu.nordtal.s2.proxy.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.common.limbo.WaitReason;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** How long a player is held in the waiting room and under which title, asserted over every input. */
class LimboHoldTest {

    @Test
    void anUnappliedPackOutranksEveryOtherReason() {
        // Including maintenance: the download is on the player's machine, and quitting loses it.
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
        // In MAINTENANCE the phase's backend is limbo, so "not available" there still means maintenance.
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
        // The one reason that does not end on its own; only a phase switch ends it.
        assertEquals(
                Optional.of(WaitReason.MAINTENANCE),
                LimboHold.reason(true, SeasonPhase.MAINTENANCE, false, false, true, false, false));
        assertEquals(
                Optional.of(WaitReason.PACK),
                LimboHold.reason(false, SeasonPhase.MAINTENANCE, false, false, true, false, false));
    }

    @Test
    void maintenanceDoesNotHoldTheAdminItIsBeingDoneBy() {
        // Maintenance alone does not hold an admin; pack and destination still count.
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
        // PRE_LAUNCH included: SMP availability is the caller's input, the same for everybody.
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
        // PackStation passes `offer == null || applied`, so a disabled pack counts as applied.
        assertEquals(Optional.empty(), LimboHold.reason(true, SeasonPhase.SMP, false, false, true, false, false));
        assertEquals(
                Optional.of(WaitReason.BACKEND),
                LimboHold.reason(true, SeasonPhase.SMP, false, false, false, false, false));
    }

    @Test
    void unknownIsNeverProducedHereBecauseTheProxyAlwaysKnowsWhy() {
        // WaitReason.UNKNOWN is for limbo's first tick; this rule always knows its reason.
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
        // BACKEND and UPDATE are the same fact here, but the player cares about the difference.
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
        // A server stays registered while its container is down; holding here avoids releasing into it.
        assertEquals(
                Optional.of(WaitReason.UPDATE),
                LimboHold.reason(true, SeasonPhase.SMP, false, false, true, true, false));
    }

    @Test
    void maintenanceOutranksAnUpdate() {
        // Maintenance is the longer-lived truth, so it outranks the update.
        assertEquals(
                Optional.of(WaitReason.MAINTENANCE),
                LimboHold.reason(true, SeasonPhase.MAINTENANCE, false, false, false, true, false));
    }

    @Test
    void anUnappliedPackStillOutranksAnUpdate() {
        // The download is on the player's machine, the only thing they influence.
        assertEquals(
                Optional.of(WaitReason.PACK),
                LimboHold.reason(false, SeasonPhase.SMP, false, false, false, true, false));
    }

    @Test
    void anUpdateOfSomebodyElsesBackendHoldsNobody() {
        // The flag is per destination: moving hunger-games must not hold a player headed for the SMP.
        assertEquals(Optional.empty(), LimboHold.reason(true, SeasonPhase.SMP, false, false, true, false, false));
    }

    // a backend somebody is holding down

    @Test
    void aHeldBackendIsItsOwnReason() {
        // An unheld title would read "something has gone wrong" for a server held down on purpose.
        assertEquals(
                Optional.of(WaitReason.HELD),
                LimboHold.reason(true, SeasonPhase.SMP, false, false, false, false, true));
    }

    @Test
    void theHoldOutranksTheUpdate() {
        // Both are true during a hold; the update screen would promise a return that will not happen.
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
        // Registered does not mean the container is up, so availability does not guard this either.
        assertEquals(
                Optional.of(WaitReason.HELD), LimboHold.reason(true, SeasonPhase.SMP, false, false, true, false, true));
    }
}
