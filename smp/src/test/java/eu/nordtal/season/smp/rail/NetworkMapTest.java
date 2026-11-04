package eu.nordtal.season.smp.rail;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NetworkMapTest {

    private static StationRow station(final int id) {
        return new StationRow(new UUID(0, id), "nordtal", id, 64, 0, "EAST_WEST", new UUID(1, id), "S" + id, null);
    }

    private static final List<StationRow> THREE = List.of(station(1), station(2), station(3));

    @Test
    void aNetworkWithTwoStationsNotHiddenIsDrawn() {
        assertTrue(NetworkMap.drawn(THREE, Set.of()));
        assertTrue(NetworkMap.drawn(THREE, Set.of(new UUID(0, 1))));
    }

    @Test
    void aNetworkWithFewerThanTwoStationsNotHiddenIsLeftOff() {
        assertFalse(NetworkMap.drawn(THREE, Set.of(new UUID(0, 1), new UUID(0, 2))));
        assertFalse(NetworkMap.drawn(List.of(station(1)), Set.of()));
        assertFalse(NetworkMap.drawn(List.of(), Set.of()));
    }
}
