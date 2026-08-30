package eu.nordtal.s2.accessbot.payment;

/**
 * The life of one attempt to buy access. The names are the exact strings stored in
 * {@code payment_request.status}, which a database {@code CHECK} constraint restricts to these
 * five.
 */
public enum PaymentRequestStatus {

    /**
     * Being worked on or waiting to be paid. At most one per person - a partial unique index says
     * so - which is why starting a new one has to move the old one out of this state.
     */
    OPEN,

    /** Paid and booked. Carries the bunq payment id and the settlement time. */
    PAID,

    /** Ran past the configured TTL without being paid. Its bunq tab was cancelled. */
    EXPIRED,

    /** Cancelled by the user or by an admin. Its bunq tab was cancelled. */
    CANCELLED,

    /** Replaced by a newer request from the same person. Its bunq tab was cancelled. */
    SUPERSEDED
}
