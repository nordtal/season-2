package eu.nordtal.s2.common.payment;

/** The life of one attempt to buy access, stored in {@code payment_request.status}. */
public enum PaymentRequestStatus {

    /** Being worked on or waiting to be paid; at most one per person, by a partial unique index. */
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
