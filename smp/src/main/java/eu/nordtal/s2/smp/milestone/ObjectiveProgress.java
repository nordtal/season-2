package eu.nordtal.s2.smp.milestone;

/**
 * Advancing one objective's progress, and deciding when it is finished.
 *
 * <p>Small, and separate from the DAO on purpose: "did that hand-in complete the objective" is the
 * question that fires the payout, the announcement, the milestone unlock and possibly the border
 * move, and it is one comparison that must not be written twice.
 *
 * <h2>Progress is monotonic and is never recomputed</h2>
 * Every type accumulates. A {@code HAND_IN} adds what was delivered, a {@code STATISTIC} adds the
 * increase since the objective started, and an {@code ADVANCEMENT} adds one the first time each
 * player earns it. Nothing is ever recalculated from the world, which is what makes a player who
 * earned an advancement and never logged in again stay counted
 * (docs/smp.md#the-rules-the-content-has-to-obey).
 */
public final class ObjectiveProgress {

    private ObjectiveProgress() {
    }

    /**
     * The result of adding to an objective.
     *
     * @param amount    the new total
     * @param credited  how much of the delta was actually credited - never more than was offered,
     *                  and never negative
     * @param completes whether this is the change that finished the objective. True <b>exactly
     *                  once</b>: an objective already at or over its target does not complete
     *                  again, because completing is what pays the pot out
     */
    public record Advance(long amount, long credited, boolean completes) {
    }

    /**
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
        // Saturating rather than wrapping: `amount` is a bigint and nothing on this server can
        // reach its limit, but an overflowing counter would read as an objective going backwards.
        final long updated = amount > Long.MAX_VALUE - delta ? Long.MAX_VALUE : amount + delta;
        return new Advance(updated, delta, !wasComplete && updated >= target);
    }

    /**
     * Whether lowering a target has just finished an objective.
     *
     * <p>The first and finest escape hatch (docs/smp.md#when-an-objective-turns-out-to-be-impossible):
     * "if the progress already collected is at or above the new target, the objective completes at
     * once and pays normally" - <b>the full pot</b>, not the scaled one, because nothing was
     * rescued and nobody was short-changed; the number was simply wrong when it was written.
     *
     * @param amount    the progress already collected
     * @param newTarget the target the reloaded file now asks for
     * @return whether the objective is finished the moment the file is reloaded
     */
    public static boolean completesOnReload(final long amount, final long newTarget) {
        return newTarget > 0 && amount >= newTarget;
    }

    /**
     * @param amount the progress collected
     * @param target the target
     * @return how far along the objective is, as a whole percentage capped at 100 - what the
     *         objective board and the HUD print. Integer arithmetic, because a board that reads
     *         "99.7 %" for two hours is worse than one that reads "99 %"
     */
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
