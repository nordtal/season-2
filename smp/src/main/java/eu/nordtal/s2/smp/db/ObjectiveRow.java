package eu.nordtal.s2.smp.db;

/**
 * One objective's stored progress; its definition lives in the {@code milestones} group.
 *
 * @param completed whether it is finished; a target lowered below the collected amount completes on the next reload
 */
public record ObjectiveRow(java.util.UUID id, String key, long amount, long target, boolean completed) {

    /** Returns this row as it stands once a credit has brought it to {@code collected}. */
    public ObjectiveRow withAmount(final long collected) {
        return new ObjectiveRow(id, key, collected, target, completed);
    }

    /** Clamped to 1.0, because a lowered target can leave more collected than is wanted. */
    public double ratio() {
        if (target <= 0) {
            return 1.0;
        }
        return Math.min(1.0, (double) amount / (double) target);
    }
}
