package eu.nordtal.season.smp.area;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.database.inbox.AreaShape;
import eu.nordtal.season.database.inbox.AreaShape.Corner;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PolygonsTest {

    static List<Corner> corners(final int... coordinates) {
        final List<Corner> corners = new ArrayList<>();
        for (int i = 0; i < coordinates.length; i += 2) {
            corners.add(new Corner(coordinates[i], coordinates[i + 1]));
        }
        return corners;
    }

    private static AreaShape shape(final int bottom, final int top, final int... coordinates) {
        return new AreaShape("Spawn", corners(coordinates), bottom, top);
    }

    @Test
    void aSquareOnTheMapIsSaveable() {
        assertNull(Polygons.problem(shape(0, 0, 100, 100, 200, 100, 200, 200, 100, 200), false, true));
    }

    @Test
    void anAreaOfTwoCornersEnclosesNothing() {
        assertEquals(
                new Polygons.Problem("corners", "the area \"Spawn\" needs at least 3 corners, has 2"),
                Polygons.problem(shape(0, 0, 1, 2, 3, 4), false, false));
    }

    @Test
    void aBowTieCrossesItself() {
        final Polygons.Problem problem = Polygons.problem(shape(0, 0, 0, 0, 10, 10, 10, 0, 0, 10), false, false);

        assertEquals("corners", problem.field());
        assertEquals("the edges of the area \"Spawn\" cross or touch each other", problem.sentence());
    }

    @Test
    void edgesThatOnlyTouchOrFoldBackOrRepeatACornerAreRefusedToo() {
        // A corner on the far edge, an edge folding back along its neighbour, and a corner twice.
        assertTrue(Polygons.selfIntersects(corners(0, 0, 10, 0, 10, 10, 5, 0)));
        assertTrue(Polygons.selfIntersects(corners(0, 0, 10, 0, 5, 0)));
        assertTrue(Polygons.selfIntersects(corners(0, 0, 10, 0, 10, 0, 0, 10)));
        assertTrue(Polygons.selfIntersects(corners(0, 0, 10, 0, 10, 10, 0, 10, 5, 0)));
    }

    @Test
    void anLAndATriangleDoNotCrossThemselves() {
        assertFalse(Polygons.selfIntersects(corners(0, 0, 60, 0, 60, 40, 30, 40, 30, 20, 0, 20)));
        assertFalse(Polygons.selfIntersects(corners(0, 0, 10, 0, 0, 10)));
    }

    @Test
    void anOverworldCornerOffTheMapIsRefusedAndElsewhereAllowed() {
        final AreaShape edge = shape(0, 0, 0, 0, 10239, 0, 10239, 10240);

        assertEquals(
                "the area \"Spawn\" has a corner at 10239, 10240 outside the map from 0 to 10239",
                Polygons.problem(edge, false, true).sentence());
        assertNull(Polygons.problem(edge, false, false));
    }

    @Test
    void aBottomAboveTheTopIsRefusedOnlyWhereAnAreaHasHeights() {
        final AreaShape upsideDown = shape(80, 60, 0, 0, 10, 0, 10, 10);

        assertEquals(
                new Polygons.Problem("bottom", "the area \"Spawn\" has its bottom at 80, above its top at 60"),
                Polygons.problem(upsideDown, true, false));
        assertNull(Polygons.problem(upsideDown, false, false));
        assertNull(Polygons.problem(shape(60, 60, 0, 0, 10, 0, 10, 10), true, false));
    }

    @Test
    void aClickFindsTheNearestEdgeWithinReachAndNoneFarther() {
        final List<Corner> square = corners(0, 0, 10, 0, 10, 10, 0, 10);

        assertEquals(1, Polygons.nearestEdge(square, 11, 5, 1.5));
        assertEquals(3, Polygons.nearestEdge(square, -1, 4, 1.5));
        assertEquals(-1, Polygons.nearestEdge(square, 5, 5, 1.5));
        assertArrayEquals(new double[] {10, 0}, Polygons.closest(new Corner(0, 0), new Corner(10, 0), 14, 3), 1e-9);
    }

    @Test
    void aHeightHandleFollowsTheLookToTheLineThroughItsColumn() {
        // Four blocks away, looking up at 45 degrees, the line is met four blocks above the eye.
        assertEquals(68, Polygons.heightAt(new double[] {0, 64, 0}, new double[] {0.5, 0.5, 0}, 4, 0), 1e-9);
        // Looking away from the column meets it only at the eye.
        assertEquals(64, Polygons.heightAt(new double[] {0, 64, 0}, new double[] {-1, 1, 0}, 4, 0), 1e-9);
        // Straight up has no horizontal part, so the eye's height stays.
        assertEquals(64, Polygons.heightAt(new double[] {0, 64, 0}, new double[] {0, 1, 0}, 4, 0), 1e-9);
    }
}
