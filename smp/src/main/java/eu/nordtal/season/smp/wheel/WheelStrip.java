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

    private final int[] sequence;
    private final int winner;
    private final Shape shape;

    private WheelStrip(final int[] sequence, final int winner, final Shape shape) {
        this.sequence = sequence;
        this.winner = winner;
        this.shape = shape;
    }

    /** At its last step the strip shows {@code winner} in the resting cell. */
    public static WheelStrip landingOn(final int poolSize, final int winner, final Random random, final Shape shape) {
        if (poolSize < 1) {
            throw new IllegalArgumentException("a wheel needs at least one prize, not " + poolSize);
        }
        if (winner < 0 || winner >= poolSize) {
            throw new IllegalArgumentException("prize " + winner + " is not in a pool of " + poolSize);
        }

        final int landing = steps() - 1 + shape.centre();
        final int[] sequence = new int[steps() + shape.count()];
        sequence[landing] = winner;

        for (int index = 0; index < sequence.length; index++) {
            if (index == landing) {
                continue;
            }
            final int left = index > 0 ? sequence[index - 1] : -1;
            final int right = index + 1 == landing ? winner : -1;

            // Neighbouring cells get different prizes when the pool allows it.
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
        final int[] out = new int[shape.count()];
        System.arraycopy(sequence, step, out, 0, shape.count());
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
