package eu.nordtal.s2.smp.db;

/**
 * One objective's stored progress; its definition lives in {@code milestones.yml}.
 *
 * @param completed whether it is finished; a target lowered below the collected amount completes on the next reload
 */
public record ObjectiveRow(java.util.UUID id, String key, long amount, long target, boolean completed) {

    /** Clamped to 1.0, because a lowered target can leave more collected than is wanted. */
    public double ratio() {
        if (target <= 0) {
            return 1.0;
        }
        return Math.min(1.0, (double) amount / (double) target);
    }
}
