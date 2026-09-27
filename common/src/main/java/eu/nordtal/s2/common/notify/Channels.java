package eu.nordtal.s2.common.notify;

/**
 * Every {@code LISTEN}/{@code NOTIFY} channel in the network, named once next to the SQL that emits it.
 * A listener pointed at a channel nobody publishes on looks exactly like one that works.
 */
public final class Channels {

    /** The season phase moved. Payload: empty; the listener re-reads the row. */
    public static final String PHASE = "nordtal_phase";

    /** Somebody's {@code discord_user.admin} flag was written. Payload: the Discord id, never trusted as state. */
    public static final String ADMIN = "nordtal_admin";

    /** A command was addressed to another process. Payload: the target's name, never inspected. */
    public static final String COMMAND = "nordtal_command";

    /** A run was asked for, or one has started counting down. Payload: empty. steward-worker and the proxy listen. */
    public static final String UPDATE = "nordtal_update";

    /**
     * The proxy published a new command allowlist.
     * Payload: empty. Emitted as a bare {@code NOTIFY}, only on a change; the three Paper backends listen.
     */
    public static final String ALLOWLIST = "nordtal_allowlist";

    /**
     * A {@code payment_request} row was written across the seam.
     * Payload: empty. Emitted inside the statement, so only a committed change signals; steward-worker and discord-bot
     * listen.
     */
    public static final String PAYMENT = "nordtal_payment";

    /** An access change was asked for. Payload: empty. Emitted inside the insert; discord-bot listens. */
    public static final String ACCESS = "nordtal_access";

    private Channels() {}
}
