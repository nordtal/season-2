package eu.nordtal.season.smp.rail;

import static eu.nordtal.season.smp.rail.Track.at;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RidePathTest {

    private static final double TOP = 24.0 / RidePath.TICKS_PER_SECOND;

    private static final RidePath.Motion MOTION = RidePath.Motion.of(6, 24, 10, 8, 32);

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
        final RidePath path = RidePath.of(rails, centre(rails.getFirst()), Set.of(), MOTION);

        final List<Double> speeds = ride(path);

        assertEquals(TOP, speeds.stream().mapToDouble(Double::doubleValue).max().orElseThrow(), 1e-9);
        for (int tick = 1; tick < speeds.size(); tick++) {
            assertTrue(speeds.get(tick) - speeds.get(tick - 1) <= MOTION.acceleration() + 1e-9, "no jump at " + tick);
        }
    }

    @Test
    void theCartBrakesWithinTheBrakingDistance() {
        final List<RailPos> rails = straight(300);
        final RidePath path = RidePath.of(rails, centre(rails.getFirst()), Set.of(), MOTION);

        final List<Double> speeds = ride(path);

        double braked = 0;
        for (int tick = speeds.size() - 1; tick >= 0 && speeds.get(tick) < TOP - 1e-9; tick--) {
            braked += speeds.get(tick);
        }
        assertTrue(braked <= 32 + MOTION.top(), "braked over " + braked + " blocks");
    }

    @Test
    void aCurveAndAJunctionAreRiddenSlowly() {
        // East for forty rails, a curve to the south at x 40, then south for forty more over a junction at z 20.
        final List<RailPos> rails = straight(40);
        for (int z = 1; z <= 40; z++) {
            rails.add(at(40, 64, z));
        }
        final RailPos curve = at(40, 64, 0);
        final RailPos junction = at(40, 64, 20);
        final RidePath path = RidePath.of(rails, centre(rails.getFirst()), Set.of(junction), MOTION);

        double along = 0;
        double speed = 0;
        double fastestOnCurve = 0;
        double fastestOnJunction = 0;
        while (along < path.length()) {
            speed = Math.min(path.next(along, speed, path.length(), MOTION), path.length() - along);
            along += speed;
            final RailPos on = rails.get(path.railAt(along));
            if (on.equals(curve)) {
                fastestOnCurve = Math.max(fastestOnCurve, speed);
            }
            if (on.equals(junction)) {
                fastestOnJunction = Math.max(fastestOnJunction, speed);
            }
        }

        assertTrue(fastestOnCurve > 0 && fastestOnCurve <= MOTION.curve() + 1e-9, "curve at " + fastestOnCurve);
        assertTrue(
                fastestOnJunction > 0 && fastestOnJunction <= MOTION.junction() + 1e-9,
                "junction at " + fastestOnJunction);
    }

    @Test
    void theLineCutsACurveFromEdgeToEdgeAndClimbsASlope() {
        final List<RailPos> rails = List.of(at(0, 64, 0), at(1, 64, 0), at(1, 64, 1), at(1, 65, 2), at(1, 65, 3));
        final RidePath path = RidePath.of(rails, centre(rails.getFirst()), Set.of(), MOTION);

        assertEquals(new RidePath.Point(1.5, 64, 1), path.at(path.start(2)), "the curve is left at its south edge");
        assertEquals(new RidePath.Point(1.5, 65, 3.0), path.at(path.start(4)), "the slope ends one block higher");
        assertEquals(new RidePath.Point(1.5, 65, 3.5), path.at(path.length()));
    }
}
