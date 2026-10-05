package eu.nordtal.season.database.payment;

/** How a payment was attributed to a request, stored under its name in {@code payment_request.matched_by}. */
public enum PaymentMatch {

    /** The payment arrived under the request's own bunq.me tab, the strongest match. */
    TAB,

    /** The payment's description carried the request's {@code NT-XXXXXX} reference, with no tab. */
    REFERENCE,

    /** An admin booked it by hand on the Access page; there is no bunq payment behind it. */
    MANUAL
}
