package eu.nordtal.s2.common.update;

/**
 * Where an {@link UpdateRequest} has got to. Stored verbatim in {@code update_request.status},
 * which a database {@code CHECK} restricts to these five.
 *
 * <p>{@code PENDING -> RUNNING -> DONE | FAILED}, or {@code PENDING -> CANCELLED}. Nothing goes
 * back, and nothing skips {@code RUNNING} - a row that reached a terminal state was claimed by an
 * updater first, which is what makes "claimed" and "finished" two separate facts a reader can tell
 * apart.</p>
 */
public enum UpdateStatus {

    /** Written and waiting. Nothing has looked at it yet. */
    PENDING,

    /** Claimed by an updater. Exactly one process holds it. */
    RUNNING,

    /** Finished. {@code result} holds the report. */
    DONE,

    /**
     * Finished badly. {@code result} says how. Also what an updater marks rows it finds
     * {@code RUNNING} at startup: nobody is running them, because it is the only process that
     * claims.
     */
    FAILED,

    /**
     * Withdrawn before it ran. Only reachable from {@link #PENDING}, and in practice only for a
     * {@link UpdateKind#RESTART} inside its own countdown.
     */
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
