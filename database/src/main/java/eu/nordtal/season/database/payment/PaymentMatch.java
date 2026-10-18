package eu.nordtal.season.database.payment;

/** How a payment was attributed to a request, stored in {@code payment_request.matched_by}. */
public enum PaymentMatch {

    /** The payment came in under the bunq.me tab of the request, the strongest match. */
    TAB,

    /** The description of the payment holds the {@code NT-XXXXXX} reference of the request, and there is no tab. */
    REFERENCE,

    /** An admin booked it by hand on the Access page. No bunq payment exists. */
    MANUAL
}
