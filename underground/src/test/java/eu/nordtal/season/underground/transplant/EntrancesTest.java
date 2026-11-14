package eu.nordtal.season.underground.transplant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.underground.block.Blocks;
import org.junit.jupiter.api.Test;

/**
 * Joins a cave of the map to a generated cave.
 * The map is ground with its top at layer 100, a pit and a cave that leaves the pit sideways for forty blocks; the
 * generated world is rock with one room, to the side of the cave's end or straight below it.
 */
class EntrancesTest {

    private static final int GROUND = 100;

    private final Tile tile = new Tile(0, 0, 200, 200, 160);
    private final Tally tally = new Tally();

    private void decide(final int roomX, final int roomTop) {
        for (int cell = 0; cell < tile.size(); cell++) {
            final int x = tile.x(cell);
            final int z = tile.z(cell);
            final int layer = tile.layer(cell);
            final boolean row = z >= 100 && z <= 102;
            final boolean pit = x >= 135 && x <= 137 && row && layer >= 83;
            final boolean cave = x >= 95 && x < 135 && row && layer >= 83 && layer <= 85;
            tile.map[cell] = (byte) (layer > GROUND || pit || cave ? Blocks.AIR : Blocks.ROCK);
            final boolean room =
                    x >= roomX && x < roomX + 18 && z >= 93 && z < 110 && layer > roomTop - 10 && layer <= roomTop;
            tile.parallel[cell] = (byte) (room ? Blocks.AIR : Blocks.ROCK);
        }
        final Parameters measures = Parameters.standard();
        new Zones(tile, measures, new Entrances(tile, measures, tally)).decide();
    }

    private int carved() {
        int carved = 0;
        for (int cell = 0; cell < tile.size(); cell++) {
            carved += tile.has(cell, Tile.CARVED) ? 1 : 0;
        }
        return carved;
    }

    @Test
    void aCaveOfTheMapIsJoinedToAGeneratedCaveItCanWalkTo() {
        decide(70, 79);

        assertEquals(1, tally.mouths);
        assertEquals(1, tally.entrances);
        assertTrue(carved() > 100);
    }

    @Test
    void aGeneratedCaveStraightBelowIsNotJoined() {
        decide(100, 58);

        assertEquals(1, tally.mouths);
        assertEquals(0, tally.entrances);
        assertEquals(0, carved());
    }

    @Test
    void aTunnelBesideABuiltBlockIsRefused() {
        final Tile rock = new Tile(0, 0, 40, 40, 40);
        final Entrances entrances = new Entrances(rock, Parameters.standard(), tally);
        rock.map[rock.index(20, 20, 21)] = (byte) Blocks.BUILT;
        final Cells beside = new Cells();
        beside.push(rock.index(20, 20, 20));
        final Cells away = new Cells();
        away.push(rock.index(20, 20, 10));

        assertFalse(entrances.sound(beside));
        assertTrue(entrances.sound(away));
    }
}
