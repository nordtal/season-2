package eu.nordtal.season.smp.wall;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FrontTest {

    private static final Area BASE = WallTest.area(0, 0, 10, 0, 10, 10, 0, 10);
    private static final Area EAST = WallTest.area(10, 0, 30, 0, 30, 10, 10, 10);
    private static final Area FAR_EAST = WallTest.area(30, 0, 50, 0, 50, 10, 30, 10);

    @Test
    void aFrontSpreadsIntoTheNewRegionAtTheConfiguredSpeedAndMovesTheWallPanels() {
        final Map<String, List<Area>> regions = Map.of("base", List.of(BASE), "east", List.of(EAST));
        final Front.Stage stage = new Front.Stage(Set.of("east"), 1_000);

        final Front.Shape halfway = Front.at(regions, Set.of("base"), List.of(stage), 6_000, 2.0);
        final Wall wall = new Wall(halfway.region(), halfway.lattice());

        assertEquals(List.of(stage), halfway.spreading());
        assertTrue(wall.inside(5, 5));
        assertTrue(wall.inside(19.5, 5), "ten blocks reached in five seconds at two blocks per second");
        assertFalse(wall.inside(20.5, 5), "beyond the front stays closed");
        assertFalse(wall.near(20, 5, 0.6).isEmpty(), "panels stand on the moving front");
        assertTrue(wall.near(10, 5, 0.4).isEmpty(), "the shared edge with the old region has no panels");
        assertTrue(wall.near(30, 5, 0.6).isEmpty(), "the unreached far edge has no panels yet");
    }

    @Test
    void aFrontFinishesOnceItReachesTheWholeRegion() {
        final Map<String, List<Area>> regions = Map.of("base", List.of(BASE), "east", List.of(EAST));
        final Front.Stage stage = new Front.Stage(Set.of("east"), 1_000);

        final Front.Shape finished = Front.at(regions, Set.of("base"), List.of(stage), 22_000, 1.0);
        final Wall wall = new Wall(finished.region(), finished.lattice());

        assertTrue(finished.spreading().isEmpty());
        assertTrue(wall.inside(29.5, 5));
        assertFalse(wall.inside(30.5, 5));
    }

    @Test
    void aStageOpeningOntoAnEmptyPlayableAreaOpensAtOnce() {
        final Map<String, List<Area>> regions = Map.of("base", List.of(BASE));
        final Front.Stage stage = new Front.Stage(Set.of("base"), 1_000);

        final Front.Shape shape = Front.at(regions, Set.of(), List.of(stage), 1_000, 1.0);
        final Wall wall = new Wall(shape.region(), shape.lattice());

        assertTrue(shape.spreading().isEmpty());
        assertTrue(wall.inside(5, 5));
    }

    @Test
    void chainedStagesEachSpreadFromEverythingPlayableBeforeThem() {
        final Map<String, List<Area>> regions =
                Map.of("base", List.of(BASE), "east", List.of(EAST), "far", List.of(FAR_EAST));
        final Front.Stage first = new Front.Stage(Set.of("east"), 0);
        final Front.Stage second = new Front.Stage(Set.of("far"), 20_000);

        // At 25 seconds with speed 1: first has reached x=30 (all of east), second has travelled 5 blocks into far.
        final Front.Shape shape = Front.at(regions, Set.of("base"), List.of(first, second), 25_000, 1.0);
        final Wall wall = new Wall(shape.region(), shape.lattice());

        assertEquals(List.of(second), shape.spreading());
        assertTrue(wall.inside(34.5, 5));
        assertFalse(wall.inside(35.5, 5));
    }
}
