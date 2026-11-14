package eu.nordtal.season.underground.check;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.underground.anvil.ChunkBlocks;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class SeamsTest {

    private static final int FLOORS = 1;

    @Test
    void generatedWaterAtARegionsEdgeMeetsACaveOfTheMapInTheNextRegion() {
        final byte[] cells = new byte[Compared.SIDE * Compared.SIDE * ChunkBlocks.HEIGHT];
        cells[Compared.index(Compared.SIDE - 1, 40, 7)] =
                (byte) (Compared.FLUID | Compared.OPEN | Compared.AS_PARALLEL | Compared.PARALLEL_OPEN);
        final Seams seams = new Seams();
        seams.borders(cells, 3, 5);
        Arrays.fill(cells, (byte) 0);
        cells[Compared.index(0, 40, 7)] = (byte) (Compared.OPEN | Compared.MAP_OPEN | Compared.AS_MAP);
        seams.borders(cells, 4, 5);

        assertArrayEquals(new long[] {1, 0}, seams.fluid());
    }

    @Test
    void aCaveOfTheMapEndedFlatCountsLikeAGeneratedCaveClosedFlat() {
        final long[][][] faces = new long[3][3][5];
        faces[Seams.NATURAL][FLOORS] = new long[] {100, 20, 0, 0, 2};
        faces[Seams.CLOSED][FLOORS] = new long[] {100, 10, 0, 0, 1};
        faces[Seams.PLUG][FLOORS] = new long[] {100, 60, 0, 0, 2};

        assertEquals(3.0, Seams.worst(faces), 1e-9);
    }
}
