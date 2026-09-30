package eu.nordtal.s2.database.access;

/**
 * Where an {@link AccessRequest} has got to.
 * {@code PENDING -> RUNNING -> DONE | FAILED}, or {@code PENDING -> EXPIRED}, which only means nothing picked the row
 * up.
 */
public enum AccessRequestStatus {

    /** Written, waiting for the bot. */
    PENDING,

    /** Claimed by the bot. */
    RUNNING,

    /** Carried out. {@link AccessRequest#result()} says what happened. */
    DONE,

    /** Claimed and then thrown. {@link AccessRequest#result()} says what went wrong. */
    FAILED,

    /** Nobody claimed it before {@link AccessRequest#expires()}. */
    EXPIRED;

    /** @return whether this row is finished with, whichever way it went. */
    public boolean settled() {
        return this == DONE || this == FAILED || this == EXPIRED;
    }
}
