package eu.nordtal.s2.common.update;

/**
 * Where an {@link UpdateRequest} has got to, stored in {@code update_request.status}.
 *
 * {@code PENDING -> RUNNING -> DONE | FAILED}, or {@code PENDING -> CANCELLED}; nothing goes back.
 */
public enum UpdateStatus {

    /** Written and waiting. Nothing has looked at it yet. */
    PENDING,

    /** Claimed by a worker. Exactly one process holds it. */
    RUNNING,

    /** Finished. {@code result} holds the report. */
    DONE,

    /** Finished badly, with {@code result} saying how; also what a starting worker marks leftover running rows. */
    FAILED,

    /** Withdrawn before it ran; only reachable from {@link #PENDING}. */
    CANCELLED;

    /** Whether nothing more will happen to this row. */
    public boolean isFinished() {
        return this == DONE || this == FAILED || this == CANCELLED;
    }

    /**
     * Reads a value out of the database.
     *
     * @param value the column, may be {@code null}
     * @return the status, or {@link #FAILED} for anything this build does not recognise - a row
     *         nobody can interpret must not read as one that is still going to happen
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
