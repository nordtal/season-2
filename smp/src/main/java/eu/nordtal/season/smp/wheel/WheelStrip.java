package eu.nordtal.season.smp.wheel;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
     * A prize appears twice only when the pool has fewer prizes than cells.
     */
    public static WheelStrip landingOn(final int poolSize, final int winner, final Random random, final Shape shape) {
        if (poolSize < 1) {
            throw new IllegalArgumentException("a wheel needs at least one prize, not " + poolSize);
        }
        if (winner < 0 || winner >= poolSize) {
            throw new IllegalArgumentException("prize " + winner + " is not in a pool of " + poolSize);
        }

        final List<Integer> order = new ArrayList<>();
        for (int prize = 0; prize < poolSize; prize++) {
            if (prize != winner) {
                order.add(prize);
            }
        }
        Collections.shuffle(order, random);
        order.add(0, winner);

        // Ring index 0 holds the winner; a small pool repeats in the same order.
        final int[] ring = new int[shape.count()];
        for (int index = 0; index < ring.length; index++) {
            ring[index] = order.get(index % poolSize);
        }
        final int last = ring.length - 1;
        if (last > 0 && poolSize > 1 && ring[last] == winner) {
            ring[last] = order.get(1);
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
