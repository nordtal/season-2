package eu.nordtal.season.underground.structures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ReachTest {

    private static final long SEED = 27_594_263L;

    @Test
    void aBoxSplitsIntoOnePiecePerChunkColumn() {
        final List<Box> pieces = Reach.columns(new Box(10, 60, 10, 20, 70, 40));

        assertEquals(6, pieces.size());
        assertEquals(new Box(10, 60, 10, 15, 70, 15), pieces.getFirst());
        assertEquals(new Box(16, 60, 32, 20, 70, 40), pieces.getLast());
    }

    @Test
    void everyPieceGoesUnderACandidateWithinEightChunksOfIt() {
        final Finds finds = new Finds();
        for (int chunkX = -40; chunkX < 40; chunkX++) {
            for (int chunkZ = -40; chunkZ < 40; chunkZ++) {
                finds.fullChunks.add(Finds.key(chunkX, chunkZ));
            }
        }
        final Spread spread = new Spread(8, 0, 945_001);
        final List<Box> pieces = Reach.columns(new Box(-200, 60, -100, 250, 70, 120));

        final List<Reach.Choice> choices = new Reach(spread, SEED, finds).cover(pieces);

        assertEquals(
                pieces.size(),
                choices.stream().mapToInt(choice -> choice.pieces().size()).sum());
        for (final Reach.Choice choice : choices) {
            final int[] candidate = spread.candidateNear(SEED, choice.chunkX(), choice.chunkZ());
            assertEquals(List.of(candidate[0], candidate[1]), List.of(choice.chunkX(), choice.chunkZ()));
            assertTrue(
                    choice.pieces().stream().allMatch(piece -> Reach.reaches(choice.chunkX(), choice.chunkZ(), piece)));
        }
    }

    @Test
    void theCandidateOfACellIsTheOneTheGamePicks() {
        // The game's linear spread for the cell 3, -2: seeded as below, x and then z from the same random.
        final java.util.Random random =
                new java.util.Random(3 * 341_873_128_712L - 2 * 132_897_987_541L + SEED + 945_004);
        final int x = random.nextInt(8);
        final int z = random.nextInt(8);

        final int[] candidate = new Spread(8, 0, 945_004).candidate(SEED, 3, -2);

        assertEquals(List.of(24 + x, -16 + z), List.of(candidate[0], candidate[1]));
    }
}
