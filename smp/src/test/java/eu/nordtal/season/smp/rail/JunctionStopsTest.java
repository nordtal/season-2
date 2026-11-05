package eu.nordtal.season.smp.rail;

import static eu.nordtal.season.smp.rail.Track.at;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.smp.config.RailsSpec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JunctionStopsTest {

    private static final RidePath.Motion MOTION = RidePath.Motion.of(6, 24);

    /** The stop distance the settings hold by default. */
    private static final double BEFORE = new RailsSpec() {}.junctionStop();

    /** The stop distance the settings hold now, read on every tick as {@code Rides} does. */
    private double before = BEFORE;

    /** The shape each junction has in the world now. */
    private final Map<RailPos, RailShape> world = new HashMap<>();

    /** Every switch made: the junction, the tick, where the cart stood and how fast it went. */
    private final List<Switch> switched = new ArrayList<>();

    private record Switch(RailPos rail, int tick, double along, double speed) {}

    /** What one tick ended with. */
    private record Tick(double along, double speed) {}

    /** Which cart holds each junction. */
    private final Map<RailPos, Cart> claims = new HashMap<>();

    private int tick;

    /** One driven cart, with the junctions as it reads and holds them. */
    private final class Cart implements JunctionStops.Junctions {
        private final JunctionStops stops = new JunctionStops();
        private double along;
        private double speed;

        @Override
        public boolean wrong(final RailPos rail, final RailShape wanted) {
            return world.get(rail) != wanted;
        }

        @Override
        public boolean claim(final RailPos rail) {
            return claims.putIfAbsent(rail, this) == null || claims.get(rail) == this;
        }

        @Override
        public void release(final RailPos rail) {
            claims.remove(rail, this);
        }

        @Override
        public void set(final RailPos rail, final RailShape wanted) {
            world.put(rail, wanted);
            switched.add(new Switch(rail, tick, along, speed));
        }

        /** Rides one tick as {@code Rides} does, and returns whether the cart stands at the end of {@code path}. */
        boolean tick(final RidePath path, final Map<Integer, RailShape> switches) {
            final double stop = stops.limit(path, switches, along, speed, path.length(), before, MOTION, this);
            final double limit = Math.max(along, Math.min(path.length(), stop));
            speed = Math.min(path.next(along, speed, limit, MOTION), limit - along);
            along += speed;
            return speed == 0 && along >= path.length();
        }
    }

    private Cart cart = new Cart();

    private static List<RailPos> straight(final int length) {
        final List<RailPos> rails = new ArrayList<>();
        for (int x = 0; x <= length; x++) {
            rails.add(at(x, 64, 0));
        }
        return rails;
    }

    private static RidePath path(final List<RailPos> rails) {
        final RailPos first = rails.getFirst();
        return RidePath.of(rails, new RidePath.Point(first.x() + 0.5, first.y(), first.z() + 0.5));
    }

    /**
     * Rides {@code path} to its end as {@code Rides} does, and returns every tick.
     * {@code during} runs before each tick, so a test can switch a junction back as a player would.
     */
    private List<Tick> ride(final RidePath path, final Map<Integer, RailShape> switches, final Runnable during) {
        cart = new Cart();
        final List<Tick> ticks = new ArrayList<>();
        for (tick = 0; tick < 10_000; tick++) {
            during.run();
            final boolean arrived = cart.tick(path, switches);
            ticks.add(new Tick(cart.along, cart.speed));
            if (arrived) {
                return ticks;
            }
        }
        throw new AssertionError("the cart never arrived");
    }

    @Test
    void aJunctionSetTheWrongWayStopsTheCartTwoBlocksBeforeItIsSwitchedAndTheRideGoesOn() {
        final List<RailPos> rails = straight(60);
        final RailPos junction = rails.get(30);
        final RidePath path = path(rails);
        world.put(junction, RailShape.NORTH_SOUTH);

        final List<Tick> ticks = ride(path, Map.of(30, RailShape.EAST_WEST), () -> {});

        assertEquals(1, switched.size(), "switched once");
        final Switch made = switched.getFirst();
        assertEquals(0, made.speed(), "switched by a standing cart");
        assertEquals(path.start(30) - BEFORE, made.along(), 1e-6, "standing two blocks before it");
        assertEquals(RailShape.EAST_WEST, world.get(junction));
        for (int t = made.tick(); t < made.tick() + JunctionStops.PAUSE; t++) {
            assertEquals(0, ticks.get(t).speed(), "the cart stands a moment after the switch, at tick " + t);
        }
        assertTrue(ticks.get(made.tick() + JunctionStops.PAUSE + 1).speed() > 0, "then it sets off");
        assertEquals(path.length(), ticks.getLast().along(), 1e-6, "and arrives");
    }

    @Test
    void aJunctionSetRightIsPassedAtTopSpeedWithoutAStop() {
        final List<RailPos> rails = straight(240);
        final RailPos junction = rails.get(120);
        final RidePath path = path(rails);
        world.put(junction, RailShape.EAST_WEST);

        final List<Tick> ticks = ride(path, Map.of(120, RailShape.EAST_WEST), () -> {});

        assertTrue(switched.isEmpty(), "nothing is switched");
        for (int t = 0; t < ticks.size() - 1; t++) {
            assertTrue(ticks.get(t).speed() > 0, "the cart stood before the end at tick " + t);
        }
        final Tick onto = ticks.stream()
                .filter(t -> t.along() >= path.start(120))
                .findFirst()
                .orElseThrow();
        assertEquals(MOTION.top(), onto.speed(), 1e-9, "onto it at top speed");
    }

    @Test
    void aCartBrakesForAWrongJunctionAtItsAccelerationAndStandsStillExactlyAtItsStop() {
        final List<RailPos> rails = straight(240);
        final RailPos junction = rails.get(120);
        final RidePath path = path(rails);
        world.put(junction, RailShape.NORTH_SOUTH);

        final List<Tick> ticks = ride(path, Map.of(120, RailShape.EAST_WEST), () -> {});

        assertGentle(ticks);
        final int standing = switched.getFirst().tick();
        final double stop = path.start(120) - BEFORE;
        assertEquals(stop, ticks.get(standing - 1).along(), 1e-9, "standing at the stop before the switch");
        assertEquals(0, ticks.get(standing - 1).speed(), "with no speed left");
        assertTrue(ticks.get(standing - 2).speed() > 0, "and only from the tick it got there");
        assertTrue(
                ticks.stream().mapToDouble(Tick::speed).max().orElseThrow() >= MOTION.top() - 1e-9,
                "after a ride at top speed");
    }

    @Test
    void aLongerStopDistanceOnlyMovesTheStopAndTheCartStillBrakesAtItsAcceleration() {
        final List<RailPos> rails = straight(240);
        final RailPos junction = rails.get(120);
        final RidePath path = path(rails);
        world.put(junction, RailShape.NORTH_SOUTH);
        before = 9;

        final List<Tick> ticks = ride(path, Map.of(120, RailShape.EAST_WEST), () -> {});

        assertGentle(ticks);
        final Switch made = switched.getFirst();
        assertEquals(0, made.speed(), "switched by a standing cart");
        assertEquals(path.start(120) - 9, made.along(), 1e-9, "standing nine blocks before it");
        assertEquals(path.start(120) - 9, ticks.get(made.tick() - 1).along(), 1e-9, "from the tick before");
        assertTrue(ticks.get(made.tick() - 2).speed() > 0, "and only from the tick it got there");
        assertTrue(
                ticks.stream().mapToDouble(Tick::speed).max().orElseThrow() >= MOTION.top() - 1e-9,
                "after a ride at top speed");
        assertEquals(path.length(), ticks.getLast().along(), 1e-6, "and arrives");
    }

    @Test
    void aStopDistanceChangedDuringTheRideTakesEffectOnTheNextTick() {
        final List<RailPos> rails = straight(240);
        final RidePath path = path(rails);
        world.put(rails.get(120), RailShape.NORTH_SOUTH);

        final List<Tick> ticks = ride(path, Map.of(120, RailShape.EAST_WEST), () -> {
            if (tick == 20) {
                before = 0;
            }
        });

        assertGentle(ticks);
        assertEquals(path.start(120), switched.getFirst().along(), 1e-9, "standing right before the junction");
    }

    @Test
    void aJunctionSetWrongAheadOfAMovingCartIsBrakedForGentlyOrRiddenOver() {
        final List<RailPos> rails = straight(120);
        final RailPos junction = rails.get(80);
        final RidePath path = path(rails);
        for (double ahead = 0; ahead <= 60; ahead += 0.25) {
            final double at = path.start(80) - ahead;
            world.put(junction, RailShape.EAST_WEST);
            switched.clear();
            claims.clear();

            final List<Tick> ticks = ride(path, Map.of(80, RailShape.EAST_WEST), () -> {
                if (switched.isEmpty() && cart.along >= at) {
                    world.put(junction, RailShape.NORTH_SOUTH);
                }
            });

            assertGentle(ticks);
            assertTrue(switched.stream().allMatch(made -> made.speed() == 0), "switched only standing, " + ahead);
        }
    }

    /** Asserts that the speed never changes by more than one tick's acceleration, from standing to standing. */
    private static void assertGentle(final List<Tick> ticks) {
        double before = 0;
        for (int t = 0; t < ticks.size(); t++) {
            final double change = ticks.get(t).speed() - before;
            assertTrue(
                    Math.abs(change) <= MOTION.acceleration() + 1e-9,
                    "the speed changed by " + change + " at tick " + t + ", along "
                            + ticks.get(t).along());
            before = ticks.get(t).speed();
        }
    }

    @Test
    void aJunctionTheCartStartsOnOrNextToIsSwitchedBeforeItSetsOff() {
        final List<RailPos> rails = straight(30);
        world.put(rails.get(0), RailShape.NORTH_SOUTH);
        world.put(rails.get(1), RailShape.NORTH_SOUTH);
        final Map<Integer, RailShape> switches = new LinkedHashMap<>();
        switches.put(0, RailShape.EAST_WEST);
        switches.put(1, RailShape.EAST_WEST);

        final List<Tick> ticks = ride(path(rails), switches, () -> {});

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
        final RidePath path = path(rails);

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
        final RidePath path = path(rails);
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
    void aJunctionSwitchedBackTooLateToBrakeForIsRiddenOverAndNeverSwitchedUnderTheCart() {
        final List<RailPos> rails = straight(60);
        final RailPos junction = rails.get(30);
        final RidePath path = path(rails);
        world.put(junction, RailShape.EAST_WEST);
        final double late = path.start(30) - 1;

        final List<Tick> ticks = ride(path, Map.of(30, RailShape.EAST_WEST), () -> {
            if (cart.speed > 0 && cart.along >= late && cart.along < path.start(30)) {
                world.put(junction, RailShape.NORTH_SOUTH);
            }
        });

        assertTrue(switched.isEmpty(), "nothing is switched under a moving cart");
        for (int t = 0; t < ticks.size() - 1; t++) {
            assertTrue(ticks.get(t).speed() > 0, "the cart halted at tick " + t);
        }
        assertEquals(path.length(), ticks.getLast().along(), 1e-6);
    }

    @Test
    void twoCartsWantingAJunctionSetApartTakeTurnsInsteadOfSwitchingItBackAndForth() {
        final List<RailPos> rails = straight(60);
        final RailPos junction = rails.get(30);
        final RidePath path = path(rails);
        final Cart first = new Cart();
        final Cart second = new Cart();
        final Map<Integer, RailShape> east = Map.of(30, RailShape.EAST_WEST);
        final Map<Integer, RailShape> north = Map.of(30, RailShape.NORTH_SOUTH);
        world.put(junction, RailShape.SOUTH_EAST);

        boolean firstIn = false;
        boolean secondIn = false;
        for (tick = 0; tick < 2_000 && !(firstIn && secondIn); tick++) {
            firstIn = firstIn || first.tick(path, east);
            secondIn = secondIn || second.tick(path, north);
        }

        assertTrue(firstIn && secondIn, "both carts arrive");
        assertEquals(2, switched.size(), "each switched it once: " + switched);
        assertTrue(claims.isEmpty(), "and let go of it");
    }

    @Test
    void aCartOnAnotherLegWaitsWithItsSwitchUntilAPassingCartHasRiddenOverTheJunction() {
        final List<RailPos> east = straight(60);
        final RailPos junction = east.get(30);
        final List<RailPos> north = new ArrayList<>();
        for (int z = -8; z <= 20; z++) {
            north.add(at(30, 64, z));
        }
        final RidePath passing = path(east);
        final RidePath waiting = path(north);
        world.put(junction, RailShape.EAST_WEST);
        final Cart first = new Cart();
        final Cart second = new Cart();
        final Map<Integer, RailShape> straightOn = Map.of(30, RailShape.EAST_WEST);
        final Map<Integer, RailShape> turned = Map.of(8, RailShape.NORTH_SOUTH);
        final List<Double> passingAtSwitch = new ArrayList<>();
        second.along = waiting.start(8) - BEFORE;

        boolean firstIn = false;
        boolean secondIn = false;
        for (tick = 0; tick < 2_000 && !(firstIn && secondIn); tick++) {
            firstIn = firstIn || first.tick(passing, straightOn);
            final int before = switched.size();
            if (first.along >= passing.start(30) - 3) {
                secondIn = secondIn || second.tick(waiting, turned);
            }
            if (switched.size() > before) {
                passingAtSwitch.add(first.along);
            }
        }

        assertTrue(firstIn && secondIn, "both carts arrive");
        assertEquals(1, passingAtSwitch.size(), "switched once: " + switched);
        assertTrue(
                passingAtSwitch.getFirst() > passing.start(31),
                "only once the passing cart is over it, not at " + passingAtSwitch.getFirst());
    }
}
