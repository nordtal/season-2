package eu.nordtal.season.smp.milestone;

/** Adds to the progress of an objective and decides whether the objective is complete. */
public final class ObjectiveProgress {

    private ObjectiveProgress() {}

    /**
     * The result of {@link #advance}.
     *
     * @param amount    the new total
     * @param credited  the part of the delta that was added
     * @param completes whether this change took the amount from below the target to the target or above
     */
    public record Advance(long amount, long credited, boolean completes) {}

    /**
     * Adds {@code delta} to {@code amount} and caps the sum at {@link Long#MAX_VALUE}.
     * A {@code delta} of zero or less adds nothing.
     */
    public static Advance advance(final long amount, final long target, final long delta) {
        if (delta <= 0) {
            return new Advance(amount, 0L, false);
        }
        final boolean wasComplete = amount >= target;
        final long updated = amount > Long.MAX_VALUE - delta ? Long.MAX_VALUE : amount + delta;
        return new Advance(updated, delta, !wasComplete && updated >= target);
    }

    /** Returns whether {@code amount} reaches the positive {@code newTarget} of a reloaded milestone file. */
    public static boolean completesOnReload(final long amount, final long newTarget) {
        return newTarget > 0 && amount >= newTarget;
    }

    /** Returns {@code amount} as a whole percentage of {@code target}, at most 100. */
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
