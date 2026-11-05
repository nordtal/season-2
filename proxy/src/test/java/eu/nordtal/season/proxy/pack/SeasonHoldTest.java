package eu.nordtal.season.proxy.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.common.season.SeasonMode;
import eu.nordtal.season.common.season.SeasonState;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Which hold of the season the waiting room applies: a mode switch before maintenance. */
class SeasonHoldTest {

    private static final SeasonState OPEN = new SeasonState(Instant.EPOCH, false, SeasonMode.SMP);

    @Test
    void aModeSwitchOutranksMaintenance() {
        assertEquals(SeasonHold.SWITCH, PackStation.holdOf(OPEN, true));
        assertEquals(SeasonHold.SWITCH, PackStation.holdOf(OPEN.withMaintenance(true), true));
    }

    @Test
    void maintenanceHoldsWithoutASwitch() {
        assertEquals(SeasonHold.MAINTENANCE, PackStation.holdOf(OPEN.withMaintenance(true), false));
        assertEquals(SeasonHold.NONE, PackStation.holdOf(OPEN, false));
    }
}
