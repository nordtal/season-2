package eu.nordtal.season.smp.rail;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StationRailListenerTest {

    private static StationRow placedBy(final UUID placer) {
        return new StationRow(new UUID(0, 1), "nordtal", 0, 64, 0, "NORTH_SOUTH", placer, null, null);
    }

    @Test
    void aRefusedBreakOfAPlayersStationSaysWhoMayBreakIt() {
        assertEquals(
                StationRailListener.BREAK_REFUSED, StationRailListener.refusal(Optional.of(placedBy(new UUID(0, 2)))));
        assertEquals(StationRailListener.BREAK_REFUSED, StationRailListener.refusal(Optional.empty()));
    }

    @Test
    void aRefusedBreakOfTheSpawnStationSaysNobodyBreaksIt() {
        assertEquals(StationRailListener.SPAWN_UNBREAKABLE, StationRailListener.refusal(Optional.of(placedBy(null))));
    }
}
