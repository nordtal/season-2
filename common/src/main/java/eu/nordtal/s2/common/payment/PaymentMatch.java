package eu.nordtal.s2.common.payment;

/**
 * How a payment was attributed to a request, stored in {@code payment_request.matched_by}.
 *
 * The names are the stored strings, restricted by {@code payment_request_matched_by_check}.
 */
public enum PaymentMatch {

    /** The payment arrived under the request's own bunq.me tab, the strongest match. */
    TAB,

    /** The payment's description carried the request's {@code NT-XXXXXX} reference, with no tab. */
    REFERENCE,

    /** An admin confirmed it with {@code /settle}; there may be no bunq payment behind it. */
    MANUAL
}
