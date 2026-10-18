package eu.nordtal.season.database.payment;

/** The state of one attempt to buy access, stored in {@code payment_request.status}. */
public enum PaymentRequestStatus {

    /** Waiting to be paid. A partial unique index allows one per person. */
    OPEN,

    /** Paid and booked. */
    PAID,

    /** Ran past the TTL unpaid. Its bunq tab was cancelled. */
    EXPIRED,

    /** Cancelled by the user or an admin. Its bunq tab was cancelled. */
    CANCELLED,

    /** Replaced by a newer request of the same person. Its bunq tab was cancelled. */
    SUPERSEDED
}
