package eu.nordtal.season.smp.rail;

import static eu.nordtal.season.smp.rail.Track.at;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class JunctionStopsTest {

    private static final RidePath.Motion MOTION = RidePath.Motion.of(6, 24, 10, 8, 32);

    /** The shape each junction has in the world now. */
    private final Map<RailPos, RailShape> world = new HashMap<>();

    /** Every switch made: the junction, the tick, where the cart stood and how fast it went. */
    private final List<Switch> switched = new ArrayList<>();

    private record Switch(RailPos rail, int tick, double along, double speed) {}

    /** What one tick ended with. */
    private record Tick(double along, double speed) {}

    private final JunctionStops.Junctions junctions = new JunctionStops.Junctions() {
        @Override
        public boolean wrong(final RailPos rail, final RailShape wanted) {
            return world.get(rail) != wanted;
        }

        @Override
        public void set(final RailPos rail, final RailShape wanted) {
            world.put(rail, wanted);
            switched.add(new Switch(rail, tick, along, speed));
        }
    };

    private int tick;
    private double along;
    private double speed;

    private static List<RailPos> straight(final int length) {
        final List<RailPos> rails = new ArrayList<>();
        for (int x = 0; x <= length; x++) {
            rails.add(at(x, 64, 0));
        }
        return rails;
    }

    private static RidePath path(final List<RailPos> rails, final RailPos... junctionsOnIt) {
        final RailPos first = rails.getFirst();
        return RidePath.of(
                rails, new RidePath.Point(first.x() + 0.5, first.y(), first.z() + 0.5), Set.of(junctionsOnIt), MOTION);
    }

    /**
     * Rides {@code path} to its end as {@code Rides} does, and returns every tick.
     * {@code during} runs before each tick, so a test can switch a junction back as a player would.
     */
    private List<Tick> ride(final RidePath path, final Map<Integer, RailShape> switches, final Runnable during) {
        final JunctionStops stops = new JunctionStops();
        final List<Tick> ticks = new ArrayList<>();
        along = 0;
        speed = 0;
        for (tick = 0; tick < 10_000; tick++) {
            during.run();
            final double stop = stops.limit(path, switches, along, speed, path.length(), junctions);
            final double limit = Math.max(along, Math.min(path.length(), stop));
            speed = Math.min(path.next(along, speed, limit, MOTION), limit - along);
            along += speed;
            ticks.add(new Tick(along, speed));
            if (speed == 0 && along >= path.length()) {
                return ticks;
            }
        }
        throw new AssertionError("the cart never arrived");
    }

    @Test
    void aJunctionSetTheWrongWayStopsTheCartTwoBlocksBeforeItIsSwitchedAndTheRideGoesOn() {
        final List<RailPos> rails = straight(60);
        final RailPos junction = rails.get(30);
        final RidePath path = path(rails, junction);
        world.put(junction, RailShape.NORTH_SOUTH);

        final List<Tick> ticks = ride(path, Map.of(30, RailShape.EAST_WEST), () -> {});

        assertEquals(1, switched.size(), "switched once");
        final Switch made = switched.getFirst();
        assertEquals(0, made.speed(), "switched by a standing cart");
        assertEquals(path.start(30) - JunctionStops.BEFORE, made.along(), 1e-6, "standing two blocks before it");
        assertEquals(RailShape.EAST_WEST, world.get(junction));
        for (int t = made.tick(); t < made.tick() + JunctionStops.PAUSE; t++) {
            assertEquals(0, ticks.get(t).speed(), "the cart stands a moment after the switch, at tick " + t);
        }
        assertTrue(ticks.get(made.tick() + JunctionStops.PAUSE + 1).speed() > 0, "then it sets off");
        assertEquals(path.length(), ticks.getLast().along(), 1e-6, "and arrives");
    }

    @Test
    void aJunctionSetRightIsPassedAtJunctionSpeedWithoutAStop() {
        final List<RailPos> rails = straight(60);
        final RailPos junction = rails.get(30);
        final RidePath path = path(rails, junction);
        world.put(junction, RailShape.EAST_WEST);

        final List<Tick> ticks = ride(path, Map.of(30, RailShape.EAST_WEST), () -> {});

        assertTrue(switched.isEmpty(), "nothing is switched");
        double fastestOnIt = 0;
        for (int t = 0; t < ticks.size() - 1; t++) {
            assertTrue(ticks.get(t).speed() > 0, "the cart stood before the end at tick " + t);
            if (path.railAt(ticks.get(t).along()) == 30) {
                fastestOnIt = Math.max(fastestOnIt, ticks.get(t).speed());
            }
        }
        assertEquals(MOTION.junction(), fastestOnIt, 1e-9, "at junction speed on it");
    }

    @Test
    void aJunctionTheCartStartsOnOrNextToIsSwitchedBeforeItSetsOff() {
        final List<RailPos> rails = straight(30);
        world.put(rails.get(0), RailShape.NORTH_SOUTH);
        world.put(rails.get(1), RailShape.NORTH_SOUTH);
        final Map<Integer, RailShape> switches = new LinkedHashMap<>();
        switches.put(0, RailShape.EAST_WEST);
        switches.put(1, RailShape.EAST_WEST);

        final List<Tick> ticks = ride(path(rails, rails.get(0), rails.get(1)), switches, () -> {});

        assertEquals(
                List.of(rails.get(0), rails.get(1)),
                switched.stream().map(Switch::rail).toList());
        assertTrue(switched.stream().allMatch(made -> made.tick() == 0 && made.along() == 0), "at once, in place");
        assertEquals(0, ticks.get(JunctionStops.PAUSE - 1).speed(), "the cart waits its moment");
        assertTrue(ticks.get(JunctionStops.PAUSE + 1).speed() > 0, "then it sets off");
    }

    @Test
    void twoWrongJunctionsCloseTogetherAreSwitchedAtOneStop() {
        final List<RailPos> rails = straight(60);
        world.put(rails.get(30), RailShape.NORTH_SOUTH);
        world.put(rails.get(34), RailShape.NORTH_SOUTH);
        final Map<Integer, RailShape> switches = new LinkedHashMap<>();
        switches.put(30, RailShape.EAST_WEST);
        switches.put(34, RailShape.EAST_WEST);
        final RidePath path = path(rails, rails.get(30), rails.get(34));

        final List<Tick> ticks = ride(path, switches, () -> {});

        assertEquals(2, switched.size());
        assertEquals(switched.get(0).tick(), switched.get(1).tick(), "both at the same stop");
        int stops = 0;
        for (int t = 0; t < ticks.size() - 1; t++) {
            if (ticks.get(t).speed() == 0 && (t == 0 || ticks.get(t - 1).speed() > 0)) {
                stops++;
            }
        }
        assertEquals(1, stops, "one stop on the way");
    }

    @Test
    void aJunctionSwitchedBackWhileTheCartWaitsIsSwitchedAgainAndNeverUnderAMovingCart() {
        final List<RailPos> rails = straight(60);
        final RailPos junction = rails.get(30);
        final RidePath path = path(rails, junction);
        world.put(junction, RailShape.NORTH_SOUTH);
        final boolean[] flipped = {false};

        final List<Tick> ticks = ride(path, Map.of(30, RailShape.EAST_WEST), () -> {
            if (!flipped[0]
                    && !switched.isEmpty()
                    && tick == switched.getFirst().tick() + 3) {
                world.put(junction, RailShape.NORTH_SOUTH);
                flipped[0] = true;
            }
        });

        assertEquals(2, switched.size(), "switched again after the player's flip");
        assertTrue(switched.stream().allMatch(made -> made.speed() == 0), "only by a standing cart");
        assertEquals(RailShape.EAST_WEST, world.get(junction));
        assertEquals(path.length(), ticks.getLast().along(), 1e-6);
    }

    @Test
    void aJunctionSwitchedBackJustAheadOfAMovingCartStopsItBeforeTheJunction() {
        final List<RailPos> rails = straight(60);
        final RailPos junction = rails.get(30);
        final RidePath path = path(rails, junction);
        world.put(junction, RailShape.EAST_WEST);
        final double late = path.start(30) - 1;

        final List<Tick> ticks = ride(path, Map.of(30, RailShape.EAST_WEST), () -> {
            if (switched.isEmpty() && speed > 0 && along >= late && world.get(junction) == RailShape.EAST_WEST) {
                world.put(junction, RailShape.NORTH_SOUTH);
            }
        });

        assertEquals(1, switched.size());
        assertEquals(0, switched.getFirst().speed(), "switched only once the cart stood");
        assertTrue(switched.getFirst().along() < path.start(30), "before the junction");
        assertEquals(path.length(), ticks.getLast().along(), 1e-6);
    }
}
