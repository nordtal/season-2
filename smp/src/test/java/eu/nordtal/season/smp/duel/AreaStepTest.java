package eu.nordtal.season.smp.duel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AreaStepTest {

    @Test
    void theFirstPlayerOnAFreeAreaWaitsAndTheSecondJoins() {
        assertEquals(AreaStep.WAIT, AreaStep.of(false, true, false, false, false, false, false));
        assertEquals(AreaStep.JOIN, AreaStep.of(false, true, false, false, false, false, true));
    }

    @Test
    void aThirdPlayerIsRefusedOnlyWhenSteppingOn() {
        assertEquals(AreaStep.REFUSE, AreaStep.of(false, true, false, false, false, true, false));
        assertEquals(AreaStep.NONE, AreaStep.of(true, true, false, false, false, true, false));
    }

    @Test
    void aNegotiatingPlayerLeavesBySteppingOff() {
        assertEquals(AreaStep.LEAVE, AreaStep.of(true, false, true, false, false, true, false));
        assertEquals(AreaStep.NONE, AreaStep.of(true, false, false, false, false, true, false));
        assertEquals(AreaStep.NONE, AreaStep.of(true, true, true, false, false, true, false));
    }

    @Test
    void aPlayerWhoClosedTheWindowOrFightsIsNeitherRefusedNorSeated() {
        assertEquals(AreaStep.NONE, AreaStep.of(false, true, false, true, false, false, false));
        assertEquals(AreaStep.NONE, AreaStep.of(false, true, false, false, true, true, false));
    }
}
