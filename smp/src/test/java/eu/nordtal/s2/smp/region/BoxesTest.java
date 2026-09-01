package eu.nordtal.s2.smp.region;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The box arithmetic behind both the spawn protection and the balloons.
 *
 * <p>Worth its own test for one reason: an off-by-one on an inclusive corner is invisible in review
 * and shows up as a player able to break exactly one block of the tavern wall.
 */
class BoxesTest {

    private static final String WORLD = "nordtal";

    @Test
    void bothCornersAreInside() {
        final Box box = new Box(WORLD, 0, 60, 0, 4, 70, 4);

        assertTrue(box.contains(WORLD, 0, 60, 0), "the min corner is inside");
        assertTrue(box.contains(WORLD, 4, 70, 4), "the max corner is inside");
        assertTrue(box.contains(WORLD, 2, 65, 2));
    }

    @Test
    void oneBlockOutsideIsOutside() {
        final Box box = new Box(WORLD, 0, 60, 0, 4, 70, 4);

        assertFalse(box.contains(WORLD, -1, 65, 2));
        assertFalse(box.contains(WORLD, 5, 65, 2));
        assertFalse(box.contains(WORLD, 2, 59, 2));
        assertFalse(box.contains(WORLD, 2, 71, 2));
        assertFalse(box.contains(WORLD, 2, 65, -1));
        assertFalse(box.contains(WORLD, 2, 65, 5));
    }

    @Test
    void aBoxBelongsToExactlyOneWorld() {
        final Box box = new Box(WORLD, 0, 60, 0, 4, 70, 4);

        assertFalse(box.contains("farm", 2, 65, 2),
                "the same coordinates in another world are not the same place");
    }

    @Test
    void anInvertedBoxIsRefusedAtConstruction() {
        assertThrows(IllegalArgumentException.class,
                () -> new Box(WORLD, 10, 60, 0, 0, 70, 4));
        assertThrows(IllegalArgumentException.class,
                () -> new Box(WORLD, 0, 80, 0, 4, 70, 4));
        assertThrows(IllegalArgumentException.class,
                () -> new Box("", 0, 60, 0, 4, 70, 4));
    }

    @Test
    void theFirstMatchingBoxWins() {
        final Box inner = new Box(WORLD, 0, 60, 0, 2, 62, 2);
        final Box outer = new Box(WORLD, -10, 50, -10, 10, 80, 10);
        final Boxes boxes = new Boxes(List.of(inner, outer));

        assertEquals(inner, boxes.at(WORLD, 1, 61, 1).orElseThrow(),
                "configuration order is what makes a carve-out possible");
        assertEquals(outer, boxes.at(WORLD, 8, 61, 8).orElseThrow());
    }

    @Test
    void boxesAreFilteredByWorld() {
        final Boxes boxes = new Boxes(List.of(
                new Box(WORLD, 0, 0, 0, 1, 1, 1),
                new Box("farm", 0, 0, 0, 1, 1, 1),
                new Box(WORLD, 5, 0, 5, 6, 1, 6)));

        assertEquals(2, boxes.in(WORLD).size());
        assertEquals(1, boxes.in("farm").size());
        assertEquals(0, boxes.in("nordtal_the_end").size());
    }

    /**
     * The one number in this file that is not arbitrary: Nordtal's balloon has to sit outside
     * radius 10 and inside 21.5 of the border centre, or border 20 does not withhold the farm world
     * and the opening expansion to 43 hands over nothing.
     */
    @Test
    void horizontalDistanceIgnoresHeightAndMeasuresFromTheCentre() {
        final Box balloon = new Box(WORLD, 119, 64, 86, 123, 68, 90);

        // Centre of the box is 121/88; the border centre is 106/88, so fifteen blocks east.
        assertEquals(15.0, balloon.horizontalDistanceFrom(106, 88), 0.0001);

        final Box tooClose = new Box(WORLD, 104, 64, 86, 108, 68, 90);
        assertTrue(tooClose.horizontalDistanceFrom(106, 88) <= 10.0,
                "a balloon this close is inside the opening border and hands travel over for free");

        final Box tooFar = new Box(WORLD, 130, 64, 86, 134, 68, 90);
        assertTrue(tooFar.horizontalDistanceFrom(106, 88) >= 21.5,
                "a balloon this far is still outside the border after the first expansion");
    }
}
