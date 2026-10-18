package eu.nordtal.season.smp.region;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

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

        assertFalse(box.contains("farm", 2, 65, 2), "the same coordinates in another world are not the same place");
    }

    @Test
    void anInvertedBoxIsRefusedAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new Box(WORLD, 10, 60, 0, 0, 70, 4));
        assertThrows(IllegalArgumentException.class, () -> new Box(WORLD, 0, 80, 0, 4, 70, 4));
        assertThrows(IllegalArgumentException.class, () -> new Box("", 0, 60, 0, 4, 70, 4));
    }

    @Test
    void theFirstMatchingBoxWins() {
        final Box inner = new Box(WORLD, 0, 60, 0, 2, 62, 2);
        final Box outer = new Box(WORLD, -10, 50, -10, 10, 80, 10);
        final Boxes boxes = new Boxes(List.of(inner, outer));

        assertEquals(
                inner,
                boxes.at(WORLD, 1, 61, 1).orElseThrow(),
                "configuration order is what makes a carve-out possible");
        assertEquals(outer, boxes.at(WORLD, 8, 61, 8).orElseThrow());
    }
}
