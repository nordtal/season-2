package eu.nordtal.season.smp.eye;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class EyeTargetTest {

    private static final Portal EAST = new Portal(5234.5, 17, 3762.5);
    private static final Portal WEST = new Portal(2914.5, 5, 3138.5);

    @Test
    void anEyeFliesToTheNearestPortalOfTheMap() {
        assertEquals(new EyeTarget.ToPortal(EAST), EyeTarget.of(List.of(WEST, EAST), 5000, 3000));
        assertEquals(new EyeTarget.ToPortal(WEST), EyeTarget.of(List.of(WEST, EAST), 3500, 2000));
    }

    @Test
    void anEyeInAWorldWithoutAKnownPortalIsNotThrown() {
        assertEquals(new EyeTarget.NotThrown(), EyeTarget.of(List.of(), 0, 0));
    }
}
