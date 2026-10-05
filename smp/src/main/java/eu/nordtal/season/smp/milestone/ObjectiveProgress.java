package eu.nordtal.season.smp.milestone;

/**
 * Advancing one objective's progress, and deciding when it is finished.
 *
 * Progress only accumulates and is never recomputed from the world.
 */
public final class ObjectiveProgress {

    private ObjectiveProgress() {}

    /**
     * The result of adding to an objective.
     *
     * @param amount    the new total
     * @param credited  how much of the delta was credited, between zero and the delta
     * @param completes whether this change finished the objective, true exactly once because completing pays out
     */
    public record Advance(long amount, long credited, boolean completes) {}

    /**
     * Adds to an objective.
     *
     * @param amount the objective's current {@code smp_objective.amount}
     * @param target its {@code target}
     * @param delta  how much to add; zero or less credits nothing
     * @return the new state
     */
    public static Advance advance(final long amount, final long target, final long delta) {
        if (delta <= 0) {
            return new Advance(amount, 0L, false);
        }
        final boolean wasComplete = amount >= target;
        // Saturating rather than wrapping; an overflowing counter would read as going backwards.
        final long updated = amount > Long.MAX_VALUE - delta ? Long.MAX_VALUE : amount + delta;
        return new Advance(updated, delta, !wasComplete && updated >= target);
    }

    /**
     * Whether lowering a target has just finished an objective, which then pays the full pot.
     *
     * @param amount the progress already collected
     * @param newTarget the target the reloaded file now asks for
     * @return whether the objective is finished the moment the file is reloaded
     */
    public static boolean completesOnReload(final long amount, final long newTarget) {
        return newTarget > 0 && amount >= newTarget;
    }

    /** Returns how far along the objective is, as a whole percentage capped at 100. */
    public static int percentOf(final long amount, final long target) {
        if (target <= 0) {
            return 100;
        }
        if (amount <= 0) {
            return 0;
        }
        return (int) Math.min(100L, amount * 100L / target);
    }
}
