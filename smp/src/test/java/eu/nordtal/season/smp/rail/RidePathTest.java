package eu.nordtal.season.smp.rail;

import static eu.nordtal.season.smp.rail.Track.at;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RidePathTest {

    private static final double TOP = 24.0 / RidePath.TICKS_PER_SECOND;

    private static final RidePath.Motion MOTION = RidePath.Motion.of(6, 24);

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
        final RidePath path = RidePath.of(rails, centre(rails.getFirst()));

        final List<Double> speeds = ride(path);

        assertEquals(TOP, speeds.stream().mapToDouble(Double::doubleValue).max().orElseThrow(), 1e-9);
        for (int tick = 1; tick < speeds.size(); tick++) {
            assertTrue(speeds.get(tick) - speeds.get(tick - 1) <= MOTION.acceleration() + 1e-9, "no jump at " + tick);
        }
    }

    @Test
    void theCartBrakesToTheEndAtItsAccelerationAndStopsThere() {
        final List<RailPos> rails = straight(300);
        final RidePath path = RidePath.of(rails, centre(rails.getFirst()));

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
    void curvesAreRiddenAtTopSpeedWithNoBrakingBeforeThem() {
        // East for 120 rails, a curve to the south at x 120, south for 120 more, a curve to the east and on.
        final List<RailPos> rails = straight(120);
        for (int z = 1; z <= 120; z++) {
            rails.add(at(120, 64, z));
        }
        for (int x = 121; x <= 240; x++) {
            rails.add(at(x, 64, 120));
        }
        final RidePath path = RidePath.of(rails, centre(rails.getFirst()));

        double along = 0;
        double speed = 0;
        final List<Double> onCurves = new ArrayList<>();
        final List<Double> speeds = new ArrayList<>();
        while (along < path.length()) {
            speed = Math.min(path.next(along, speed, path.length(), MOTION), path.length() - along);
            along += speed;
            speeds.add(speed);
            final RailPos on = rails.get(path.railAt(along));
            if (on.equals(at(120, 64, 0)) || on.equals(at(120, 64, 120))) {
                onCurves.add(speed);
            }
        }

        assertFalse(onCurves.isEmpty(), "the cart rode over the curves");
        onCurves.forEach(onCurve -> assertEquals(TOP, onCurve, 1e-9, "a curve at top speed"));
        // Once at the top speed, the cart keeps it without a dip until it brakes for the end.
        int braking = speeds.indexOf(TOP);
        while (braking < speeds.size() && speeds.get(braking) == TOP) {
            braking++;
        }
        double braked = 0;
        for (int tick = braking; tick < speeds.size(); tick++) {
            braked += speeds.get(tick);
        }
        assertTrue(braked <= MOTION.stopping(TOP) + TOP, "braked only for the end, over " + braked + " blocks");
        assertGentle(speeds);
    }

    @Test
    void aCartBrakesForADistanceOnlyWhereItsBrakingCurveFitsIn() {
        final double speed = TOP;
        final double least = speed * speed / (2 * MOTION.acceleration()) - MOTION.acceleration() / 2;
        assertTrue(MOTION.brakes(speed, least), "just enough room");
        assertTrue(!MOTION.brakes(speed, least - 0.01), "too little room");
        assertTrue(MOTION.brakes(0, 0), "a standing cart needs none");

        final List<RailPos> rails = straight(300);
        final RidePath path = RidePath.of(rails, centre(rails.getFirst()));
        for (double room = least; room < least + 2; room += 0.01) {
            final double end = 100 + room;
            double along = 100;
            double before = speed;
            while (along < end) {
                final double next = Math.min(path.next(along, before, end, MOTION), end - along);
                assertTrue(before - next <= MOTION.acceleration() + 1e-9, "a hard brake with " + room + " room");
                along += next;
                before = next;
                if (next == 0) {
                    break;
                }
            }
            assertEquals(end, along, 1e-9, "stopped at its end with " + room + " room");
        }
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
        final RidePath path = RidePath.of(rails, centre(rails.getFirst()));

        assertEquals(new RidePath.Point(1.5, 64, 1), path.at(path.start(2)), "the curve is left at its south edge");
        assertEquals(new RidePath.Point(1.5, 65, 3.0), path.at(path.start(4)), "the slope ends one block higher");
        assertEquals(new RidePath.Point(1.5, 65, 3.5), path.at(path.length()));
    }
}
