package eu.nordtal.season.smp.wall;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ViewerTest {

    private static final long WHOLE = Band.STRETCH_TICKS * 50L;

    @Test
    void aNewViewersPanelsAreTheBandOnTheGround() {
        final Viewer viewer = new Viewer("nordtal");

        assertEquals(0, viewer.stretched(1_000_000));
        assertEquals(0, viewer.left(1_000_000));
    }

    @Test
    void aTurnStretchesThePanelsEvenlyOverTheWholeTime() {
        final Viewer viewer = new Viewer("nordtal");
        viewer.turn(true, 1_000_000);

        assertEquals(0, viewer.stretched(1_000_000));
        assertEquals(Band.STRETCH_TICKS, viewer.left(1_000_000));
        assertEquals(0.5, viewer.stretched(1_000_000 + WHOLE / 2));
        assertEquals(Band.STRETCH_TICKS / 2, viewer.left(1_000_000 + WHOLE / 2));
        assertEquals(1, viewer.stretched(1_000_000 + 2 * WHOLE));
        assertEquals(0, viewer.left(1_000_000 + 2 * WHOLE));
    }

    @Test
    void aTurnHalfWayStartsFromWhereThePanelsAreAndTakesTheWholeTimeAgain() {
        final Viewer viewer = new Viewer("nordtal");
        viewer.turn(true, 1_000_000);
        viewer.turn(false, 1_000_000 + WHOLE / 2);

        assertEquals(0.5, viewer.stretched(1_000_000 + WHOLE / 2));
        assertEquals(0.25, viewer.stretched(1_000_000 + WHOLE));
        assertEquals(0, viewer.stretched(1_000_000 + 3 * WHOLE / 2));
    }
}
