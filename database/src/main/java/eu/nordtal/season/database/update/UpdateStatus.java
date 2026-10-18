package eu.nordtal.season.database.update;

/**
 * The state of an {@link UpdateRequest}, stored as its inbox status.
 * {@code PENDING -> RUNNING -> DONE | FAILED}, or {@code PENDING -> CANCELLED}. Nothing goes back.
 */
public enum UpdateStatus {

    /** Written and waiting. */
    PENDING,

    /** Claimed by steward. Exactly one process holds it. */
    RUNNING,

    /** Finished. {@code result} holds the report. */
    DONE,

    /** Failed, with {@code result} saying how. A starting steward also marks leftover running rows with it. */
    FAILED,

    /** Withdrawn before it ran. Only {@link #PENDING} reaches it. */
    CANCELLED;

    public boolean isFinished() {
        return this == DONE || this == FAILED || this == CANCELLED;
    }

    /**
     * Reads a value out of the database.
     *
     * @param value the column, may be null
     * @return the status, or {@link #FAILED} for a value this build does not recognise
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
