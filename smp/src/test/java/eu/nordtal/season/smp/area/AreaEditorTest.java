package eu.nordtal.season.smp.area;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.database.inbox.AreaShape;
import org.junit.jupiter.api.Test;

class AreaEditorTest {

    @Test
    void aTall3dAreaPlacesCornerHandlesAtEyeHeightClampedToItsSpanAndMatchesTheTopRing() {
        final AreaShape tall = new AreaShape("Tower", PolygonsTest.corners(0, 0, 20, 0, 20, 20, 0, 20), -64, 320);

        assertEquals(321.0, AreaEditor.topEdge(tall));
        assertEquals(71.6, AreaEditor.cornerHeight(tall, 71.6));
        assertEquals(-64.0, AreaEditor.cornerHeight(tall, -80.0));
        assertEquals(321.0, AreaEditor.cornerHeight(tall, 400.0));
    }

    @Test
    void anInvertedHeightSpanStillClampsCornerHandlesWithoutThrowing() {
        final AreaShape inverted = new AreaShape("UpsideDown", PolygonsTest.corners(0, 0, 10, 0, 10, 10), 80, 60);

        assertEquals(70.0, AreaEditor.cornerHeight(inverted, 70.0));
        assertEquals(61.0, AreaEditor.cornerHeight(inverted, 50.0));
        assertEquals(80.0, AreaEditor.cornerHeight(inverted, 90.0));
    }

    @Test
    void handleSightFiltersByHorizontalDistanceOnly() {
        assertTrue(AreaEditor.withinSight(10, 20, 10, 20));
        assertTrue(AreaEditor.withinSight(10 + AreaEditor.SIGHT, 20, 10, 20));
        assertFalse(AreaEditor.withinSight(10 + AreaEditor.SIGHT + 0.1, 20, 10, 20));
    }
}
