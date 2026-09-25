package eu.nordtal.s2.common.notify;

/**
 * Every {@code LISTEN}/{@code NOTIFY} channel in the network, named once.
 *
 * <h2>Why they live here and not next to the listener</h2>
 * A channel name is only ever right in pairs: a {@code pg_notify(...)} somewhere in a statement and
 * a {@code LISTEN} somewhere in a process that has never met it. Until 2026-09-04 the notifying
 * halves were literals inside {@code :common}'s SQL and the listening halves were constants in
 * {@code proxy}, three packages away - and a listener quietly pointed at a channel nobody
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
     * <p>Matches {@code pg_notify('nordtal_admin', discord_id)} in {@code JdbiAdminTree}, for a grant,
     * a revocation and a branch dropped on leaving the guild.
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
     * listen: steward-worker, so a request is claimed the moment it is written, and the proxy, so the
     * countdown appears in front of every player the moment the worker starts one rather than up to
     * a poll later. On a thirty-second countdown a five-second poll is a sixth of the warning spent
     * before it is shown.</p>
     *
     * <p>The proxy's poll is still the guarantee: this only makes it feel instant, and the first
     * thing every listener does on connect is re-read.</p>
     */
    public static final String UPDATE = "nordtal_update";

    /**
     * The proxy published a new command allowlist. Payload: empty.
     *
     * <p>Matches the bare {@code NOTIFY nordtal_allowlist} in {@code AllowlistDao#notifyChanged} -
     * the one notification here that is its own statement rather than a {@code pg_notify} riding
     * inside the write, for the reason that method gives. The three Paper backends listen; the
     * proxy does not, because it reads the list out of its own {@code network.yml} and is the
     * process that wrote the row.</p>
     *
     * <p>It is emitted only when the value actually changed, so a proxy restart with an unedited
     * list wakes nobody. That is an optimisation and not the contract: each backend polls as well,
     * and re-reads the whole list on every signal and every reconnect.</p>
     */
    public static final String ALLOWLIST = "nordtal_allowlist";

    /**
     * A {@code payment_request} row was written across the seam. Payload: empty.
     *
     * <p>Matches {@code pg_notify('nordtal_payment', '')} in every write of
     * {@code PaymentRequestDao} that belongs to the seam - {@code requestTab}, {@code attachTab},
     * {@code failTab}, {@code requestCancel}, {@code recordCancelled}, {@code recordMatch} and
     * {@code noticeOnce}. Each one carries it inside the statement, in a CTE over the rows that
     * actually changed, so a notification only exists for a write that committed and a no-op write
     * is silent.</p>
     *
     * <p>Two processes listen (steward/109): steward-worker, so a requested tab is created in the
     * seconds the user is looking at the message rather than at the next poll, and discord-bot, so
     * the link, the failure, the booking and an unmatchable payment each reach Discord the moment
     * they exist. Both halves poll as well - the poll is the guarantee, this only makes it feel
     * immediate - and both re-read their queues in full on every signal and every reconnect, which
     * is why the payload is empty.</p>
     *
     * <p>The signal deliberately says nothing about which of the seven writes it was. Both
     * listeners run every one of their refreshes on every wake-up, which is the rule this whole
     * mechanism is built on: a listener that decided a notification was not for it has no second
     * chance, because a notification is delivered once.</p>
     *
     * <p>Since 2026-09-18 the channel exists before either listener does. That is the wrong way
     * round only in appearance: a {@code LISTEN} on a channel nobody publishes on and a working one
     * look identical, so the publishing half is the half worth having first.</p>
     */
    public static final String PAYMENT = "nordtal_payment";

    /**
     * An access change was asked for. Payload: empty.
     *
     * <p>Matches {@code pg_notify('nordtal_access', '')} in {@code AccessRequestDao#submit}, which
     * carries it inside the insert so that a notification only ever exists for a row that
     * committed. One process listens - discord-bot, the only one holding a JDA session and
     * therefore the only one that can apply a role, send a direct message and write the admin line
     * that a grant consists of (season-2-community/08).</p>
     *
     * <p>The bot polls as well, and re-reads every pending row on every signal and every reconnect,
     * which is why the payload is empty. A row written while the bot was restarting is carried out
     * when it comes back - the thing an HTTP call between the two processes could not have
     * done.</p>
     */
    public static final String ACCESS = "nordtal_access";

    private Channels() {
    }
}
