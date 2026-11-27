package eu.nordtal.season.smp.wall;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.IntUnaryOperator;
import org.junit.jupiter.api.Test;

class GroundsTest {

    @Test
    void aCeilingWorldScansDownwardFromThePlayersHeightAndIgnoresTheRoof() {
        // Bedrock roof at y 127, open cavern below, netherrack floor at y 32.
        final int ground = Grounds.floorFrom(65, 0, 128, y -> y == 127 || y <= 32);

        assertEquals(33, ground);
    }

    @Test
    void aCeilingWorldReturnsTheTopOfASolidBlockAtTheStartingHeight() {
        assertEquals(66, Grounds.floorFrom(65, 0, 128, y -> y <= 70));
    }

    @Test
    void aCeilingWorldFallsBackToTheWorldBottomWhenNothingHoldsBelow() {
        assertEquals(0, Grounds.floorFrom(65, 0, 128, y -> y == 127));
    }

    @Test
    void aCeilingWorldClampsTheStartingHeightIntoTheWorldRange() {
        assertEquals(128, Grounds.floorFrom(200, 0, 128, y -> y == 127));
        assertEquals(0, Grounds.floorFrom(-10, 0, 128, y -> false));
    }

    @Test
    void aPillarOneAndAHalfBlocksInsideIsHighAndLeavesTheLowSurface() {
        // Panel at z = 0.03 with yaw 0 points inward along +z.
        final Panel panel = new Panel(10.5, 0.03, 0f);

        assertEquals(new Grounds.Surface(80, 64), Grounds.surfaceAt(panel, (x, z) -> z >= 1.0 ? 80 : 64));
    }

    @Test
    void aBlockOutsideTheEdgeIsNotHigh() {
        final Panel panel = new Panel(10.5, 0.03, 0f);

        assertEquals(new Grounds.Surface(64, 64), Grounds.surfaceAt(panel, (x, z) -> z < 0 ? 80 : 64));
    }

    @Test
    void anUnloadedChunkAtAnySampleReturnsNoSurface() {
        final Panel panel = new Panel(10.5, 0.03, 0f);

        assertNull(Grounds.surfaceAt(panel, (x, z) -> z < 0 ? Grounds.UNLOADED : 80));
        assertNull(Grounds.surfaceAt(panel, (x, z) -> z > 0 && z < 1.0 ? Grounds.UNLOADED : 80));
        assertNull(Grounds.surfaceAt(panel, (x, z) -> z >= 1.0 ? Grounds.UNLOADED : 64));
    }

    @Test
    void onOpenGroundTheWallStandsOnItAndDoesNotRise() {
        assertEquals(new Band.Ground(64, 0), shaped(50, x -> 64));
    }

    @Test
    void aPillarLeavesTheFootAndRaisesTheWallInABellAroundIt() {
        final IntUnaryOperator pillar = x -> x == 50 ? 74 : 64;

        assertEquals(new Band.Ground(64, 10), shaped(50, pillar));
        int before = 10;
        for (int x = 51; x <= 70; x++) {
            final Band.Ground ground = shaped(x, pillar);
            assertEquals(64, ground.foot(), "foot at " + x);
            assertTrue(ground.rise() <= before, "falls at " + x);
            assertTrue(before - ground.rise() <= 1, "no step at " + x);
            assertEquals(shaped(100 - x, pillar), ground, "the same on both sides at " + x);
            before = ground.rise();
        }
        assertEquals(0, before);
    }

    @Test
    void aLineOfBlocksOverheadLeavesTheFootOnTheGround() {
        // Six blocks wide, across the edge, so the surface outside is as high as inside.
        final IntUnaryOperator line = x -> x >= 48 && x <= 53 ? 90 : 64;

        assertEquals(new Band.Ground(64, 26), shaped(50, line));
    }

    @Test
    void aStoneWallBeforeTheBorderRaisesTheWallAlongItAndLeavesTheFoot() {
        final Panel panel = new Panel(50.5, 0.03, 0f);
        final Grounds.Surface behindStone = new Grounds.Surface(70, 64);

        assertEquals(new Band.Ground(64, 6), Grounds.shape(panel, row(), near -> behindStone));
    }

    @Test
    void highGroundKeepsItsFootWhereNothingNearbyIsLower() {
        final IntUnaryOperator cliff = x -> x >= 50 ? 100 : 64;

        assertEquals(new Band.Ground(100, 0), shaped(70, cliff));
        assertEquals(new Band.Ground(64, 36), shaped(50, cliff));
    }

    @Test
    void aWallHigherThanTheTintCarriesKeepsItsUpperEnd() {
        final Band.Ground ground = shaped(50, x -> x == 50 ? 200 : 64);

        assertEquals(Band.HIGHEST_RISE, ground.rise());
        assertEquals(200, ground.foot() + ground.rise());
    }

    @Test
    void aPanelWithoutItsSurfaceIsUnknownAndAnUnknownNeighbourIsLeftOut() {
        final Panel panel = new Panel(50.5, 0.03, 0f);

        assertEquals(Band.Ground.UNKNOWN, Grounds.shape(panel, row(), near -> null));
        assertEquals(
                new Band.Ground(64, 0),
                Grounds.shape(panel, row(), near -> near.equals(panel) ? new Grounds.Surface(64, 64) : null));
    }

    /** The ground of the panel at block {@code x} on an edge along x whose surface at each block is {@code height}. */
    private static Band.Ground shaped(final int x, final IntUnaryOperator height) {
        return Grounds.shape(new Panel(x + 0.5, 0.03, 0f), row(), near -> {
            final int surface = height.applyAsInt((int) Math.floor(near.x()));
            return new Grounds.Surface(surface, surface);
        });
    }

    private static List<Panel> row() {
        return java.util.stream.IntStream.range(0, 100)
                .mapToObj(x -> new Panel(x + 0.5, 0.03, 0f))
                .toList();
    }
}
