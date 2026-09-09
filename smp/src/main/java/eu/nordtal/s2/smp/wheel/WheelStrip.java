package eu.nordtal.s2.smp.wheel;

import java.util.Random;

/**
 * Where a spinning wheel's icons sit at every step of the animation, and how long each step lasts.
 *
 * <h2>The prize is decided first, and the strip is built to land on it</h2>
 * This is the whole reason the class exists rather than the animation picking a winner as it slows
 * down. A spin is <b>spent in SQL</b> before anything is drawn - {@code Wheel} takes the row, and
 * only the update that changed a row gets a prize - so by the time there is anything to look at the
 * outcome is already a fact. An animation that decided the winner would be a second decision about
 * the same spin, and the two would disagree the first time a player closed the window early, or
 * logged off, or the server lagged through a tick.
 *
 * <p>So {@link #landingOn} fills the sequence with noise and then <em>writes the winner into the
 * cell the centre marker will be pointing at on the last step</em>. The wheel is honest about what
 * it shows and dishonest about nothing: the player watches a real outcome arrive.
 *
 * <h2>The deceleration is a table, not a formula</h2>
 * Twelve steps at two ticks each is a blur; the last nine are what a wheel losing its momentum
 * looks like, and the final 18-tick pause is the one that makes people lean in. A closed-form ease
 * would be shorter to write and impossible to retune by eye - and this is a thing that is tuned by
 * watching it, once, on a real client. Total is {@value #TOTAL_TICKS} ticks, a little over five
 * seconds, which is about as long as a reveal can hold somebody who has done it before.
 *
 * <p>Everything here is arithmetic, so {@code WheelStripTest} covers it without a server. What no
 * test here can say anything about is whether it <em>looks</em> like a wheel; that is a rehearsal
 * item in the owner's checklist.
 */
public final class WheelStrip {

    /**
     * Visible cells and the one the winner stops in, as a pair.
     *
     * <h2>Why these are not constants any more (2026-09-09)</h2>
     * They were 9 and 4 - one chest row, marker in the middle - because that was the only shape a
     * wheel had. The ring the owner chose has <b>twelve</b> cells laid round a hub and stops on the
     * top one, so the window's width and the resting index are properties of the surface rather
     * than of this class. Nothing else changes: the sequence, the anti-repeat rule and the
     * deceleration table are the same, which is exactly what the design artifact says a ring mode
     * costs.
     *
     * @param count  how many cells the surface shows at once
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

    /**
     * Ticks to wait <em>after</em> drawing each frame.
     *
     * <p>The last entry is not a gap between frames - there is no frame after it. It is the beat
     * between the wheel stopping and the prize landing, and it is the longest one on purpose: the
     * strike wants a moment of silence in front of it or it reads as part of the ticking.
     */
    private static final int[] DELAYS = {
            2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,
            3, 3, 4, 5, 6, 8, 10, 13, 16, 18,
    };

    private final int[] sequence;
    private final int winner;
    private final Shape shape;

    private WheelStrip(final int[] sequence, final int winner, final Shape shape) {
        this.sequence = sequence;
        this.winner = winner;
        this.shape = shape;
    }

    /**
     * A strip for a pool of {@code poolSize} prizes whose last step rests {@code winner} on
     * {@code shape}'s own resting cell.
     *
     * @throws IllegalArgumentException on an empty pool or a winner outside it - both are
     *                                  programming errors, and a wheel drawn from a pool it does not
     *                                  have would land on whatever index happened to be in range
     */
    public static WheelStrip landingOn(final int poolSize, final int winner, final Random random,
                                       final Shape shape) {
        if (poolSize < 1) {
            throw new IllegalArgumentException("a wheel needs at least one prize, not " + poolSize);
        }
        if (winner < 0 || winner >= poolSize) {
            throw new IllegalArgumentException(
                    "prize " + winner + " is not in a pool of " + poolSize);
        }

        // The winner goes in first, at the cell the marker points at on the last frame, and
        // everything else is filled around it. The other order - fill, then overwrite - is the
        // obvious one and it is wrong: overwriting can drop the winner next to a copy of itself,
        // in the one frame every player is actually looking at.
        final int landing = steps() - 1 + shape.centre();
        final int[] sequence = new int[steps() + shape.count()];
        sequence[landing] = winner;

        for (int index = 0; index < sequence.length; index++) {
            if (index == landing) {
                continue;
            }
            final int left = index > 0 ? sequence[index - 1] : -1;
            final int right = index + 1 == landing ? winner : -1;

            // Two of the same icon side by side reads as the strip having stopped, which is
            // precisely the wrong thing for it to say while it is still moving. Bounded by the
            // pool size rather than looping until it works: with two prizes and a fixed
            // neighbour on each side there is no answer, and the strip has to be drawn anyway.
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

    /** How many frames the animation has. */
    public static int steps() {
        return DELAYS.length;
    }

    /** Ticks to wait after drawing {@code step} before drawing the next one. */
    public static int delay(final int step) {
        if (step < 0 || step >= steps()) {
            throw new IllegalArgumentException("step " + step + " is not one of " + steps());
        }
        return DELAYS[step];
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

    /** How long a whole spin takes, in ticks, including the pause before the strike. */
    public static int totalTicks() {
        int sum = 0;
        for (final int delay : DELAYS) {
            sum += delay;
        }
        return sum;
    }

    /** The prize this strip was built to land on. */
    public int winner() {
        return winner;
    }
}
