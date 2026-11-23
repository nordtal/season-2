package eu.nordtal.season.smp.duel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.bukkit.GameMode;
import org.junit.jupiter.api.Test;

class AreaStepTest {

    @Test
    void theFirstPlayerOnAFreeAreaWaitsAndTheSecondJoins() {
        assertEquals(AreaStep.WAIT, AreaStep.of(GameMode.SURVIVAL, false, true, false, false, false, false, false));
        assertEquals(AreaStep.JOIN, AreaStep.of(GameMode.SURVIVAL, false, true, false, false, false, false, true));
    }

    @Test
    void aThirdPlayerIsRefusedOnlyWhenSteppingOn() {
        assertEquals(AreaStep.REFUSE, AreaStep.of(GameMode.SURVIVAL, false, true, false, false, false, true, false));
        assertEquals(AreaStep.NONE, AreaStep.of(GameMode.SURVIVAL, true, true, false, false, false, true, false));
    }

    @Test
    void aWaitingPlayerLeavesBySteppingOffButAPairStays() {
        assertEquals(AreaStep.LEAVE, AreaStep.of(GameMode.SURVIVAL, true, false, true, false, false, false, true));
        assertEquals(AreaStep.NONE, AreaStep.of(GameMode.SURVIVAL, true, false, true, false, false, true, false));
        assertEquals(AreaStep.NONE, AreaStep.of(GameMode.SURVIVAL, true, false, false, false, false, true, false));
        assertEquals(AreaStep.NONE, AreaStep.of(GameMode.SURVIVAL, true, true, true, false, false, true, false));
    }

    @Test
    void aPlayerWhosePairEndedOrWhoFightsIsNeitherRefusedNorSeated() {
        assertEquals(AreaStep.NONE, AreaStep.of(GameMode.SURVIVAL, false, true, false, true, false, false, false));
        assertEquals(AreaStep.NONE, AreaStep.of(GameMode.SURVIVAL, false, true, false, false, true, true, false));
    }

    @Test
    void aSpectatorNeverWaitsJoinsBlocksOrEndsANegotiation() {
        assertEquals(AreaStep.NONE, AreaStep.of(GameMode.SPECTATOR, false, true, false, false, false, false, false));
        assertEquals(AreaStep.NONE, AreaStep.of(GameMode.SPECTATOR, false, true, false, false, false, false, true));
        assertEquals(AreaStep.NONE, AreaStep.of(GameMode.SPECTATOR, false, true, false, false, false, true, false));
        assertEquals(AreaStep.NONE, AreaStep.of(GameMode.SPECTATOR, true, false, false, false, false, false, true));
    }

    @Test
    void aNegotiatorSwitchingToSpectatorReleasesTheNegotiation() {
        assertEquals(AreaStep.LEAVE, AreaStep.of(GameMode.SPECTATOR, true, true, true, false, false, false, true));
        assertEquals(AreaStep.LEAVE, AreaStep.of(GameMode.SPECTATOR, true, false, true, false, false, false, true));
    }

    @Test
    void aSpectatorOverTheAreaDoesNotInterfereWithTwoFighters() {
        assertEquals(AreaStep.WAIT, AreaStep.of(GameMode.SURVIVAL, false, true, false, false, false, false, false));
        assertEquals(AreaStep.NONE, AreaStep.of(GameMode.SPECTATOR, false, true, false, false, false, false, true));
        assertEquals(AreaStep.NONE, AreaStep.of(GameMode.SPECTATOR, true, false, false, false, false, false, true));
        assertEquals(AreaStep.JOIN, AreaStep.of(GameMode.SURVIVAL, false, true, false, false, false, false, true));
        assertEquals(AreaStep.NONE, AreaStep.of(GameMode.SPECTATOR, false, true, false, false, false, true, false));
        assertEquals(AreaStep.NONE, AreaStep.of(GameMode.SPECTATOR, true, false, false, false, false, true, false));
        assertEquals(AreaStep.REFUSE, AreaStep.of(GameMode.SURVIVAL, false, true, false, false, false, true, false));
    }
}
