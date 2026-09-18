package eu.nordtal.s2.common.payment;

/**
 * How a payment was attributed to a request. The names are the exact strings stored in
 * {@code payment_request.matched_by}, which {@code payment_request_matched_by_check} restricts to
 * these three - the same arrangement as {@link PaymentRequestStatus}.
 * <p>
 * The three already exist in the code as three separate paths that each end in a booking and then
 * forget which one they were: {@code PaymentProcessor#matchByTab},
 * {@code PaymentProcessor#matchByReference} and {@code /settle}. The row records the id of the
 * payment and nothing about how it was found, so a human reconstructing a booking - "why does this
 * grant exist" - has no way back to the mechanism. That is what this is for, and it is why the
 * column is worth its width even before anything reads it.
 * </p>
 */
public enum PaymentMatch {

    /**
     * The payment showed up under the request's own bunq.me tab. The strongest of the three: the
     * tab was created for this request and asks for its exact amount.
     */
    TAB,

    /**
     * The payment carried the request's {@code NT-XXXXXX} reference in its description, and no tab
     * connected the two. A bank transfer typed by hand lands here.
     */
    REFERENCE,

    /**
     * An admin said so, with {@code /settle}. There may be no bunq payment behind it at all, which
     * is why {@code bunq_payment_id} stays null and the partial unique index allows that.
     */
    MANUAL
}
