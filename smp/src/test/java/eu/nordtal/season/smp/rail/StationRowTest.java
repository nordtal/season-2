package eu.nordtal.season.smp.rail;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class StationRowTest {

    private static final UUID PLACER = new UUID(1, 1);
    private static final UUID OTHER = new UUID(1, 2);

    private static StationRow placedBy(final UUID placer) {
        return new StationRow(new UUID(0, 1), "nordtal", 0, 64, 0, "EAST_WEST", placer, "Mine", null);
    }

    @Test
    void thePlacerAndAdminsChangeAStationAndNobodyElse() {
        assertTrue(placedBy(PLACER).mayChange(PLACER, false));
        assertTrue(placedBy(PLACER).mayChange(OTHER, true));
        assertFalse(placedBy(PLACER).mayChange(OTHER, false));
    }

    @Test
    void nobodyBreaksRenamesOrHidesTheSpawnStationNotEvenAnAdmin() {
        final StationRow spawn = new StationRow(new UUID(0, 9), "nordtal", 0, 64, 0, "EAST_WEST", null, null, null);
        assertTrue(spawn.spawn());
        assertFalse(spawn.mayChange(PLACER, false));
        assertFalse(spawn.mayChange(PLACER, true));
        assertFalse(placedBy(PLACER).spawn());
    }
}
