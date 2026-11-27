package eu.nordtal.season.packbuilder.wheel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.packbuilder.wheel.WheelTurn.Ending;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class WheelTurnTest {

    private final List<Integer> variants = WheelSpin.offsets(WheelLayout.PLACES);

    @Test
    void everyVariantAndEndingSharesTheStartAndRestsOnItsWinner() {
        final List<WheelTurn.Tick> first =
                WheelTurn.ticks(variants.getFirst(), Ending.DIRECT).subList(0, WheelTurn.SHARED);
        for (final int e : variants) {
            for (final Ending ending : Ending.values()) {
                final List<WheelTurn.Tick> ticks = WheelTurn.ticks(e, ending);
                assertEquals(first, ticks.subList(0, WheelTurn.SHARED), "variant " + e + " " + ending);
                assertEquals(WheelTurn.length(e, ending), ticks.size());
                assertTrue(ticks.size() <= WheelTurn.COOLDOWN);
                assertEquals(
                        WheelTurn.discFrame(WheelTurn.distance(e)),
                        WheelTurn.discFrame(ticks.getLast().angle()),
                        "variant " + e + " " + ending);
                assertEquals(WheelTurn.Pointer.REST, ticks.getLast().pointer());
            }
        }
    }

    @Test
    void theWheelSpeedsUpHoldsItsTopSpeedThenOnlySlowsAndComesToRestWithinFiveToTenSeconds() {
        final int slows = WheelTurn.ACCELERATION + WheelTurn.CRUISE;
        for (final int e : variants) {
            for (final Ending ending : Ending.values()) {
                for (int t = 1; t < WheelTurn.ACCELERATION; t++) {
                    assertTrue(
                            WheelTurn.speed(e, ending, t) > WheelTurn.speed(e, ending, t - 1),
                            "variant " + e + " " + ending + " lags at tick " + t);
                }
                for (int t = WheelTurn.ACCELERATION; t < slows; t++) {
                    assertEquals(
                            WheelTurn.TOP_SPEED,
                            WheelTurn.speed(e, ending, t),
                            1e-3,
                            "variant " + e + " " + ending + " cruises at tick " + t);
                }
                final double target = WheelTurn.distance(e) + (ending == Ending.REBOUND ? WheelTurn.PIN : 0.0);
                final double dips = target - 120.0;
                final List<Double> speeds = new ArrayList<>();
                int t = slows;
                for (; WheelTurn.angle(e, ending, t + 0.5) < dips; t++) {
                    speeds.add(WheelTurn.speed(e, ending, t));
                }
                for (int i = 1; i < speeds.size(); i++) {
                    assertTrue(
                            speeds.get(i) <= speeds.get(i - 1) + 1e-9,
                            "variant " + e + " " + ending + " speeds up at tick " + i);
                }
                while (WheelTurn.angle(e, ending, t) < target - 1e-6) {
                    t++;
                }
                final int slowing = t - slows;
                assertTrue(
                        slowing >= 95 && slowing <= 200,
                        "variant " + e + " " + ending + " slows for " + slowing + " ticks");
            }
        }
    }

    @Test
    void theSharpWheelStepsAtMostHalfAFieldATickSoItNeverSeemsToRunBack() {
        for (final int e : variants) {
            for (final Ending ending : Ending.values()) {
                final List<WheelTurn.Tick> ticks = WheelTurn.ticks(e, ending);
                for (int t = 0; t < ticks.size(); t++) {
                    assertTrue(
                            WheelTurn.speed(e, ending, t) <= WheelTurn.TOP_SPEED + 1e-9,
                            "variant " + e + " " + ending + " tick " + t);
                    if (t > 0) {
                        final double step =
                                ticks.get(t).angle() - ticks.get(t - 1).angle();
                        assertTrue(
                                Math.abs(step) <= 15.0 + 1e-9,
                                "variant " + e + " " + ending + " steps " + step + " at tick " + t);
                    }
                }
            }
        }
    }

    @Test
    void theSlowingNeverJumpsWhereTheVariantsPart() {
        for (final int e : variants) {
            for (final Ending ending : Ending.values()) {
                final List<Double> slowing = new ArrayList<>();
                for (int t = WheelTurn.SHARED - 5; t < WheelTurn.SHARED + 40; t++) {
                    slowing.add(WheelTurn.speed(e, ending, t - 0.5) - WheelTurn.speed(e, ending, t + 0.5));
                }
                double around = 0.0;
                for (int i = 1; i < 5; i++) {
                    around = Math.max(around, Math.abs(slowing.get(i) - slowing.get(i - 1)));
                }
                for (int i = 1; i < slowing.size(); i++) {
                    assertTrue(
                            Math.abs(slowing.get(i) - slowing.get(i - 1)) <= 2 * around + 0.002,
                            "variant " + e + " " + ending + " slowing jumps at tick " + (WheelTurn.SHARED - 5 + i));
                }
            }
        }
    }

    @Test
    void theDirectEndingHasNoBackwardMotionAndStopsDirectlyOnTheWinner() {
        for (final int e : variants) {
            final List<WheelTurn.Tick> ticks = WheelTurn.ticks(e, Ending.DIRECT);
            final double target = WheelTurn.distance(e);
            for (int i = 0; i < ticks.size(); i++) {
                assertTrue(ticks.get(i).angle() <= target + 1e-9, "variant " + e + " overshoots at tick " + i);
                if (i > 0) {
                    assertTrue(
                            ticks.get(i).angle() >= ticks.get(i - 1).angle() - 1e-9,
                            "variant " + e + " moves backward at tick " + i);
                }
            }
            assertEquals(target, ticks.getLast().angle(), 1e-9, "variant " + e + " ends on winner");
            assertTrue(
                    WheelTurn.length(e, Ending.DIRECT) < WheelTurn.length(e, Ending.REBOUND),
                    "direct ending is shorter than rebound for variant " + e);
        }
    }

    @Test
    void theReboundEndingStopsAgainstTheFarPinAndRocksBackWithoutCrossingIt() {
        for (final int e : variants) {
            final List<WheelTurn.Tick> ticks = WheelTurn.ticks(e, Ending.REBOUND);
            final double pin = WheelTurn.distance(e) + WheelTurn.PIN;
            assertTrue(ticks.stream().allMatch(t -> t.angle() <= pin + 1e-9), "variant " + e + " passes the pin");
            int top = 0;
            while (ticks.get(top).angle() < pin - 1e-9) {
                top++;
            }
            assertEquals(WheelTurn.Pointer.TICK1, ticks.get(top).pointer(), "variant " + e + " bends on the pin");
            for (int i = top + 1; i < ticks.size(); i++) {
                assertTrue(ticks.get(i).angle() <= ticks.get(i - 1).angle() + 1e-9, "variant " + e + " rocks forward");
            }
            assertEquals(WheelTurn.Pointer.REST, ticks.getLast().pointer());
        }
    }

    @Test
    void ordinaryPegPassesClickAndFarPinContactOnReboundIsSuppressed() {
        for (final int e : variants) {
            final int expectedPegs = WheelTurn.TURNS * WheelLayout.PLACES + e;
            final List<Integer> directClicks = WheelTurn.clicks(e, Ending.DIRECT);
            final List<Integer> reboundClicks = WheelTurn.clicks(e, Ending.REBOUND);
            assertEquals(List.copyOf(new TreeSet<>(directClicks)), directClicks);
            assertEquals(List.copyOf(new TreeSet<>(reboundClicks)), reboundClicks);
            assertEquals(expectedPegs, directClicks.size(), "direct peg clicks for variant " + e);
            assertEquals(expectedPegs, reboundClicks.size(), "rebound peg clicks for variant " + e);

            final List<WheelTurn.Tick> reboundTicks = WheelTurn.ticks(e, Ending.REBOUND);
            final double pin = WheelTurn.distance(e) + WheelTurn.PIN;
            int firstPinTick = 0;
            while (reboundTicks.get(firstPinTick).angle() < pin - 1e-9) {
                firstPinTick++;
            }
            assertFalse(
                    reboundClicks.contains(firstPinTick),
                    "variant " + e + " must suppress far-pin contact click at tick " + firstPinTick);
            assertTrue(
                    reboundClicks.getLast() < firstPinTick,
                    "variant " + e + " has no click at or after far-pin contact: last="
                            + reboundClicks.getLast()
                            + ", pinTick="
                            + firstPinTick);
        }
    }

    @Test
    void thePrizesPopInQuickly() {
        assertTrue(WheelTurn.OPEN_TICKS <= 25);
        assertEquals(Optional.empty(), WheelSpin.popIn(11, 0));
        assertEquals(Optional.of(WheelSpin.waiting(11)), WheelSpin.popIn(11, WheelTurn.OPEN_TICKS));
    }

    @Test
    void aTurnedWheelPlacesItsPrizesAsTheSameWheelAtRest() {
        for (int e = 0; e < WheelLayout.PLACES; e++) {
            for (final Ending ending : Ending.values()) {
                for (int b = 0; b < WheelLayout.PLACES; b++) {
                    assertEquals(
                            WheelSpin.waiting((b + e) % WheelLayout.PLACES),
                            new WheelSpin(e, ending).prizeRest(b),
                            "variant " + e + " " + ending + " body " + b);
                }
            }
        }
    }

    @Test
    void variantsSpreadOverThePlaces() {
        assertEquals(List.of(0, 3, 6, 9), WheelSpin.offsets(4));
        assertEquals(List.of(0, 1, 2, 4, 5, 6, 7, 8, 10, 11), WheelSpin.offsets(10));
    }

    @Test
    void aRunShowsFromHalfwayIntoItsLastTickAndTheRestIsTheFallback() {
        final List<WheelSpin.Run<String>> runs = WheelSpin.runs(List.of("a", "a", "b", "r", "r"), "r", 5);
        assertEquals(List.of(new WheelSpin.Run<>(6, "a"), new WheelSpin.Run<>(7, "b")), runs);
        assertEquals((200 - 1.5) / 200, WheelSpin.threshold(1, 200).doubleValue(), 1e-4);
    }
}
