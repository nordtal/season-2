package eu.nordtal.season.internalapi;

import org.jspecify.annotations.Nullable;

/**
 * The routes of steward-bunq and their records.
 * Amounts are whole euro cents, and only euro payments cross the wire.
 */
public final class BankWire {

    /** The compose service name, its default port and its address. */
    public static final String SERVICE = "steward-bunq";

    public static final int PORT = 8082;

    public static final String ACCOUNT = "/api/account";

    public static final String TABS = "/api/tabs";

    public static final String CANCEL = TABS + "/{id}/cancel";

    public static final String TAB_PAYMENTS = TABS + "/{id}/payments";

    /** The account's latest {@link Payment}s, newest first, at most {@code ?count=}. */
    public static final String RECENT = "/api/payments";

    private BankWire() {}

    public static String of(final String route, final long id) {
        return route.replace("{id}", Long.toString(id));
    }

    /** Whether an API key and an account id are both set. */
    public record Account(boolean configured, long id) {}

    /** A tab to open: the amount, which the payer can change, and the reference. */
    public record NewTab(int amountCents, String description) {}

    /** An open tab: its id and the address the payer is sent to. */
    public record Tab(long id, String shareUrl) {}

    /** Whether the bank closed the tab. An already closed or paid tab answers {@code false}. */
    public record Cancelled(boolean accepted) {}

    /**
     * The answer of steward-bunq to a failed request: 503 without a key, otherwise 502.
     *
     * @param bunqStatus the HTTP status of bunq, or null if bunq was not asked or sent none
     * @param message bunq's or steward-bunq's own words, for steward's log
     */
    public record Refused(@Nullable Integer bunqStatus, String message) {}

    /**
     * One incoming euro payment.
     *
     * @param created ISO-8601 in UTC. A payment whose time cannot be read does not cross the wire.
     */
    public record Payment(long id, int cents, String created, String description) {}
}
