package eu.nordtal.season.smp.milestone;

/** The stored progress of an objective. */
public record ObjectiveRow(java.util.UUID id, String key, long amount, long target, boolean completed) {

    public ObjectiveRow withAmount(final long collected) {
        return new ObjectiveRow(id, key, collected, target, completed);
    }

    /** Returns {@code amount} divided by {@code target}, at most 1.0, and 1.0 when {@code target} is zero or less. */
    public double ratio() {
        if (target <= 0) {
            return 1.0;
        }
        return Math.min(1.0, (double) amount / (double) target);
    }
}
