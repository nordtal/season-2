package eu.nordtal.season.smp.wheel;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.smp.config.WheelBlockSpec;
import eu.nordtal.season.spec.Specs;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WheelBlocksTest {

    private static final List<WheelBlockSpec> BLOCKS = List.of(
            Specs.createUnsafe(WheelBlockSpec.class, Map.of("world", "nordtal", "x", 3978, "y", 75, "z", 2171)));

    @Test
    void aClickOnAWheelBlockOpensTheWheel() {
        assertTrue(WheelBlocks.holds(BLOCKS, "nordtal", 3978, 75, 2171));
    }

    @Test
    void aClickOnTheBlockBesideItDoesNot() {
        assertFalse(WheelBlocks.holds(BLOCKS, "nordtal", 3978, 76, 2171), "the block above");
        assertFalse(WheelBlocks.holds(BLOCKS, "nordtal", 3979, 75, 2171), "the block east of it");
        assertFalse(WheelBlocks.holds(BLOCKS, "nordtal_nether", 3978, 75, 2171), "the same place in another world");
    }
}
