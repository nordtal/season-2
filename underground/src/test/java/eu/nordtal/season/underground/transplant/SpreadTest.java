package eu.nordtal.season.underground.transplant;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class SpreadTest {

    private final Tile tile = new Tile(0, 0, 9, 9, 9);

    @Test
    void aDistanceGrowsByThreeAcrossAFaceFourAcrossAnEdgeAndFiveAcrossACorner() {
        Arrays.fill(tile.first, (byte) Spread.UNREACHED);
        tile.first[tile.index(4, 4, 4)] = 0;

        Spread.distances(tile, tile.first, 30);

        assertEquals(3, tile.first[tile.index(5, 4, 4)]);
        assertEquals(4, tile.first[tile.index(5, 5, 4)]);
        assertEquals(5, tile.first[tile.index(5, 5, 5)]);
        assertEquals(6, tile.first[tile.index(6, 4, 4)]);
    }

    @Test
    void aBlockedCellIsWalkedAround() {
        Arrays.fill(tile.first, (byte) Spread.UNREACHED);
        tile.first[tile.index(2, 4, 4)] = 0;
        tile.first[tile.index(3, 4, 4)] = (byte) Spread.BLOCKED;

        Spread.distances(tile, tile.first, 30);

        assertEquals(8, tile.first[tile.index(4, 4, 4)]);
        assertEquals(Spread.BLOCKED, tile.first[tile.index(3, 4, 4)] & 0xFF);
    }

    @Test
    void nothingIsReachedBeyondTheLimit() {
        Arrays.fill(tile.first, (byte) Spread.UNREACHED);
        tile.first[tile.index(1, 4, 4)] = 0;

        Spread.distances(tile, tile.first, 6);

        assertEquals(6, tile.first[tile.index(3, 4, 4)]);
        assertEquals(Spread.UNREACHED, tile.first[tile.index(4, 4, 4)] & 0xFF);
    }
}
