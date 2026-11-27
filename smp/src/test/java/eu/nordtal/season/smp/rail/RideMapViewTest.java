package eu.nordtal.season.smp.rail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Tests map viewport panning, zooming, full-area coverage, rail interpolation, and destination label clamping. */
class RideMapViewTest {

    @Test
    void pansAndZoomsWithinThePlayAreaAndDisablesOutOfBoundsEdges() {
        final RideMapView.Viewport corner = new RideMapView.Viewport(RideMapView.SCALE_CLOSE, 0, 0);
        assertFalse(corner.canPanLeft());
        assertFalse(corner.canPanUp());
        assertTrue(corner.canPanRight());
        assertTrue(corner.canPanDown());
        assertFalse(corner.canZoomIn());
        assertTrue(corner.canZoomOut());
        assertEquals("nordtal:map_8", corner.mapFont());
        assertEquals(Character.toString(0xF0000), corner.tileGlyph(0, 0, false));
        assertEquals(Character.toString(0xF1000), corner.tileGlyph(0, 0, true));

        final RideMapView.Viewport far = corner.zoomed(RideMapView.SCALE_FAR);
        assertEquals(RideMapView.SCALE_FAR, far.scale());
        assertTrue(far.canZoomIn());
        assertFalse(far.canZoomOut());
        assertEquals("nordtal:map_16", far.mapFont());

        final RideMapView.Viewport max = new RideMapView.Viewport(RideMapView.SCALE_FAR, 99, 99);
        assertEquals(7, max.tileX());
        assertEquals(9, max.tileY());
        assertFalse(max.canPanRight());
        assertFalse(max.canPanDown());
    }

    @Test
    void everyCoordinateInThePlayAreaIsReachableWithZeroVerticalOrHorizontalDeadZones() {
        for (final int scale : List.of(RideMapView.SCALE_CLOSE, RideMapView.SCALE_FAR)) {
            for (int z = 0; z < RideMapView.PLAY_SIZE; z += scale) {
                final RideMapView.Viewport view = RideMapView.centredOn(scale, 5000, z);
                assertTrue(
                        view.visible(view.pixelX(5000), view.pixelY(z)),
                        "z=" + z + " at scale=" + scale + " must be visible");
            }
            for (int x = 0; x < RideMapView.PLAY_SIZE; x += scale) {
                final RideMapView.Viewport view = RideMapView.centredOn(scale, x, 5000);
                assertTrue(
                        view.visible(view.pixelX(x), view.pixelY(5000)),
                        "x=" + x + " at scale=" + scale + " must be visible");
            }
        }
    }

    @Test
    void initialViewportFramesNearbyStationsAtCloseScaleAndDistantStationsAtFarScale() {
        final RailPos origin = new RailPos(400, 64, 400);
        final RailPos nearby = new RailPos(800, 64, 560);
        final RideMapView.Viewport close = RideMapView.initial(origin, nearby, false);
        assertEquals(RideMapView.SCALE_CLOSE, close.scale());
        assertTrue(close.visible(close.pixelX(origin.x()), close.pixelY(origin.z())));
        assertTrue(close.visible(close.pixelX(nearby.x()), close.pixelY(nearby.z())));

        final RailPos distant = new RailPos(4000, 64, 4000);
        final RideMapView.Viewport far = RideMapView.initial(origin, distant, false);
        assertEquals(RideMapView.SCALE_FAR, far.scale());

        final RideMapView.Viewport hiddenDest = RideMapView.initial(origin, distant, true);
        assertEquals(RideMapView.SCALE_CLOSE, hiddenDest.scale());
        assertTrue(hiddenDest.visible(hiddenDest.pixelX(origin.x()), hiddenDest.pixelY(origin.z())));
    }

    @Test
    void rasterizesSparseEdgesAsContinuousClippedSegments() {
        final RailPos a = new RailPos(160, 64, 160);
        final RailPos b = new RailPos(320, 64, 160);
        final RailPos off = new RailPos(9000, 64, 9000);
        final RailGraph graph = new RailGraph(
                List.of(a, b, off), List.of(), List.of(RailEdge.of(a, b, List.of()), RailEdge.of(b, off, List.of())));
        final RideMapView.Viewport view = new RideMapView.Viewport(RideMapView.SCALE_CLOSE, 0, 0);

        final List<RideMapView.Run> runs = RideMapView.rasterize(graph, List.of(a, b), view);

        final int routePixels = runs.stream()
                .filter(run -> run.layer() == RideMapView.Layer.ROUTE)
                .mapToInt(RideMapView.Run::width)
                .sum();
        assertTrue(routePixels >= 40, "sparse endpoints must interpolate into a continuous route line");
        assertTrue(runs.stream()
                .allMatch(run -> run.x() >= 0
                        && run.x() + run.width() <= RideMapView.VIEW_WIDTH
                        && run.y() >= 0
                        && run.y() < RideMapView.VIEW_HEIGHT));
    }

    @Test
    void clampsDestinationLabelInsideTheViewportNearCorners() {
        final RideMapView.LabelBox topLeft = RideMapView.destinationLabel("Harbour", 2, 2);
        assertTrue(topLeft.x() >= 2);
        assertTrue(topLeft.y() >= 2);
        assertTrue(topLeft.x() + topLeft.width() <= RideMapView.VIEW_WIDTH - 2);
        assertTrue(topLeft.y() + topLeft.height() <= RideMapView.VIEW_HEIGHT - 2);

        final RideMapView.LabelBox bottomRight =
                RideMapView.destinationLabel("Bernsteinbucht", RideMapView.VIEW_WIDTH - 1, RideMapView.VIEW_HEIGHT - 1);
        assertTrue(bottomRight.x() >= 2);
        assertTrue(bottomRight.y() >= 2);
        assertTrue(bottomRight.x() + bottomRight.width() <= RideMapView.VIEW_WIDTH - 2);
        assertTrue(bottomRight.y() + bottomRight.height() <= RideMapView.VIEW_HEIGHT - 2);
    }
}
