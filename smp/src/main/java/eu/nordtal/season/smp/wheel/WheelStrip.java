package eu.nordtal.season.smp.wheel;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

public final class WheelStrip {

    /** {@code centre} is the index of the cell where the winner stops. */
    public record Shape(int count, int centre) {

        public Shape {
            if (count < 1) {
                throw new IllegalArgumentException("a wheel shows at least one cell, not " + count);
            }
            if (centre < 0 || centre >= count) {
                throw new IllegalArgumentException("cell " + centre + " is not one of " + count);
            }
        }
    }

    /** The last delay is the pause before the strike. */
    private static final List<Duration> DELAYS = millis(
            100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 150, 150, 200, 250, 300, 400, 500, 650, 800,
            900);

    private final int[] ring;
    private final int winner;
    private final Shape shape;

    private WheelStrip(final int[] ring, final int winner, final Shape shape) {
        this.ring = ring;
        this.winner = winner;
        this.shape = shape;
    }

    /**
     * Draws one ring of cells for the whole spin, with the winner in the resting cell at the last step.
     *
     * Every other cell is drawn with the same weights as the winner, so a prize shows as often as it can be won.
     */
    public static WheelStrip landingOn(
            final List<Integer> weights, final int winner, final Random random, final Shape shape) {
        if (winner < 0 || winner >= weights.size()) {
            throw new IllegalArgumentException("prize " + winner + " is not in a pool of " + weights.size());
        }
        final Integer weight = weights.get(winner);
        if (weight == null || weight <= 0) {
            throw new IllegalArgumentException("prize " + winner + " has no positive weight and cannot be won");
        }

        // Ring index 0 holds the winner.
        final int[] ring = new int[shape.count()];
        ring[0] = winner;
        for (int index = 1; index < ring.length; index++) {
            ring[index] = PrizeDraw.draw(weights, random);
        }
        return new WheelStrip(ring, winner, shape);
    }

    private static List<Duration> millis(final long... each) {
        return Arrays.stream(each).mapToObj(Duration::ofMillis).toList();
    }

    public static int steps() {
        return DELAYS.size();
    }

    public static Duration delay(final int step) {
        if (step < 0 || step >= steps()) {
            throw new IllegalArgumentException("step " + step + " is not one of " + steps());
        }
        return DELAYS.get(step);
    }

    public int[] cells(final int step) {
        if (step < 0 || step >= steps()) {
            throw new IllegalArgumentException("step " + step + " is not one of " + steps());
        }
        // Each step moves every prize one cell clockwise, to the next index; the last step rests ring[0] on the centre.
        final int n = shape.count();
        final int[] out = new int[n];
        for (int cell = 0; cell < n; cell++) {
            out[cell] = ring[Math.floorMod(cell - shape.centre() - step + steps() - 1, n)];
        }
        return out;
    }

    public Shape shape() {
        return shape;
    }

    public static Duration total() {
        return DELAYS.stream().reduce(Duration.ZERO, Duration::plus);
    }

    public int winner() {
        return winner;
    }
}
