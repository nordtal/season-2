package eu.nordtal.s2.smp.db;

/**
 * One objective's stored progress: how much has been collected against how much is wanted.
 *
 * <p>The <em>definition</em> - what is being counted, and by which of the three types - lives in
 * {@code milestones.yml}. Only the numbers are here, which is what makes the track editable
 * mid-season without a migration.
 *
 * @param completed whether it is finished; a target that was later lowered below the collected
 *                  amount completes on the next reload, which is the escape hatch the concept keeps
 */
public record ObjectiveRow(java.util.UUID id, String key, long amount, long target,
                           boolean completed) {

    /** Clamped to 1.0, because a lowered target can leave more collected than is wanted. */
    public double ratio() {
        if (target <= 0) {
            return 1.0;
        }
        return Math.min(1.0, (double) amount / (double) target);
    }
}
