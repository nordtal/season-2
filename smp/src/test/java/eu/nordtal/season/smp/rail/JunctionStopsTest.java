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

    /** The switch distance the settings hold by default. */
    private static final double BEFORE = new RailsSpec() {}.junctionSwitch();

    /** The crawl speed the settings hold by default, in blocks per tick. */
    private static final double CRAWL = new RailsSpec() {}.crawlSpeed() / RidePath.TICKS_PER_SECOND;

    /** The switch distance and crawl speed the settings hold now, read on every tick as {@code Rides} does. */
    private double before = BEFORE;

    private double crawl = CRAWL;
    private RidePath.Motion motion = MOTION;

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
            final JunctionStops.Limit limit =
                    stops.limit(path, switches, along, speed, path.length(), before, crawl, motion, this);
            final double at = Math.max(along, Math.min(path.length(), limit.at()));
            speed = Math.min(path.next(along, speed, at, motion, limit.speed()), at - along);
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
    void aWrongJunctionSwitchesAtItsDistanceWhileTheCartCrawlsAndTheRideSpeedsUpAtOnce() {
        final List<RailPos> rails = straight(60);
        final RailPos junction = rails.get(30);
        final RidePath path = path(rails);
        world.put(junction, RailShape.NORTH_SOUTH);

        final List<Tick> ticks = ride(path, Map.of(30, RailShape.EAST_WEST), () -> {});

        assertEquals(1, switched.size(), "switched once");
        final Switch made = switched.getFirst();
        assertEquals(CRAWL, made.speed(), MOTION.acceleration(), "switched by a crawling cart");
        assertEquals(path.start(30) - BEFORE, made.along(), CRAWL, "within one tick of the switch distance");
        assertEquals(RailShape.EAST_WEST, world.get(junction));
        assertTrue(ticks.get(made.tick() + 1).speed() > made.speed(), "the cart speeds up on the next tick");
        for (int t = 0; t < ticks.size() - 1; t++) {
            assertTrue(ticks.get(t).speed() > 0, "the cart never stops on the way, at tick " + t);
        }
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
    void aCartBrakesForAWrongJunctionAtItsAccelerationAndReachesTheSwitchPointAtCrawlSpeed() {
        final List<RailPos> rails = straight(240);
        final RailPos junction = rails.get(120);
        final RidePath path = path(rails);
        world.put(junction, RailShape.NORTH_SOUTH);

        final List<Tick> ticks = ride(path, Map.of(120, RailShape.EAST_WEST), () -> {});

        assertGentle(ticks);
        final int made = switched.getFirst().tick();
        assertEquals(path.start(120) - BEFORE, ticks.get(made - 1).along(), CRAWL, "at the switch point");
        assertEquals(CRAWL, ticks.get(made - 1).speed(), MOTION.acceleration(), "at crawl speed");
        assertTrue(
                ticks.stream().mapToDouble(Tick::speed).max().orElseThrow() >= MOTION.top() - 1e-9,
                "after a ride at top speed");
    }

    @Test
    void aLongerSwitchDistanceOnlyMovesTheSwitchAndTheCartStillBrakesAtItsAcceleration() {
        final List<RailPos> rails = straight(240);
        final RailPos junction = rails.get(120);
        final RidePath path = path(rails);
        world.put(junction, RailShape.NORTH_SOUTH);
        before = 9;

        final List<Tick> ticks = ride(path, Map.of(120, RailShape.EAST_WEST), () -> {});

        assertGentle(ticks);
        final Switch made = switched.getFirst();
        assertEquals(
                path.start(120) - 9,
                made.along(),
                CRAWL,
                "within one tick of the switch point nine blocks before the junction");
        assertEquals(CRAWL, made.speed(), MOTION.acceleration(), "still at crawl speed");
        assertTrue(
                ticks.stream().mapToDouble(Tick::speed).max().orElseThrow() >= MOTION.top() - 1e-9,
                "after a ride at top speed");
        assertEquals(path.length(), ticks.getLast().along(), 1e-6, "and arrives");
    }

    @Test
    void aSwitchDistanceChangedDuringTheRideMovesTheSwitchOfThatRide() {
        final List<RailPos> rails = straight(240);
        final RidePath path = path(rails);
        world.put(rails.get(120), RailShape.NORTH_SOUTH);

        final List<Tick> ticks = ride(path, Map.of(120, RailShape.EAST_WEST), () -> {
            if (tick == 20) {
                before = 9;
            }
        });

        assertGentle(ticks);
        assertEquals(
                path.start(120) - 9,
                switched.getFirst().along(),
                CRAWL,
                "within one tick of the switch point nine blocks before the junction");
    }

    @Test
    void aJunctionSetWrongAheadOfAMovingCartIsBrakedForGentlyOrSwitchedLate() {
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
            assertTrue(
                    switched.stream().allMatch(made -> made.along() < path.start(80)),
                    "switched before the cart rode onto it, " + ahead);
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
        for (int index = 0; index <= 1; index++) {
            final List<RailPos> rails = straight(30);
            world.put(rails.get(index), RailShape.NORTH_SOUTH);

            final List<Tick> ticks = ride(path(rails), Map.of(index, RailShape.EAST_WEST), () -> {});

            assertEquals(1, switched.size());
            assertEquals(0, switched.getFirst().tick(), "switched before the first move");
            assertEquals(0, switched.getFirst().along());
            assertTrue(ticks.getFirst().speed() > 0, "the cart sets off on the switch tick");
            assertTrue(ticks.get(1).speed() > ticks.getFirst().speed(), "without a standing pause");
            switched.clear();
            claims.clear();
        }
    }

    @Test
    void twoWrongJunctionsCloseTogetherAreSwitchedOneAfterTheOtherWithoutAStop() {
        final List<RailPos> rails = straight(60);
        world.put(rails.get(30), RailShape.NORTH_SOUTH);
        world.put(rails.get(34), RailShape.NORTH_SOUTH);
        final Map<Integer, RailShape> switches = new LinkedHashMap<>();
        switches.put(30, RailShape.EAST_WEST);
        switches.put(34, RailShape.EAST_WEST);
        final RidePath path = path(rails);

        final List<Tick> ticks = ride(path, switches, () -> {});

        assertEquals(2, switched.size());
        assertTrue(switched.get(1).tick() > switched.get(0).tick(), "each switches at its own moment");
        assertEquals(path.start(30) - BEFORE, switched.get(0).along(), CRAWL);
        assertEquals(path.start(34) - BEFORE, switched.get(1).along(), CRAWL);
        for (int t = 0; t < ticks.size() - 1; t++) {
            assertTrue(ticks.get(t).speed() > 0, "the cart never stops on the way, at tick " + t);
        }
    }

    @Test
    void twoAdjacentJunctionsSwitchAtLeastHalfASecondApartAndBeforeTheCartReachesThem() {
        final List<RailPos> rails = straight(60);
        world.put(rails.get(30), RailShape.NORTH_SOUTH);
        world.put(rails.get(31), RailShape.NORTH_SOUTH);
        final Map<Integer, RailShape> switches = new LinkedHashMap<>();
        switches.put(30, RailShape.EAST_WEST);
        switches.put(31, RailShape.EAST_WEST);
        final RidePath path = path(rails);

        ride(path, switches, () -> {});

        assertEquals(2, switched.size());
        assertTrue(
                switched.get(1).tick() - switched.get(0).tick() >= JunctionStops.SWITCH_INTERVAL,
                "half a second between the switches");
        assertTrue(switched.get(1).along() < path.start(31), "the second switches before the cart reaches it");
    }

    @Test
    void aJunctionSwitchedBackAheadIsSwitchedAgainAfterTheIntervalAndNeverUnderTheCart() {
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
        assertTrue(
                switched.get(1).tick() - switched.get(0).tick() >= JunctionStops.SWITCH_INTERVAL,
                "not before the switch interval passed");
        assertTrue(switched.stream().allMatch(made -> made.along() < path.start(30)), "never under the cart");
        assertEquals(RailShape.EAST_WEST, world.get(junction));
        assertEquals(path.length(), ticks.getLast().along(), 1e-6);
    }

    @Test
    void aJunctionSetWrongTooLateToCrawlForIsSwitchedBeforeTheCartReachesIt() {
        final List<RailPos> rails = straight(60);
        final RailPos junction = rails.get(30);
        final RidePath path = path(rails);
        world.put(junction, RailShape.EAST_WEST);
        final double late = path.start(30) - 1;

        ride(path, Map.of(30, RailShape.EAST_WEST), () -> {
            if (cart.speed > 0 && cart.along >= late && cart.along < path.start(30)) {
                world.put(junction, RailShape.NORTH_SOUTH);
            }
        });

        assertEquals(1, switched.size(), "the late junction still switches");
        assertTrue(switched.getFirst().speed() > CRAWL, "without braking harder to the crawl");
        assertTrue(switched.getFirst().along() < path.start(30), "before the cart reaches it");
    }

    @Test
    void aJunctionSetWrongUnderAMovingCartIsRiddenOverAsItLies() {
        final List<RailPos> rails = straight(60);
        final RailPos junction = rails.get(30);
        final RidePath path = path(rails);
        world.put(junction, RailShape.EAST_WEST);

        final List<Tick> ticks = ride(path, Map.of(30, RailShape.EAST_WEST), () -> {
            if (cart.along >= path.start(30)) {
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
    void aTopSpeedBelowTheCrawlSpeedCapsTheCrawl() {
        final List<RailPos> rails = straight(60);
        final RailPos junction = rails.get(30);
        final RidePath path = path(rails);
        world.put(junction, RailShape.NORTH_SOUTH);
        motion = RidePath.Motion.of(6, 2);
        crawl = motion.top();

        ride(path, Map.of(30, RailShape.EAST_WEST), () -> {});

        assertEquals(motion.top(), switched.getFirst().speed(), 1e-9, "the crawl never exceeds the top speed");
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
    void aCartOnAnotherLegWaitsTwoBlocksBeforeItsSwitchUntilAPassingCartHasRiddenOverIt() {
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
        final List<Double> waitingAt = new ArrayList<>();
        second.along = waiting.start(8) - BEFORE;

        boolean firstIn = false;
        boolean secondIn = false;
        for (tick = 0; tick < 2_000 && !(firstIn && secondIn); tick++) {
            firstIn = firstIn || first.tick(passing, straightOn);
            final int before = switched.size();
            if (first.along >= passing.start(30) - 3) {
                secondIn = secondIn || second.tick(waiting, turned);
                if (second.speed == 0 && second.along < waiting.start(8)) {
                    waitingAt.add(second.along);
                }
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
        assertTrue(
                waitingAt.stream().allMatch(at -> Math.abs(at - (waiting.start(8) - 2)) < 1e-9),
                "the waiting cart stands two blocks before the junction");
    }
}
