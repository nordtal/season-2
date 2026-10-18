package eu.nordtal.season.database.payment;

/**
 * Why no bunq.me tab was made, stored in {@code payment_request.tab_refusal}.
 *
 * The payer's text and the admins' alert come from it. The words of bunq go to steward's log only.
 */
public enum TabRefusal {

    /** steward-bunq has no bunq key. */
    NOT_CONFIGURED,

    /** steward-bunq did not answer. */
    UNREACHABLE,

    /** bunq refused the request, such as an amount above its limit (400). */
    BAD_REQUEST,

    /** bunq did not accept the key or its session (401). */
    UNAUTHORIZED,

    /** bunq accepted the key but not this action on the account (403). */
    FORBIDDEN,

    /** bunq does not know the monetary account (404). */
    NOT_FOUND,

    /** bunq asked to slow down (429). */
    TOO_MANY_REQUESTS,

    /** bunq failed on its side (500). */
    BUNQ_ERROR,

    /** Any other answer of bunq or steward-bunq. */
    UNKNOWN;

    /** Returns whether it likely passes by itself, so the payer is told to try again soon. */
    public boolean passes() {
        return this == UNREACHABLE || this == TOO_MANY_REQUESTS || this == BUNQ_ERROR;
    }

    /** Returns the refusal for an HTTP status that the SDK of bunq names. */
    public static TabRefusal ofBunqStatus(final int status) {
        return switch (status) {
            case 400 -> BAD_REQUEST;
            case 401 -> UNAUTHORIZED;
            case 403 -> FORBIDDEN;
            case 404 -> NOT_FOUND;
            case 429 -> TOO_MANY_REQUESTS;
            case 500 -> BUNQ_ERROR;
            default -> UNKNOWN;
        };
    }
}
