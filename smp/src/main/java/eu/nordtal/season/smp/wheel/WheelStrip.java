package eu.nordtal.season.smp.wheel;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Where a spinning wheel's icons sit at every step of the animation, and how long each step lasts.
 *
 * The prize is spent in SQL first; {@link #landingOn} writes it into the cell the marker rests on at the last step.
 */
public final class WheelStrip {

    /**
     * The visible cells and the one the winner stops in, as the surface defines them.
     *
     * @param count how many cells the surface shows at once
     * @param centre which of them the winner stops in, counted from the first
     */
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

    /** How long each frame stands, in milliseconds; the last is the pause before the strike. */
    private static final List<Duration> DELAYS = millis(
            100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 150, 150, 200, 250, 300, 400, 500, 650, 800,
            900);

    private final int[] sequence;
    private final int winner;
    private final Shape shape;

    private WheelStrip(final int[] sequence, final int winner, final Shape shape) {
        this.sequence = sequence;
        this.winner = winner;
        this.shape = shape;
    }

    /**
     * A strip for a pool of {@code poolSize} prizes whose last step rests {@code winner} on the shape's resting cell.
     *
     * @throws IllegalArgumentException on an empty pool or a winner outside it
     */
    public static WheelStrip landingOn(final int poolSize, final int winner, final Random random, final Shape shape) {
        if (poolSize < 1) {
            throw new IllegalArgumentException("a wheel needs at least one prize, not " + poolSize);
        }
        if (winner < 0 || winner >= poolSize) {
            throw new IllegalArgumentException("prize " + winner + " is not in a pool of " + poolSize);
        }

        // The winner goes in first, at the cell the marker stops on; filling first can drop a copy beside it.
        final int landing = steps() - 1 + shape.centre();
        final int[] sequence = new int[steps() + shape.count()];
        sequence[landing] = winner;

        for (int index = 0; index < sequence.length; index++) {
            if (index == landing) {
                continue;
            }
            final int left = index > 0 ? sequence[index - 1] : -1;
            final int right = index + 1 == landing ? winner : -1;

            // Two of the same icon side by side reads as stopped; bounded by pool size, since there may be no answer.
            int pick = random.nextInt(poolSize);
            for (int attempt = 0; attempt < poolSize; attempt++) {
                if (pick != left && pick != right) {
                    break;
                }
                pick = (pick + 1) % poolSize;
            }
            sequence[index] = pick;
        }
        return new WheelStrip(sequence, winner, shape);
    }

    private static List<Duration> millis(final long... each) {
        return Arrays.stream(each).mapToObj(Duration::ofMillis).toList();
    }

    /** How many frames the animation has. */
    public static int steps() {
        return DELAYS.size();
    }

    /** How long to wait after drawing {@code step} before drawing the next one. */
    public static Duration delay(final int step) {
        if (step < 0 || step >= steps()) {
            throw new IllegalArgumentException("step " + step + " is not one of " + steps());
        }
        return DELAYS.get(step);
    }

    /** The prize index in each visible cell at {@code step}, in the surface's own cell order. */
    public int[] cells(final int step) {
        if (step < 0 || step >= steps()) {
            throw new IllegalArgumentException("step " + step + " is not one of " + steps());
        }
        final int[] out = new int[shape.count()];
        System.arraycopy(sequence, step, out, 0, shape.count());
        return out;
    }

    /** The surface this strip was built for. */
    public Shape shape() {
        return shape;
    }

    /** How long a whole spin takes, including the pause before the strike. */
    public static Duration total() {
        return DELAYS.stream().reduce(Duration.ZERO, Duration::plus);
    }

    /** The prize this strip was built to land on. */
    public int winner() {
        return winner;
    }
}
