package eu.nordtal.season.database.payment;

/**
 * Why no bunq.me tab was made, stored under its name in {@code payment_request.tab_refusal}.
 *
 * The payer's text and the admins' alert are drawn from it; bunq's own words go to steward's log only.
 */
public enum TabRefusal {

    /** steward-bunq has no bunq key. */
    NOT_CONFIGURED,

    /** steward-bunq did not answer. */
    UNREACHABLE,

    /** bunq refused the request itself, such as an amount above its limit: its 400. */
    BAD_REQUEST,

    /** bunq did not accept the key or its session: its 401. */
    UNAUTHORIZED,

    /** bunq accepted the key but not this action on the account: its 403. */
    FORBIDDEN,

    /** bunq does not know the monetary account: its 404. */
    NOT_FOUND,

    /** bunq asked to slow down: its 429. */
    TOO_MANY_REQUESTS,

    /** bunq failed on its side: its 500. */
    BUNQ_ERROR,

    /** Anything else bunq or steward-bunq answered. */
    UNKNOWN;

    /** Returns whether it likely passes by itself, so the payer is told to try again soon rather than wait for an admin. */
    public boolean passes() {
        return this == UNREACHABLE || this == TOO_MANY_REQUESTS || this == BUNQ_ERROR;
    }

    /** Returns the refusal bunq meant by answering this HTTP status, the statuses its SDK names. */
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
