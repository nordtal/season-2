package eu.nordtal.season.smp.rail;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RailShapeTest {

    @Test
    void twoOppositeHeadingsMakeAStraightRail() {
        assertEquals(RailShape.NORTH_SOUTH, RailShape.joining(Heading.SOUTH, Heading.NORTH, null));
        assertEquals(RailShape.EAST_WEST, RailShape.joining(Heading.WEST, Heading.EAST, null));
    }

    @Test
    void twoNeighbouringHeadingsMakeACurve() {
        assertEquals(RailShape.NORTH_EAST, RailShape.joining(Heading.EAST, Heading.NORTH, null));
        assertEquals(RailShape.SOUTH_WEST, RailShape.joining(Heading.WEST, Heading.SOUTH, null));
    }

    @Test
    void aStraightRailRisesTowardsTheHigherNeighbour() {
        assertEquals(RailShape.ASCENDING_EAST, RailShape.joining(Heading.WEST, Heading.EAST, Heading.EAST));
        assertEquals(RailShape.ASCENDING_NORTH, RailShape.joining(Heading.NORTH, Heading.SOUTH, Heading.NORTH));
    }

    @Test
    void aCurveStaysFlatEvenWhenItsNeighbourIsHigher() {
        assertEquals(RailShape.NORTH_WEST, RailShape.joining(Heading.NORTH, Heading.WEST, Heading.NORTH));
    }

    @Test
    void everyShapeIsOneOfPapers() {
        for (final RailShape shape : RailShape.values()) {
            assertEquals(
                    shape.name(),
                    org.bukkit.block.data.Rail.Shape.valueOf(shape.name()).name());
        }
    }
}
