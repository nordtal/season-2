package eu.nordtal.season.proxy.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import eu.nordtal.season.limboprotocol.WaitReason;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** How long a player is held in the waiting room and under which title, asserted over every input. */
class LimboHoldTest {

    @Test
    void anUnappliedPackOutranksEveryOtherReason() {
        // Including maintenance: the download is on the player's machine, and quitting loses it.
        for (final SeasonHold hold : SeasonHold.values()) {
            assertEquals(
                    Optional.of(WaitReason.PACK),
                    LimboHold.reason(false, hold, false, false, true, false, false),
                    hold.toString());
            assertEquals(
                    Optional.of(WaitReason.PACK),
                    LimboHold.reason(false, hold, false, false, false, false, false),
                    hold.toString());
        }
    }

    @Test
    void maintenanceHoldsAPlayerWhosePackIsDone() {
        assertEquals(
                Optional.of(WaitReason.MAINTENANCE),
                LimboHold.reason(true, SeasonHold.MAINTENANCE, false, false, true, false, false));
    }

    @Test
    void maintenanceOutranksAMissingBackend() {
        // A missing backend during maintenance still reads as maintenance, the reason that lasts longer.
        assertEquals(
                Optional.of(WaitReason.MAINTENANCE),
                LimboHold.reason(true, SeasonHold.MAINTENANCE, false, false, false, false, false));
    }

    @Test
    void aMissingBackendIsItsOwnReasonWithoutAHold() {
        assertEquals(
                Optional.of(WaitReason.BACKEND),
                LimboHold.reason(true, SeasonHold.NONE, false, false, false, false, false));
    }

    @Test
    void nothingLeftToWaitForReleasesThePlayer() {
        assertEquals(Optional.empty(), LimboHold.reason(true, SeasonHold.NONE, false, false, true, false, false));
    }

    @Test
    void maintenanceNeverReleasesAnybodyNoMatterWhatElseIsTrue() {
        // The one reason that does not end on its own; only switching maintenance off ends it.
        assertEquals(
                Optional.of(WaitReason.MAINTENANCE),
                LimboHold.reason(true, SeasonHold.MAINTENANCE, false, false, true, false, false));
        assertEquals(
                Optional.of(WaitReason.PACK),
                LimboHold.reason(false, SeasonHold.MAINTENANCE, false, false, true, false, false));
    }

    @Test
    void maintenanceDoesNotHoldTheAdminItIsBeingDoneBy() {
        // Maintenance alone does not hold an admin; pack and destination still count.
        assertEquals(Optional.empty(), LimboHold.reason(true, SeasonHold.MAINTENANCE, true, false, true, false, false));
        assertEquals(
                Optional.of(WaitReason.BACKEND),
                LimboHold.reason(true, SeasonHold.MAINTENANCE, true, false, false, false, false));
        assertEquals(
                Optional.of(WaitReason.PACK),
                LimboHold.reason(false, SeasonHold.MAINTENANCE, true, false, true, false, false));
    }

    @Test
    void theAdminFlagChangesNothingOutsideMaintenance() {
        // A mode switch included: it holds admins as it holds everybody else.
        for (final SeasonHold hold : SeasonHold.values()) {
            if (hold == SeasonHold.MAINTENANCE) {
                continue;
            }
            for (final boolean settled : new boolean[] {false, true}) {
                for (final boolean available : new boolean[] {false, true}) {
                    assertEquals(
                            LimboHold.reason(settled, hold, false, false, available, false, false),
                            LimboHold.reason(settled, hold, true, false, available, false, false),
                            hold + "/settled=" + settled + "/available=" + available);
                }
            }
        }
    }

    @Test
    void aDisabledPackIsNotASpecialCaseButAWaitWithOneFewerThingInIt() {
        // PackStation passes `offer == null || applied`, so a disabled pack counts as applied.
        assertEquals(Optional.empty(), LimboHold.reason(true, SeasonHold.NONE, false, false, true, false, false));
        assertEquals(
                Optional.of(WaitReason.BACKEND),
                LimboHold.reason(true, SeasonHold.NONE, false, false, false, false, false));
    }

    @Test
    void unknownIsNeverProducedHereBecauseTheProxyAlwaysKnowsWhy() {
        // WaitReason.UNKNOWN is for limbo's first tick; this rule always knows its reason.
        for (final SeasonHold hold : SeasonHold.values()) {
            for (final boolean settled : new boolean[] {false, true}) {
                for (final boolean admin : new boolean[] {false, true}) {
                    for (final boolean available : new boolean[] {false, true}) {
                        assertNotEquals(
                                Optional.of(WaitReason.UNKNOWN),
                                LimboHold.reason(settled, hold, admin, false, available, false, false),
                                hold + "/settled=" + settled + "/admin=" + admin + "/available=" + available);
                    }
                }
            }
        }
    }

    // an update is running

    @Test
    void anUpdateIsItsOwnReasonAndOutranksAMissingBackend() {
        // BACKEND and UPDATE are the same fact here, but the player cares about the difference.
        assertEquals(
                Optional.of(WaitReason.UPDATE),
                LimboHold.reason(true, SeasonHold.NONE, false, false, false, true, false));
    }

    @Test
    void anUpdateHoldsEvenWhileTheBackendStillLooksAvailable() {
        // A server stays registered while its container is down; holding here avoids releasing into it.
        assertEquals(
                Optional.of(WaitReason.UPDATE),
                LimboHold.reason(true, SeasonHold.NONE, false, false, true, true, false));
    }

    @Test
    void maintenanceOutranksAnUpdate() {
        // Maintenance is the longer-lived truth, so it outranks the update.
        assertEquals(
                Optional.of(WaitReason.MAINTENANCE),
                LimboHold.reason(true, SeasonHold.MAINTENANCE, false, false, false, true, false));
    }

    @Test
    void anUnappliedPackStillOutranksAnUpdate() {
        // The download is on the player's machine, the only thing they influence.
        assertEquals(
                Optional.of(WaitReason.PACK),
                LimboHold.reason(false, SeasonHold.NONE, false, false, false, true, false));
    }

    @Test
    void anUpdateOfSomebodyElsesBackendHoldsNobody() {
        // The flag is per destination: moving hunger-games must not hold a player headed for the SMP.
        assertEquals(Optional.empty(), LimboHold.reason(true, SeasonHold.NONE, false, false, true, false, false));
    }

    // a backend somebody is holding down

    @Test
    void aHeldBackendIsItsOwnReason() {
        // An unheld title would read "something has gone wrong" for a server held down on purpose.
        assertEquals(
                Optional.of(WaitReason.HELD),
                LimboHold.reason(true, SeasonHold.NONE, false, false, false, false, true));
    }

    @Test
    void theHoldOutranksTheUpdate() {
        // Both are true during a hold; the update screen would promise a return that will not happen.
        assertEquals(
                Optional.of(WaitReason.HELD), LimboHold.reason(true, SeasonHold.NONE, false, false, false, true, true));
    }

    @Test
    void maintenanceStillWins() {
        assertEquals(
                Optional.of(WaitReason.MAINTENANCE),
                LimboHold.reason(true, SeasonHold.MAINTENANCE, false, false, false, false, true));
    }

    @Test
    void aHoldHoldsEvenWhileAvailable() {
        // Registered does not mean the container is up, so availability does not guard this either.
        assertEquals(
                Optional.of(WaitReason.HELD), LimboHold.reason(true, SeasonHold.NONE, false, false, true, false, true));
    }

    // a mode switch

    @Test
    void aModeSwitchHoldsEverybodyAdminsIncluded() {
        for (final boolean admin : new boolean[] {false, true}) {
            assertEquals(
                    Optional.of(WaitReason.SWITCH),
                    LimboHold.reason(true, SeasonHold.SWITCH, admin, false, true, false, false),
                    "admin=" + admin);
        }
    }

    @Test
    void aModeSwitchOutranksMaintenanceAndAHeldBackend() {
        // The switch moves every player; the screen says so until its target passed its check.
        assertEquals(
                Optional.of(WaitReason.SWITCH),
                LimboHold.reason(true, SeasonHold.SWITCH, false, false, true, false, true));
    }

    @Test
    void anUnappliedPackStillOutranksAModeSwitch() {
        assertEquals(
                Optional.of(WaitReason.PACK),
                LimboHold.reason(false, SeasonHold.SWITCH, true, false, true, false, false));
    }
}
