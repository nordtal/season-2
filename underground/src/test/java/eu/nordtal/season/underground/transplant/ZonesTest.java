package eu.nordtal.season.underground.transplant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.underground.block.Blocks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Decides a box of ground with its top at layer 100.
 * The map has a built block deep down and a cave that leaves a pit sideways for thirty blocks; the generated
 * world is rock with one wide shaft that reaches above the ground.
 */
class ZonesTest {

    private static final int GROUND = 100;

    private final Tile tile = new Tile(0, 0, 48, 48, 160);

    @BeforeEach
    void decide() {
        for (int cell = 0; cell < tile.size(); cell++) {
            final int x = tile.x(cell);
            final int z = tile.z(cell);
            final int layer = tile.layer(cell);
            final boolean pit = x >= 35 && x <= 37 && z >= 35 && z <= 37 && layer >= 83;
            final boolean cave = x >= 5 && x < 35 && z >= 35 && z <= 37 && layer >= 83 && layer <= 85;
            tile.map[cell] = (byte) (layer > GROUND || pit || cave ? Blocks.AIR : Blocks.ROCK);
            final boolean shaft = x >= 20 && x <= 27 && z >= 20 && z <= 27 && layer >= 40 && layer <= 110;
            tile.parallel[cell] = (byte) (shaft ? Blocks.AIR : Blocks.ROCK);
        }
        tile.map[tile.index(10, 60, 10)] = (byte) Blocks.BUILT;
        final Blocks blocks = Blocks.load();
        tile.map[tile.index(11, 60, 10)] = blocks.mapCell("minecraft:terracotta");
        tile.map[tile.index(40, 60, 10)] = blocks.mapCell("minecraft:terracotta");
        final Parameters measures = Parameters.standard().withEntrances(false);
        new Zones(tile, measures, new Entrances(tile, measures, new Tally())).decide();
    }

    @Test
    void theGroundStaysForTheDepthOfTheCrustAndNoFurther() {
        assertTrue(tile.has(tile.index(30, GROUND - 5, 10), Tile.KEPT));
        assertFalse(tile.has(tile.index(30, GROUND - 20, 10), Tile.KEPT));
    }

    @Test
    void aBuiltBlockDeepDownStaysWithTheRockAroundIt() {
        assertTrue(tile.has(tile.index(10, 60, 10), Tile.KEPT));
        assertTrue(tile.has(tile.index(13, 60, 10), Tile.KEPT));
        assertFalse(tile.has(tile.index(10, 60, 20), Tile.KEPT));
    }

    @Test
    void anAmbiguousBlockIsBuiltBesideABuildingAndElseGroundThatFillsWithItsOwnRock() {
        assertEquals(Blocks.BUILT, tile.map[tile.index(11, 60, 10)] & Blocks.KIND);
        assertEquals(Blocks.ROCK, tile.map[tile.index(40, 60, 10)] & Blocks.KIND);
        assertEquals("minecraft:terracotta", Blocks.filling(tile.map[tile.index(40, 60, 10)], 60));
    }

    @Test
    void aGeneratedCaveClosesBelowTheKeptZoneAndStaysOpenFurtherDown() {
        assertTrue(tile.has(tile.index(23, 91, 23), Tile.CLOSED));
        assertFalse(tile.has(tile.index(23, 60, 23), Tile.CLOSED));
    }

    @Test
    void noOpenCellOfTheGeneratedWorldTouchesAKeptCell() {
        int touching = 0;
        for (int cell = 0; cell < tile.size(); cell++) {
            final boolean open = !tile.has(cell, Tile.KEPT | Tile.CLOSED) && Blocks.open(tile.parallel[cell]);
            if (!open || tile.edge(cell)) {
                continue;
            }
            for (final int face : tile.faces()) {
                touching += tile.has(cell + face, Tile.KEPT) ? 1 : 0;
            }
        }
        assertEquals(0, touching);
    }

    @Test
    void aCaveOfTheMapThatGoesOnIsFilledBehindItsNarrowEnd() {
        assertTrue(tile.has(tile.index(30, 84, 36), Tile.OUTSIDE));
        assertFalse(tile.has(tile.index(30, 84, 36), Tile.FILLED));
        assertTrue(tile.has(tile.index(13, 84, 36), Tile.KEPT));
        assertTrue(tile.has(tile.index(13, 84, 36), Tile.FILLED));
        assertFalse(tile.has(tile.index(5, 84, 36), Tile.KEPT));
    }
}
