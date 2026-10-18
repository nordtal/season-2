package eu.nordtal.season.database.inbox;

/**
 * The state of a request, the same in every inbox table.
 * {@code PENDING -> RUNNING -> DONE | REFUSED | FAILED}, {@code PENDING -> EXPIRED} if nobody claims it in time,
 * and {@code PENDING -> CANCELLED} if the asker withdraws it.
 */
public enum InboxStatus {

    /** Waiting for its consumer and its time. */
    PENDING,

    /** Claimed by the consumer. */
    RUNNING,

    /** Carried out. */
    DONE,

    /** Answered with a refusal, which is an answer, not a fault. */
    REFUSED,

    /** The consumer failed while carrying it out. It is not retried. */
    FAILED,

    /** Nobody claimed it before it expired. */
    EXPIRED,

    /** Withdrawn before it ran. */
    CANCELLED;

    public boolean settled() {
        return this != PENDING && this != RUNNING;
    }
}
