package eu.nordtal.season.smp.region;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.database.inbox.AreaShape.Corner;
import java.util.List;
import org.junit.jupiter.api.Test;

class PolygonAreasTest {

    private static final String WORLD = "nordtal";
    private static final List<Corner> SQUARE =
            List.of(new Corner(0, 0), new Corner(4, 0), new Corner(4, 4), new Corner(0, 4));
    private static final List<Corner> L_SHAPE = List.of(
            new Corner(0, 0), new Corner(6, 0), new Corner(6, 6), new Corner(3, 6), new Corner(3, 3), new Corner(0, 3));

    @Test
    void allEdgesAndHeightBoundsAreInclusive() {
        final PolygonArea area = new PolygonArea("Spawn", WORLD, SQUARE, 60, 70);

        assertTrue(area.containsBlock(WORLD, 0, 60, 0));
        assertTrue(area.containsBlock(WORLD, 4, 70, 4), "east and south edges and maxY are inside");
        assertTrue(area.containsBlock(WORLD, 4, 65, 2), "east edge is inside");
        assertTrue(area.containsBlock(WORLD, 2, 65, 4), "south edge is inside");
        assertTrue(area.containsPoint(WORLD, 4.5, 70.9, 4.5));

        assertFalse(area.containsBlock(WORLD, 5, 65, 2));
        assertFalse(area.containsBlock(WORLD, 2, 59, 2));
        assertFalse(area.containsBlock(WORLD, 2, 71, 2));
        assertFalse(area.containsPoint(WORLD, 2.5, 71.0, 2.5));
        assertFalse(area.containsBlock("farm", 2, 65, 2));
    }

    @Test
    void nonRectangularPolygonExcludesItsNotch() {
        final PolygonArea area = new PolygonArea("Arena", WORLD, L_SHAPE, 64, 80);

        assertTrue(area.containsBlock(WORLD, 1, 70, 1));
        assertTrue(area.containsBlock(WORLD, 5, 70, 5));
        assertTrue(area.containsBlock(WORLD, 3, 70, 3));
        assertFalse(area.containsBlock(WORLD, 1, 70, 5));
    }

    @Test
    void invalidAreaParametersAreRefusedAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new PolygonArea("Bad", "", SQUARE, 60, 70));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PolygonArea("Bad", WORLD, List.of(new Corner(0, 0), new Corner(1, 0)), 60, 70));
        assertThrows(IllegalArgumentException.class, () -> new PolygonArea("Bad", WORLD, SQUARE, 80, 70));
    }

    @Test
    void theFirstMatchingPolygonAreaWins() {
        final PolygonArea inner = new PolygonArea("Inner", WORLD, SQUARE, 60, 62);
        final PolygonArea outer = new PolygonArea(
                "Outer",
                WORLD,
                List.of(new Corner(-10, -10), new Corner(10, -10), new Corner(10, 10), new Corner(-10, 10)),
                50,
                80);
        final PolygonAreas areas = new PolygonAreas(List.of(inner, outer));

        assertEquals(inner, areas.atBlock(WORLD, 2, 61, 2).orElseThrow());
        assertEquals(outer, areas.atBlock(WORLD, 8, 61, 8).orElseThrow());
    }

    @Test
    void blockBasedPushbackMatchesFullEdgeBlockIncludingOuterHalf() {
        final PolygonArea area = new PolygonArea("Arena", WORLD, SQUARE, 60, 70);
        final PolygonAreas areas = new PolygonAreas(List.of(area));

        // Outer half of edge block x=4 has floor(4.8)=4 (inside block boundary) while containsPoint ends at 4.5.
        final double outerHalfX = 4.8;
        assertFalse(area.containsPoint(WORLD, outerHalfX, 65.0, 2.5));
        assertTrue(areas.atBlock(WORLD, (int) Math.floor(outerHalfX), 65, 2).isPresent());

        // Standing in the next block outside (x=5.01) has floor(5.01)=5, which is outside.
        assertFalse(areas.atBlock(WORLD, (int) Math.floor(5.01), 65, 2).isPresent());
        // Outward vector from the outer half of the edge block still points strictly outward (+x).
        final double[] outward = area.outward(outerHalfX, 2.5);
        assertTrue(outward[0] > 0.9, "outward vector pushes away from the arena");
    }
}
