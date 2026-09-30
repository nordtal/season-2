package eu.nordtal.s2.database.inbox;

/**
 * Where a request has got to, the same in every inbox table.
 * {@code PENDING -> RUNNING -> DONE | REFUSED | FAILED}, {@code PENDING -> EXPIRED} when nobody claimed it in time,
 * and {@code CANCELLED} when the asking side withdrew it.
 */
public enum InboxStatus {

    /** Written, waiting for its consumer and for its time. */
    PENDING,

    /** Claimed by the consumer. */
    RUNNING,

    /** Carried out; the outcome is the kind's answer. */
    DONE,

    /** Answered with a refusal: an answer, not a fault. */
    REFUSED,

    /** The consumer failed while carrying it out; it is never retried. */
    FAILED,

    /** Nobody claimed it before it expired. */
    EXPIRED,

    /** Withdrawn before it ran. */
    CANCELLED;

    /** Returns whether this row is finished with, whichever way it went. */
    public boolean settled() {
        return this != PENDING && this != RUNNING;
    }
}
