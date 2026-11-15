package eu.nordtal.season.smp.eye;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PortalTest {

    /** Returns the twelve frames of a portal ring whose missing corner lies at x and z. */
    static List<Frame> ring(final int x, final int y, final int z) {
        final List<Frame> frames = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            frames.add(new Frame(x + i, y, z));
            frames.add(new Frame(x + i, y, z + 4));
            frames.add(new Frame(x, y, z + i));
            frames.add(new Frame(x + 4, y, z + i));
        }
        return frames;
    }

    @Test
    void theTwelveFramesOfARingAreOnePortalAtItsCentre() {
        assertEquals(List.of(new Portal(5234.5, 17, 3762.5)), Portal.of(ring(5232, 17, 3760)));
    }

    @Test
    void twoRingsAreTwoPortals() {
        final List<Frame> frames = new ArrayList<>(ring(5232, 17, 3760));
        frames.addAll(ring(2912, 5, 3136));

        assertEquals(List.of(new Portal(2914.5, 5, 3138.5), new Portal(5234.5, 17, 3762.5)), Portal.of(frames));
    }

    @Test
    void aRingStillBeingBuiltIsAPortal() {
        assertEquals(
                List.of(new Portal(11, 30, 20.5)), Portal.of(List.of(new Frame(10, 30, 20), new Frame(11, 30, 20))));
    }

    @Test
    void framesFarApartInHeightAreTwoPortals() {
        assertEquals(
                2, Portal.of(List.of(new Frame(0, 10, 0), new Frame(0, 20, 0))).size());
    }

    @Test
    void theNearestPortalIsCountedAcrossTheGround() {
        final Portal low = new Portal(0.5, -50, 0.5);
        final Portal high = new Portal(100.5, 200, 0.5);

        assertEquals(Optional.of(low), Portal.nearest(List.of(high, low), 40, 0));
        assertEquals(Optional.of(high), Portal.nearest(List.of(high, low), 60, 0));
    }

    @Test
    void noFramesAreNoPortals() {
        assertEquals(List.of(), Portal.of(List.of()));
        assertEquals(Optional.empty(), Portal.nearest(List.of(), 0, 0));
    }
}
