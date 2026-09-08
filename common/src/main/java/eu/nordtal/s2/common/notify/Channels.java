package eu.nordtal.s2.common.notify;

/**
 * Every {@code LISTEN}/{@code NOTIFY} channel in the network, named once.
 *
 * <h2>Why they live here and not next to the listener</h2>
 * A channel name is only ever right in pairs: a {@code pg_notify(...)} somewhere in a statement and
 * a {@code LISTEN} somewhere in a process that has never met it. Until 2026-09-04 the notifying
 * halves were literals inside {@code :common}'s SQL and the listening halves were constants in
 * {@code network-control}, three packages away - and a listener quietly pointed at a channel nobody
 * publishes on looks exactly like a listener that works, right up until the moment it is needed.
 * The SQL is in this module, so the names belong in this module, where the statement that emits
 * them and the loop that waits for them can be read against each other.
 *
 * <h2>They are constants, not configuration</h2>
 * Settled 2026-08-31 with the thirty-second poll, and the reasoning is unchanged: nothing is gained
 * by making them settable, and what is lost is the ability to tell a misconfigured listener from a
 * working one.
 */
public final class Channels {

    /**
     * The season phase moved. Payload: empty, on purpose - the listener re-reads the row.
     *
     * <p>Matches {@code pg_notify('nordtal_phase', '')} in {@code PhaseDao#switchPhase}.</p>
     */
    public static final String PHASE = "nordtal_phase";

    /**
     * Somebody's {@code discord_user.admin} flag was written. Payload: the Discord id.
     *
     * <p>Matches {@code pg_notify('nordtal_admin', discord_id)} in {@code AccessDao#setAdmin}.
     * The payload is <b>not</b> trusted as state by anything: every listener re-reads the whole set,
     * which is what makes a lost notification cost latency rather than correctness.</p>
     */
    public static final String ADMIN = "nordtal_admin";

    /**
     * A command was addressed to a process that is not the one it was typed in. Payload: the
     * target's name.
     *
     * <p>Matches {@code pg_notify('nordtal_command', target)} in {@code CommandRequestDao#submit}.
     * The payload names a target and is <b>still</b> not inspected by anybody: every inbox wakes on
     * every signal and claims rows for its own target with a {@code WHERE}, which is what makes one
     * connection carrying three channels cheaper than three connections and no worse. Filtering on
     * the payload would also be the one way to lose a row permanently - a notification is delivered
     * once, and a listener that decided the message was not for it has no second chance.</p>
     */
    public static final String COMMAND = "nordtal_command";

    /**
     * A run was asked for, or one has started counting down. Payload: empty.
     *
     * <p>Matches {@code pg_notify('nordtal_update', '')} in {@code UpdateDao#submit}. Two processes
     * listen: the updater, so a request is claimed the moment it is written, and the proxy, so the
     * countdown appears in front of every player the moment the updater starts one rather than up to
     * a poll later. On a thirty-second countdown a five-second poll is a sixth of the warning spent
     * before it is shown.</p>
     *
     * <p>The proxy's poll is still the guarantee: this only makes it feel instant, and the first
     * thing every listener does on connect is re-read.</p>
     */
    public static final String UPDATE = "nordtal_update";

    private Channels() {
    }
}
