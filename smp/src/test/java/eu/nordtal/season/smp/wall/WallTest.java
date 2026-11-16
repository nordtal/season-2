package eu.nordtal.season.smp.wall;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WallTest {

    /** An L, 60 by 40 blocks, with its inner corner at 30, 20. */
    static final Area L = area(0, 0, 60, 0, 60, 40, 30, 40, 30, 20, 0, 20);

    static Area area(final double... coordinates) {
        final List<Area.Point> corners = new java.util.ArrayList<>();
        for (int i = 0; i < coordinates.length; i += 2) {
            corners.add(new Area.Point(coordinates[i], coordinates[i + 1]));
        }
        return new Area(corners);
    }

    @Test
    void theInsideOfAnLLeavesOutItsNotch() {
        final Wall wall = new Wall(List.of(L));

        assertTrue(wall.inside(10, 10));
        assertTrue(wall.inside(45, 35));
        assertFalse(wall.inside(10, 30));
        assertFalse(wall.inside(-1, 10));
    }

    @Test
    void theOutlineGetsOnePanelPerBlockOfEveryEdge() {
        // 60 + 40 + 30 + 20 + 30 + 20 blocks of outline.
        assertEquals(200, new Wall(List.of(L)).panelCount());
    }

    @Test
    void anEdgeTwoAreasShareGetsNoPanel() {
        final Wall wall = new Wall(List.of(area(0, 0, 10, 0, 10, 10, 0, 10), area(10, 0, 20, 0, 20, 10, 10, 10)));

        assertEquals(60, wall.panelCount());
        assertTrue(wall.near(10, 5, 0.6).isEmpty());
    }

    @Test
    void panelsStandOnWholeBlocksCountedFromTheEdgesFirstCorner() {
        // An edge of 3.4 blocks: slots at 0.5, 1.5 and 2.5, and the last panel ends at the corner.
        final Wall wall = new Wall(List.of(area(0.3, 0, 3.7, 0, 3.7, 10, 0.3, 10)));

        for (final double x : new double[] {0.8, 1.8, 2.8, 3.2}) {
            assertEquals(1, wall.near(x, 0, 1e-6).size(), "a panel at " + x);
        }
        assertEquals(4, wall.near(2, 0, 1.6).size());
    }

    @Test
    void panelsMeetAtACornerWithoutAGap() {
        final Wall wall = new Wall(List.of(L));

        final List<Panel> atCorner = wall.near(30, 20, 0.6);

        assertEquals(2, atCorner.size());
        for (final Panel panel : atCorner) {
            // A panel is one block wide, so its end reaches the corner.
            assertTrue(panel.distance(30, 20) <= 0.5 + 1e-9, panel.toString());
        }
    }

    @Test
    void aPanelTurnsAlongItsEdge() {
        final Wall wall = new Wall(List.of(L));

        assertEquals(0f, wall.near(10.5, 0, 0.1).getFirst().yaw());
        assertEquals(90f, wall.near(60, 10.5, 0.1).getFirst().yaw());
    }

    @Test
    void nearFindsPanelsAcrossCells() {
        final Wall wall = new Wall(List.of(area(-40, -40, 40, -40, 40, 40, -40, 40)));

        final long found = wall.near(0, -40, 32).size();

        assertEquals(64, found);
    }

    @Test
    void thePushPointsBackInsideFromBeyondAnEdgeAndFromTheNotch() {
        final Wall wall = new Wall(List.of(L));

        final double[] south = Push.velocity(wall, 10, -0.5);
        final double[] notch = Push.velocity(wall, 20, 21);

        assertArrayEquals(new double[] {0, Push.UP, Push.HORIZONTAL}, south, 1e-9);
        assertArrayEquals(new double[] {0, Push.UP, -Push.HORIZONTAL}, notch, 1e-9);
        assertNull(Push.velocity(wall, 10, 10));
    }

    @Test
    void aPlayerJustOutsideTheOutlineIsPushedAlongTheInwardNormal() {
        final Wall wall = new Wall(List.of(L));

        assertArrayEquals(new double[] {0, Push.UP, Push.HORIZONTAL}, Push.velocity(wall, 10, -0.01), 1e-9);
    }

    @Test
    void aPushWaitsHalfASecondAfterTheLast() {
        assertFalse(Push.due(1_000, 1_499));
        assertTrue(Push.due(1_000, 1_500));
    }

    @Test
    void thePixelLookFadesItsLastThreeBlocksAndTheFieldFadesInItsShader() {
        assertEquals("border/pixel", Look.PIXEL.model(26.9));
        assertEquals("border/pixel_fade_3", Look.PIXEL.model(27.5));
        assertEquals("border/pixel_fade_2", Look.PIXEL.model(28.5));
        assertEquals("border/pixel_fade_1", Look.PIXEL.model(29.5));
        assertEquals("border/field", Look.FIELD.model(29.5));
    }

    @Test
    void theFieldReachesPastTheFadeOfAThirdPersonCamera() {
        final Wall wall = new Wall(List.of(area(0, 0, 100, 0, 100, 10, 0, 10)));

        assertTrue(seenAt(wall, Look.FIELD, 82.5));
        assertFalse(seenAt(wall, Look.PIXEL, 82.5));
        assertTrue(seenAt(wall, Look.PIXEL, 79.5));
    }

    /** Whether a player at 50, 0 is sent the panel at {@code x} on the wall's edge at z 0. */
    private static boolean seenAt(final Wall wall, final Look look, final double x) {
        return Band.seen(wall, look, 50, 0).keySet().stream()
                .anyMatch(panel -> panel.z() == 0 && Math.abs(panel.x() - x) < 1e-9);
    }

    @Test
    void aChangeAddsRemovesAndRemodelsOnlyWhatDiffers() {
        final Panel kept = new Panel(0, 0, 0);
        final Panel faded = new Panel(1, 0, 0);
        final Panel gone = new Panel(2, 0, 0);
        final Panel added = new Panel(3, 0, 0);

        final Band.Change change = Band.change(
                Map.of(kept, "border/pixel", faded, "border/pixel", gone, "border/pixel"),
                Map.of(kept, "border/pixel", faded, "border/pixel_fade_1", added, "border/pixel"));

        assertEquals(Map.of(added, "border/pixel"), change.added());
        assertEquals(List.of(gone), change.removed());
        assertEquals(Map.of(faded, "border/pixel_fade_1"), change.remodelled());
    }

    @Test
    void theBandStandsFromThreeBelowTheFeet() {
        assertEquals(61, Band.base(64.4));
        assertEquals(-5, Band.base(-1.5));
    }
}
