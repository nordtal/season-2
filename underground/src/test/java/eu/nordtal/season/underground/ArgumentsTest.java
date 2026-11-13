package eu.nordtal.season.underground;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Reads the options of a command. */
class ArgumentsTest {

    @Test
    void anOptionWithoutAValueIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new Arguments(List.of("--seed")));
    }

    @Test
    void anOptionWithoutDashesIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new Arguments(List.of("seed", "1")));
    }

    @Test
    void aNumberFallsBackWhenTheOptionIsAbsent() {
        assertEquals(1.5, new Arguments(List.of()).number("roughness", 1.5));
        assertEquals(2.0, new Arguments(List.of("--roughness", "2")).number("roughness", 1.5));
    }

    @Test
    void aSeedIsAWholeNumber() {
        assertEquals(-27594263L, new Arguments(List.of("--seed", "-27594263")).whole("seed"));
        assertThrows(IllegalArgumentException.class, () -> new Arguments(List.of("--seed", "1.5")).whole("seed"));
        assertThrows(IllegalArgumentException.class, () -> new Arguments(List.of()).whole("seed"));
    }

    @Test
    void theRegionsAreTheRectangleBetweenTwoCorners() throws Exception {
        final List<int[]> regions = new Arguments(List.of("--regions", "7,2,8,3")).regions(Path.of("unused"));

        assertEquals(4, regions.size());
        assertArrayEquals(new int[] {7, 2}, regions.get(0));
        assertArrayEquals(new int[] {8, 3}, regions.get(3));
    }
}
