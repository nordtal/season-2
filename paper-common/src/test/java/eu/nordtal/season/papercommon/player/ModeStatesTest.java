package eu.nordtal.season.papercommon.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.database.mode.ModeStateStore.Mode;
import org.bukkit.GameMode;
import org.junit.jupiter.api.Test;

/** Which state each game mode uses, which one a player holds, and who must leave Creative's on joining. */
class ModeStatesTest {

    @Test
    void adventureSharesSurvivalsStateAndSpectatorUsesNone() {
        assertEquals(Mode.SURVIVAL, ModeStates.of(GameMode.SURVIVAL));
        assertEquals(Mode.SURVIVAL, ModeStates.of(GameMode.ADVENTURE));
        assertEquals(Mode.CREATIVE, ModeStates.of(GameMode.CREATIVE));
        assertNull(ModeStates.of(GameMode.SPECTATOR));
    }

    @Test
    void theRecordedStateWinsOverTheGameModeWhichWinsOverSurvival() {
        assertEquals(Mode.CREATIVE, ModeStates.held("CREATIVE", GameMode.SPECTATOR), "Creative, then Spectator");
        assertEquals(Mode.SURVIVAL, ModeStates.held("SURVIVAL", GameMode.CREATIVE), "a switch another handler undid");
        assertEquals(Mode.CREATIVE, ModeStates.held(null, GameMode.CREATIVE), "before the first switch");
        assertEquals(Mode.SURVIVAL, ModeStates.held(null, GameMode.SPECTATOR));
    }

    @Test
    void onlyAnAdminKeepsCreativesStateOnJoining() {
        assertTrue(ModeStates.leavesCreative(false, Mode.CREATIVE));
        assertFalse(ModeStates.leavesCreative(true, Mode.CREATIVE));
        assertFalse(ModeStates.leavesCreative(false, Mode.SURVIVAL));
    }
}
