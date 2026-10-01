package eu.nordtal.s2.internalapi;

/**
 * The routes steward-bunq answers and the shapes on them; the bank SDK's own types never leave steward-bunq.
 *
 * Amounts are whole euro cents, and only money that arrived in EUR crosses this wire at all.
 */
public final class BankWire {

    /** The compose service, its default port and the address steward reaches it at. */
    public static final String SERVICE = "steward-bunq";

    public static final int PORT = 8082;

    /** Whether a bank account is behind the service: {@link Account}. */
    public static final String ACCOUNT = "/api/account";

    /** POST a {@link NewTab}, answered by its {@link Tab}. */
    public static final String TABS = "/api/tabs";

    /** POST, answered by {@link Cancelled}; {@code {id}} is the tab. */
    public static final String CANCEL = TABS + "/{id}/cancel";

    /** The {@link Payment}s that settled one tab. */
    public static final String TAB_PAYMENTS = TABS + "/{id}/payments";

    /** The account's latest {@link Payment}s, newest first, at most {@code ?count=}. */
    public static final String RECENT = "/api/payments";

    private BankWire() {}

    /** Returns {@code route} with its {@code {id}} filled in. */
    public static String of(final String route, final long id) {
        return route.replace("{id}", Long.toString(id));
    }

    /** Whether an API key and an account id were both set, and the account polled and billed. */
    public record Account(boolean configured, long id) {}

    /** A tab to open: what it asks for, which the payer can change, and the reference both sides see. */
    public record NewTab(int amountCents, String description) {}

    /** An open tab: what cancels it and finds its payments, and the address the payer is sent to. */
    public record Tab(long id, String shareUrl) {}

    /** Whether the bank closed the tab; an already closed or paid one answers {@code false}. */
    public record Cancelled(boolean accepted) {}

    /**
     * One incoming payment in EUR, as the bank recorded it.
     *
     * @param created ISO-8601 in UTC; a payment whose time cannot be read never crosses the wire
     */
    public record Payment(long id, int cents, String created, String description) {}
}
