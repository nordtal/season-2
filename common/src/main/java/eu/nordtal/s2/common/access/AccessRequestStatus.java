package eu.nordtal.s2.common.access;

/**
 * Where an {@link AccessRequest} has got to.
 *
 * {@code PENDING -> RUNNING -> DONE | FAILED}, or {@code PENDING -> EXPIRED}. Nothing goes
 * back.
 *
 * <b>{@link #EXPIRED} means exactly one thing: nothing ever picked this up.</b> The executing
 * side never writes it - it refuses to claim a row past its expiry instead - so a row in this state
 * is proof that the bot was not there, and never proof that it was there and failed. That
 * distinction is the whole reason the status exists, and it is the same rule
 * {@code CommandRequests} follows.
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
