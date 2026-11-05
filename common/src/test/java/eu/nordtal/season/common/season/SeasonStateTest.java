package eu.nordtal.season.common.season;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class SeasonStateTest {

    private static final Instant NOW = Instant.parse("2026-11-04T13:00:00Z");

    @Test
    void noDateIsClosed() {
        assertEquals(SeasonOpening.CLOSED, new SeasonState(null, false, SeasonMode.SMP).opening(NOW));
    }

    @Test
    void aFutureDateCountsDownAndOpensByItselfWhenItPasses() {
        final SeasonState state = new SeasonState(NOW.plusSeconds(60), false, SeasonMode.HUNGER_GAMES);

        assertEquals(SeasonOpening.COUNTDOWN, state.opening(NOW));
        assertEquals(SeasonOpening.LAUNCHED, state.opening(NOW.plusSeconds(60)), "the date itself is open");
    }

    @Test
    void anUnreadProcessIsLaunchedInMaintenanceSoNobodySeesAClosedScreen() {
        assertTrue(SeasonState.UNREAD.launched(NOW));
        assertTrue(SeasonState.UNREAD.maintenance());
    }

    @Test
    void eachModeNamesItsServerAndBack() {
        for (final SeasonMode mode : SeasonMode.values()) {
            assertEquals(mode, SeasonMode.ofServer(mode.server()).orElseThrow());
            assertEquals(mode, mode.other().other());
        }
        assertTrue(SeasonMode.ofServer("limbo").isEmpty());
        assertEquals(SeasonMode.SMP, SeasonMode.fromDatabase("SOMETHING_NEWER"));
    }
}
