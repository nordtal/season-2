package eu.nordtal.s2.database.update;

/**
 * Where an {@link UpdateRequest} has got to, stored as its inbox status.
 *
 * {@code PENDING -> RUNNING -> DONE | FAILED}, or {@code PENDING -> CANCELLED}; nothing goes back.
 */
public enum UpdateStatus {

    /** Written and waiting. */
    PENDING,

    /** Claimed by steward; exactly one process holds it. */
    RUNNING,

    /** Finished; {@code result} holds the report. */
    DONE,

    /** Finished badly, with {@code result} saying how; also what a starting steward marks leftover running rows. */
    FAILED,

    /** Withdrawn before it ran; only reachable from {@link #PENDING}. */
    CANCELLED;

    /** Returns whether nothing more will happen to this row. */
    public boolean isFinished() {
        return this == DONE || this == FAILED || this == CANCELLED;
    }

    /**
     * Reads a value out of the database.
     *
     * @param value the column, may be {@code null}
     * @return the status, or {@link #FAILED} for anything this build does not recognise
     */
    public static UpdateStatus fromDatabase(final String value) {
        if (value == null) {
            return FAILED;
        }
        for (final UpdateStatus status : values()) {
            if (status.name().equals(value)) {
                return status;
            }
        }
        return FAILED;
    }
}
