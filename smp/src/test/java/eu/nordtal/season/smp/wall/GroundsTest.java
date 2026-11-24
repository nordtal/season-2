package eu.nordtal.season.smp.wall;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    void aPillarOneAndAHalfBlocksInsideRaisesThePanelGround() {
        // Panel at z = 0.03 with yaw 0 points inward along +z.
        final Panel panel = new Panel(10.5, 0.03, 0f);
        final int ground = Grounds.surfaceAt(panel, (x, z) -> z >= 1.0 ? 80 : 64);

        assertEquals(80, ground);
    }

    @Test
    void anUnloadedChunkAtEitherSampleReturnsUnknown() {
        final Panel panel = new Panel(10.5, 0.03, 0f);

        assertEquals(Band.UNKNOWN, Grounds.surfaceAt(panel, (x, z) -> z < 1.0 ? Band.UNKNOWN : 80));
        assertEquals(Band.UNKNOWN, Grounds.surfaceAt(panel, (x, z) -> z >= 1.0 ? Band.UNKNOWN : 64));
    }
}
