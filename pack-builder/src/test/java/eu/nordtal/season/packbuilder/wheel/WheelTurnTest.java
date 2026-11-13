package eu.nordtal.season.packbuilder.wheel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class WheelTurnTest {

    private final List<Integer> variants = WheelSpin.offsets(WheelLayout.PLACES);

    @Test
    void everyVariantSharesTheStartAndRestsOnItsWinner() {
        final List<WheelTurn.Tick> first = WheelTurn.ticks(variants.getFirst()).subList(0, WheelTurn.SHARED);
        for (final int e : variants) {
            final List<WheelTurn.Tick> ticks = WheelTurn.ticks(e);
            assertEquals(first, ticks.subList(0, WheelTurn.SHARED), "variant " + e);
            assertEquals(WheelTurn.length(e), ticks.size());
            assertTrue(ticks.size() <= WheelTurn.COOLDOWN);
            assertEquals(
                    WheelTurn.discFrame(WheelTurn.distance(e)),
                    WheelTurn.discFrame(ticks.getLast().angle()),
                    "variant " + e);
        }
    }

    @Test
    void theWheelNeverRunsFasterThanThreeFramesATickAndSlowsDownEvenly() {
        for (final int e : variants) {
            final List<WheelTurn.Tick> ticks = WheelTurn.ticks(e);
            for (int i = 1; i < ticks.size(); i++) {
                assertTrue(
                        ticks.get(i).angle() - ticks.get(i - 1).angle() <= WheelTurn.START_SPEED + 1e-9,
                        "variant " + e + " at tick " + i);
            }
            final double dips = WheelTurn.distance(e) + WheelTurn.PIN - 120.0;
            final List<Double> speeds = new ArrayList<>();
            for (int t = 0; t < WheelTurn.length(e) && WheelTurn.angle(e, t + 0.5) < dips; t++) {
                speeds.add(WheelTurn.speed(e, t));
            }
            for (int i = 1; i < speeds.size(); i++) {
                assertTrue(speeds.get(i) <= speeds.get(i - 1) + 1e-9, "variant " + e + " speeds up at tick " + i);
            }
            assertTrue(WheelTurn.length(e) >= 160, "variant " + e + " turns long");
        }
    }

    @Test
    void theSlowingNeverJumpsWhereTheVariantsPart() {
        for (final int e : variants) {
            final List<Double> slowing = new ArrayList<>();
            for (int t = WheelTurn.SHARED - 5; t < WheelTurn.SHARED + 40; t++) {
                slowing.add(WheelTurn.speed(e, t - 0.5) - WheelTurn.speed(e, t + 0.5));
            }
            for (int i = 1; i < slowing.size(); i++) {
                assertTrue(
                        Math.abs(slowing.get(i) - slowing.get(i - 1)) <= 0.002,
                        "variant " + e + " slowing jumps at tick " + (WheelTurn.SHARED - 5 + i));
            }
        }
    }

    @Test
    void thePointerStopsAgainstTheFarPinAndTheWheelRocksBackWithoutCrossingIt() {
        for (final int e : variants) {
            final List<WheelTurn.Tick> ticks = WheelTurn.ticks(e);
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
    void aPegClicksAtMostOnceATickAndTheFarPinOnce() {
        for (final int e : variants) {
            final List<Integer> clicks = WheelTurn.clicks(e);
            assertEquals(List.copyOf(new TreeSet<>(clicks)), clicks);
            assertTrue(clicks.size() <= WheelTurn.TURNS * WheelLayout.PLACES + e + 1, "variant " + e);
            assertTrue(clicks.size() >= WheelTurn.TURNS * WheelLayout.PLACES, "variant " + e);
            assertTrue(
                    clicks.getLast() < WheelTurn.length(e) - WheelTurn.SETTLE,
                    "variant " + e + " clicks while rocking back: " + clicks);
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
            for (int b = 0; b < WheelLayout.PLACES; b++) {
                assertEquals(
                        WheelSpin.waiting((b + e) % WheelLayout.PLACES),
                        new WheelSpin(e).prizeRest(b),
                        "variant " + e + " body " + b);
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
