package eu.nordtal.season.smp.wheel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The strip ends on the prize already won, for every pool size and every winner.
 *
 * Each case runs against {@link WheelPanel#shape()} and against the old shape of nine and four cells.
 */
class WheelStripTest {

    private static final List<WheelStrip.Shape> SHAPES = List.of(WheelPanel.shape(), new WheelStrip.Shape(9, 4));

    private static List<Integer> even(final int poolSize) {
        return Collections.nCopies(poolSize, 1);
    }

    @Test
    void itAlwaysLandsOnTheWinner() {
        for (final WheelStrip.Shape shape : SHAPES) {
            for (int poolSize = 1; poolSize <= 12; poolSize++) {
                for (int winner = 0; winner < poolSize; winner++) {
                    final WheelStrip strip = WheelStrip.landingOn(even(poolSize), winner, new Random(winner), shape);
                    final int[] last = strip.cells(WheelStrip.steps() - 1);
                    assertEquals(shape.count(), last.length, "a frame is the whole surface");
                    assertEquals(
                            winner,
                            last[shape.centre()],
                            shape + ", pool " + poolSize + ", winner " + winner + ": the resting"
                                    + " cell has to hold the prize the database already gave away");
                    assertEquals(winner, strip.winner());
                    assertEquals(shape, strip.shape());
                }
            }
        }
    }

    @Test
    void itTurnsClockwiseOverOneRing() {
        for (final WheelStrip.Shape shape : SHAPES) {
            final WheelStrip strip = WheelStrip.landingOn(even(20), 2, new Random(7), shape);
            for (int step = 0; step + 1 < WheelStrip.steps(); step++) {
                final int[] here = strip.cells(step);
                final int[] next = strip.cells(step + 1);
                for (int cell = 0; cell < shape.count(); cell++) {
                    assertEquals(
                            here[cell],
                            next[(cell + 1) % shape.count()],
                            "every step moves each prize one cell to the next index");
                }
            }
        }
    }

    @Test
    void oneSpinDrawsTwelveCellsWithTheWinnerOnIndexZero() {
        final WheelStrip.Shape shape = WheelPanel.shape();
        assertEquals(12, shape.count());
        for (int poolSize = 1; poolSize <= 30; poolSize++) {
            final WheelStrip strip = WheelStrip.landingOn(even(poolSize), poolSize - 1, new Random(poolSize), shape);
            final int[] last = strip.cells(WheelStrip.steps() - 1);
            assertEquals(12, last.length);
            assertEquals(poolSize - 1, last[0]);
            Arrays.sort(last);
            for (int step = 0; step < WheelStrip.steps(); step++) {
                final int[] cells = strip.cells(step);
                Arrays.sort(cells);
                assertArrayEquals(last, cells, "every frame shows the same ring");
            }
        }
    }

    /** Over many spins the cells beside the winner show each prize as often as its weight lets it be won. */
    @Test
    void aPrizeIsSeenAsOftenAsItCanBeWon() {
        final List<Integer> weights = List.of(1, 3, 6, 0, 10);
        final int total = 20;
        final WheelStrip.Shape shape = WheelPanel.shape();
        final Random random = new Random(769);
        final long[] seen = new long[weights.size()];
        long others = 0;
        for (int spin = 0; spin < 20_000; spin++) {
            final int winner = PrizeDraw.draw(weights, random);
            final int[] last =
                    WheelStrip.landingOn(weights, winner, random, shape).cells(WheelStrip.steps() - 1);
            for (int cell = 0; cell < last.length; cell++) {
                if (cell != shape.centre()) {
                    seen[last[cell]]++;
                    others++;
                }
            }
        }
        for (int prize = 0; prize < weights.size(); prize++) {
            final double share = (double) seen[prize] / others;
            final double chance = (double) weights.get(prize) / total;
            assertEquals(
                    chance,
                    share,
                    0.01,
                    "prize " + prize + " of weight " + weights.get(prize) + " shows in " + share
                            + " of the cells, but is won with a chance of " + chance);
        }
        assertEquals(0, seen[3], "a prize of weight zero is never won, so it never shows");
    }

    @Test
    void aPrizeOfWeightZeroNeverShows() {
        final List<Integer> weights = List.of(0, 1, 0, 1);
        for (final WheelStrip.Shape shape : SHAPES) {
            for (int seed = 0; seed < 200; seed++) {
                final WheelStrip strip = WheelStrip.landingOn(weights, 1 + 2 * (seed % 2), new Random(seed), shape);
                for (int step = 0; step < WheelStrip.steps(); step++) {
                    for (final int cell : strip.cells(step)) {
                        assertNotEquals(0, cell);
                        assertNotEquals(2, cell);
                    }
                }
            }
        }
    }

    @Test
    void aPoolOfOne() {
        for (final WheelStrip.Shape shape : SHAPES) {
            final WheelStrip strip = WheelStrip.landingOn(even(1), 0, new Random(1), shape);
            for (int step = 0; step < WheelStrip.steps(); step++) {
                for (final int cell : strip.cells(step)) {
                    assertEquals(0, cell, "with one prize there is nothing else to show");
                }
            }
        }
    }

    @Test
    void itDecelerates() {
        Duration previous = Duration.ZERO;
        for (int step = 0; step < WheelStrip.steps(); step++) {
            final Duration delay = WheelStrip.delay(step);
            assertTrue(
                    delay.compareTo(previous) >= 0,
                    "step " + step + " is faster than the one before it (" + delay + " after " + previous
                            + "); a wheel that speeds up again has been retuned wrong");
            previous = delay;
        }
        final Duration total = WheelStrip.total();
        assertTrue(
                total.compareTo(Duration.ofSeconds(4)) >= 0 && total.compareTo(Duration.ofSeconds(8)) <= 0,
                "a spin is meant to be about five seconds - long enough to lean in, short enough to"
                        + " do twice. It is " + total + ".");
    }

    @Test
    void theWinnerHasToBeInThePool() {
        final WheelStrip.Shape shape = WheelPanel.shape();
        assertThrows(IllegalArgumentException.class, () -> WheelStrip.landingOn(even(3), 3, new Random(), shape));
        assertThrows(IllegalArgumentException.class, () -> WheelStrip.landingOn(even(3), -1, new Random(), shape));
        assertThrows(IllegalArgumentException.class, () -> WheelStrip.landingOn(even(0), 0, new Random(), shape));
        assertThrows(
                IllegalArgumentException.class,
                () -> WheelStrip.landingOn(List.of(1, 0, 1), 1, new Random(), shape),
                "a prize of weight zero cannot be won, so it cannot be the winner either");
    }

    @Test
    void aShapeHasToBeAbleToRestSomewhere() {
        assertThrows(IllegalArgumentException.class, () -> new WheelStrip.Shape(0, 0));
        assertThrows(IllegalArgumentException.class, () -> new WheelStrip.Shape(12, 12));
        assertThrows(IllegalArgumentException.class, () -> new WheelStrip.Shape(12, -1));
    }

    @Test
    void framesAreBounded() {
        final WheelStrip strip = WheelStrip.landingOn(even(4), 1, new Random(3), WheelPanel.shape());
        assertThrows(IllegalArgumentException.class, () -> strip.cells(WheelStrip.steps()));
        assertThrows(IllegalArgumentException.class, () -> strip.cells(-1));
        assertThrows(IllegalArgumentException.class, () -> WheelStrip.delay(WheelStrip.steps()));
    }
}
