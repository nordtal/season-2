package eu.nordtal.s2.smp.wheel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The wheel's animation, in the only half of it a test can reach.
 *
 * What is asserted here is that the strip <b>lands on the prize that was already won</b>, over every pool size the
 * config can produce and every winner in it. That is the property the whole design rests on: the spin is spent in
 * SQL before a frame is drawn, so an animation that could stop anywhere else would be a second, disagreeing answer
 * about one spin.
 *
 * <b>Two shapes, and one of them is the real one</b>
 *
 * The strip stopped being nine cells with the marker in the middle when the ring replaced the row: it
 * is twelve cells resting on the first now. Every case below runs against {@link WheelPanel#shape()} - the one the
 * server actually builds - <em>and</em> against the old nine-and-four, because a resting cell at index 0 has no left
 * neighbour and is therefore the one shape that could pass an assertion by not reaching it.
 *
 * What no test here can say is whether it looks like a wheel. That is a rehearsal item.
 */
class WheelStripTest {

    private static final List<WheelStrip.Shape> SHAPES = List.of(WheelPanel.shape(), new WheelStrip.Shape(9, 4));

    @Test
    void itAlwaysLandsOnTheWinner() {
        for (final WheelStrip.Shape shape : SHAPES) {
            for (int poolSize = 1; poolSize <= 12; poolSize++) {
                for (int winner = 0; winner < poolSize; winner++) {
                    final WheelStrip strip = WheelStrip.landingOn(poolSize, winner, new Random(winner), shape);
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
    void itTravels() {
        for (final WheelStrip.Shape shape : SHAPES) {
            final WheelStrip strip = WheelStrip.landingOn(5, 2, new Random(7), shape);
            for (int step = 0; step + 1 < WheelStrip.steps(); step++) {
                final int[] here = strip.cells(step);
                final int[] next = strip.cells(step + 1);
                for (int cell = 0; cell + 1 < shape.count(); cell++) {
                    assertEquals(
                            here[cell + 1],
                            next[cell],
                            "frame " + step + " to " + (step + 1) + " has to be the same strip one"
                                    + " cell along; anything else is a new set of icons appearing,"
                                    + " and reads as a slot machine rather than a wheel");
                }
            }
        }
    }

    /**
     * From three prizes up, because two is not solvable and the code says so.
     *
     * A cell whose left neighbour is already fixed and whose right neighbour is the winner has two forbidden values;
     * with a pool of two there is no third, and the strip is drawn with the duplicate rather than looping forever
     * looking for one.
     */
    @Test
    void neighboursDiffer() {
        for (final WheelStrip.Shape shape : SHAPES) {
            for (int poolSize = 3; poolSize <= 8; poolSize++) {
                final WheelStrip strip = WheelStrip.landingOn(poolSize, 0, new Random(poolSize), shape);
                for (int step = 0; step < WheelStrip.steps(); step++) {
                    final int[] cells = strip.cells(step);
                    for (int cell = 0; cell + 1 < cells.length; cell++) {
                        assertNotEquals(
                                cells[cell],
                                cells[cell + 1],
                                "two of the same icon side by side reads as the strip having"
                                        + " stopped, which is the wrong thing for it to say while"
                                        + " it is moving");
                    }
                }
            }
        }
    }

    @Test
    void theLandingCellStandsAlone() {
        for (final WheelStrip.Shape shape : SHAPES) {
            for (int poolSize = 3; poolSize <= 8; poolSize++) {
                for (int winner = 0; winner < poolSize; winner++) {
                    final int[] last = WheelStrip.landingOn(poolSize, winner, new Random(winner), shape)
                            .cells(WheelStrip.steps() - 1);
                    // Cell 0 has no left neighbour; guarded, not skipped - the right one is what the eye follows.
                    if (shape.centre() > 0) {
                        assertNotEquals(
                                winner,
                                last[shape.centre() - 1],
                                "the last frame is the one everybody looks at, and the prize wants"
                                        + " to be the only one of its kind in the resting cell");
                    }
                    if (shape.centre() + 1 < shape.count()) {
                        assertNotEquals(winner, last[shape.centre() + 1]);
                    }
                }
            }
        }
    }

    @Test
    void aPoolOfOne() {
        for (final WheelStrip.Shape shape : SHAPES) {
            final WheelStrip strip = WheelStrip.landingOn(1, 0, new Random(1), shape);
            for (int step = 0; step < WheelStrip.steps(); step++) {
                for (final int cell : strip.cells(step)) {
                    assertEquals(
                            0,
                            cell,
                            "with one prize there is nothing else to show, and the"
                                    + " neighbour rule has to give way rather than loop forever");
                }
            }
        }
    }

    @Test
    void itDecelerates() {
        int previous = 0;
        for (int step = 0; step < WheelStrip.steps(); step++) {
            final int delay = WheelStrip.delay(step);
            assertTrue(
                    delay >= previous,
                    "step " + step + " is faster than the one before it (" + delay + " after " + previous
                            + "); a wheel that speeds up again has been retuned wrong");
            previous = delay;
        }
        assertTrue(
                WheelStrip.totalTicks() >= 80 && WheelStrip.totalTicks() <= 160,
                "a spin is meant to be about five seconds - long enough to lean in, short enough to"
                        + " do twice. It is " + WheelStrip.totalTicks() + " ticks.");
    }

    @Test
    void theWinnerHasToBeInThePool() {
        final WheelStrip.Shape shape = WheelPanel.shape();
        assertThrows(IllegalArgumentException.class, () -> WheelStrip.landingOn(3, 3, new Random(), shape));
        assertThrows(IllegalArgumentException.class, () -> WheelStrip.landingOn(3, -1, new Random(), shape));
        assertThrows(IllegalArgumentException.class, () -> WheelStrip.landingOn(0, 0, new Random(), shape));
    }

    @Test
    void aShapeHasToBeAbleToRestSomewhere() {
        // Refused where the shape is built, not where a frame is drawn, so the failure names the wrong surface.
        assertThrows(IllegalArgumentException.class, () -> new WheelStrip.Shape(0, 0));
        assertThrows(IllegalArgumentException.class, () -> new WheelStrip.Shape(12, 12));
        assertThrows(IllegalArgumentException.class, () -> new WheelStrip.Shape(12, -1));
    }

    @Test
    void framesAreBounded() {
        final WheelStrip strip = WheelStrip.landingOn(4, 1, new Random(3), WheelPanel.shape());
        assertThrows(IllegalArgumentException.class, () -> strip.cells(WheelStrip.steps()));
        assertThrows(IllegalArgumentException.class, () -> strip.cells(-1));
        assertThrows(IllegalArgumentException.class, () -> WheelStrip.delay(WheelStrip.steps()));
    }
}
