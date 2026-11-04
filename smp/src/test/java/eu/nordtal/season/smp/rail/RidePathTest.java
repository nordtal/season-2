package eu.nordtal.season.smp.rail;

import static eu.nordtal.season.smp.rail.Track.at;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RidePathTest {

    private static final double TOP = 24.0 / RidePath.TICKS_PER_SECOND;

    private static final RidePath.Motion MOTION = RidePath.Motion.of(6, 24, 10);

    private static List<RailPos> straight(final int length) {
        final List<RailPos> rails = new ArrayList<>();
        for (int x = 0; x <= length; x++) {
            rails.add(at(x, 64, 0));
        }
        return rails;
    }

    private static RidePath.Point centre(final RailPos rail) {
        return new RidePath.Point(rail.x() + 0.5, rail.y(), rail.z() + 0.5);
    }

    /** Rides {@code path} to its end and returns the speed of every tick. */
    private static List<Double> ride(final RidePath path) {
        final List<Double> speeds = new ArrayList<>();
        double along = 0;
        double speed = 0;
        for (int tick = 0; tick < 10_000; tick++) {
            speed = Math.min(path.next(along, speed, path.length(), MOTION), path.length() - along);
            along += speed;
            speeds.add(speed);
            if (speed == 0 && along >= path.length()) {
                return speeds;
            }
        }
        throw new AssertionError("the cart never stopped");
    }

    @Test
    void aCartSpeedsUpSteadilyToTheTopSpeedAndStopsAtTheEnd() {
        final List<RailPos> rails = straight(300);
        final RidePath path = RidePath.of(rails, centre(rails.getFirst()), MOTION);

        final List<Double> speeds = ride(path);

        assertEquals(TOP, speeds.stream().mapToDouble(Double::doubleValue).max().orElseThrow(), 1e-9);
        for (int tick = 1; tick < speeds.size(); tick++) {
            assertTrue(speeds.get(tick) - speeds.get(tick - 1) <= MOTION.acceleration() + 1e-9, "no jump at " + tick);
        }
    }

    @Test
    void theCartBrakesToTheEndAtItsAccelerationAndStopsThere() {
        final List<RailPos> rails = straight(300);
        final RidePath path = RidePath.of(rails, centre(rails.getFirst()), MOTION);

        final List<Double> speeds = ride(path);

        assertGentle(speeds);
        double braked = 0;
        for (int tick = speeds.size() - 1; tick >= 0 && speeds.get(tick) < TOP - 1e-9; tick--) {
            braked += speeds.get(tick);
        }
        final double stopping = TOP * TOP / (2 * MOTION.acceleration());
        assertTrue(braked >= stopping, "braked over " + braked + " blocks, not " + stopping);
        assertTrue(speeds.get(speeds.size() - 2) > 0, "the cart stood still only at the end");
    }

    @Test
    void aCurveIsRiddenSlowlyAndBrakedForAtTheAcceleration() {
        // East for 120 rails, a curve to the south at x 120, then south for sixty more.
        final List<RailPos> rails = straight(120);
        for (int z = 1; z <= 60; z++) {
            rails.add(at(120, 64, z));
        }
        final RailPos curve = at(120, 64, 0);
        final RidePath path = RidePath.of(rails, centre(rails.getFirst()), MOTION);

        double along = 0;
        double speed = 0;
        double fastestOnCurve = 0;
        final List<Double> speeds = new ArrayList<>();
        while (along < path.length()) {
            speed = Math.min(path.next(along, speed, path.length(), MOTION), path.length() - along);
            along += speed;
            speeds.add(speed);
            if (rails.get(path.railAt(along)).equals(curve)) {
                fastestOnCurve = Math.max(fastestOnCurve, speed);
            }
        }

        assertTrue(fastestOnCurve > 0 && fastestOnCurve <= MOTION.curve() + 1e-9, "curve at " + fastestOnCurve);
        assertTrue(
                speeds.stream().mapToDouble(Double::doubleValue).max().orElseThrow() >= TOP - 1e-9,
                "at top speed before it");
        assertGentle(speeds);
    }

    /** Asserts that the speed never changes by more than one tick's acceleration, from standing to standing. */
    private static void assertGentle(final List<Double> speeds) {
        double before = 0;
        for (int tick = 0; tick < speeds.size(); tick++) {
            final double change = speeds.get(tick) - before;
            assertTrue(Math.abs(change) <= MOTION.acceleration() + 1e-9, "changed by " + change + " at " + tick);
            before = speeds.get(tick);
        }
    }

    @Test
    void theLineCutsACurveFromEdgeToEdgeAndClimbsASlope() {
        final List<RailPos> rails = List.of(at(0, 64, 0), at(1, 64, 0), at(1, 64, 1), at(1, 65, 2), at(1, 65, 3));
        final RidePath path = RidePath.of(rails, centre(rails.getFirst()), MOTION);

        assertEquals(new RidePath.Point(1.5, 64, 1), path.at(path.start(2)), "the curve is left at its south edge");
        assertEquals(new RidePath.Point(1.5, 65, 3.0), path.at(path.start(4)), "the slope ends one block higher");
        assertEquals(new RidePath.Point(1.5, 65, 3.5), path.at(path.length()));
    }
}
