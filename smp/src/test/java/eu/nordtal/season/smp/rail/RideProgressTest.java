package eu.nordtal.season.smp.rail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RideProgressTest {

    private static final double EXACT = 1e-9;

    @Test
    void aRideStartsAtNothingWithTheWholeWayLeft() {
        final RideProgress progress = new RideProgress(240.2);
        assertEquals(0, progress.done());
        assertEquals(241, progress.metres());
    }

    @Test
    void withoutAReplanItIsTheDistanceRiddenOverTheRoute() {
        final RideProgress progress = new RideProgress(200);
        double along = 0;
        for (final double step : new double[] {0.1, 0.3, 0.6, 1.2, 1.2, 1.2, 50, 0.4}) {
            along += step;
            progress.moved(200 - along);
            assertEquals(along / 200, progress.done(), EXACT, "after " + along + " blocks of 200");
        }
    }

    @Test
    void itStandsStillWhileTheCartWaitsAtAJunction() {
        final RideProgress progress = new RideProgress(100);
        progress.moved(70);
        final double before = progress.done();
        for (int tick = 0; tick < 40; tick++) {
            progress.moved(70);
        }
        assertEquals(before, progress.done());
        assertEquals(70, progress.metres());
    }

    @Test
    void itNeverShrinksAndReachesOneOnlyAtTheStation() {
        final RideProgress progress = new RideProgress(500);
        double last = 0;
        for (double left = 500; left > 0; left -= 0.7) {
            progress.moved(left);
            assertTrue(progress.done() >= last, "the progress went back at " + left + " blocks left");
            assertTrue(progress.done() < 1, "the progress is full " + left + " blocks before the station");
            last = progress.done();
        }
        progress.moved(0);
        assertEquals(1, progress.done());
        assertEquals(0, progress.metres());
    }

    @Test
    void aReplanToALongerWayKeepsTheProgressAndFillsTheRestAlongTheNewWay() {
        final RideProgress progress = new RideProgress(100);
        progress.moved(50);
        assertEquals(0.5, progress.done(), EXACT);

        // The rail ahead broke and the new way is 150 blocks from here.
        progress.replanned(150);
        assertEquals(0.5, progress.done(), EXACT, "a replan moved the bar");
        assertEquals(150, progress.metres());

        progress.moved(75);
        assertEquals(0.75, progress.done(), EXACT, "half the new way is not half the rest of the bar");
        progress.moved(0);
        assertEquals(1, progress.done());
    }

    @Test
    void aReplanToAShorterWayFillsTheRestFaster() {
        final RideProgress progress = new RideProgress(100);
        progress.moved(80);
        progress.replanned(10);
        progress.moved(5);
        assertEquals(0.2 + 0.8 * 0.5, progress.done(), EXACT);
        assertEquals(5, progress.metres());
    }

    @Test
    void anArrivalFillsTheBarWhereverTheCartStopped() {
        final RideProgress progress = new RideProgress(100);
        progress.moved(0.4);
        progress.arrived();
        assertEquals(1, progress.done());
        assertEquals(0, progress.metres());
    }
}
