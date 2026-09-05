package eu.nordtal.s2.common.command;

import javax.sql.DataSource;

import java.util.Optional;

/**
 * The inbox every process shares: one admin command, addressed to the JVM that can carry it out.
 *
 * <h2>Why a table and not a call</h2>
 * The five processes share nothing but one PostgreSQL. There is no socket between them and
 * deliberately no Docker socket anywhere in this deployment, so a command typed in Discord reaches
 * the SMP the way an update request already reaches the updater: a row, a {@code pg_notify}, and the
 * owning process listening. A request therefore survives a target that happens to be restarting -
 * which is not a corner case but the ordinary state of things during a deploy.
 *
 * <h2>Both ends are guarded, and they guard different things</h2>
 * <ul>
 *   <li>The <b>asker</b> writes the row, waits, and writes {@code EXPIRED} when it gives up. It
 *       cannot cancel work already running, and does not try: {@link #expire} only touches a row
 *       still {@code PENDING}.</li>
 *   <li>The <b>target</b> claims atomically, refuses anything already past its expiry, re-reads the
 *       admin flag, runs the command, and settles the row. It never writes {@code EXPIRED}, which
 *       is what makes that status mean exactly "nothing ever picked this up".</li>
 * </ul>
 *
 * <h2>What this interface deliberately does not do</h2>
 * It does not know what a command is. {@link #submit} takes a path and a line of arguments as
 * strings, because {@code Declaration} and {@code Values} live in {@code :commands} and
 * {@code :common} is compiled against no platform and no command model. The pairing between the
 * five {@code target} strings here and {@code Target}'s constants is held by a test in
 * {@code :commands}, the same way {@code SeasonPhase} is held against {@code season_phase.phase}.
 */
public interface CommandRequests extends AutoCloseable {

    /**
     * Write a request and wake its target.
     *
     * @return the row's id, to read the outcome back with
     */
    long submit(NewCommandRequest request);

    /**
     * Take the oldest request addressed to {@code target} that has not expired, and mark it running.
     *
     * <p>Call it in a loop until it answers empty: one notification can stand for several rows, and
     * a notification can be missed altogether, which is why the inbox polls as well.</p>
     *
     * @param target the caller's own {@code Target#name()} - never anybody else's
     */
    Optional<CommandRequest> claim(String target);

    /**
     * Settle a claimed request.
     *
     * @param id      the row
     * @param ok      whether the command ran; {@code false} records it as {@code FAILED}
     * @param result  what to show the asker, already rendered in their language
     */
    void finish(long id, boolean ok, String result);

    /**
     * Stop waiting for a request nothing has claimed.
     *
     * @return {@code true} when this call is what expired it - {@code false} means a target had
     *         already claimed the row and the answer is still coming
     */
    boolean expire(long id);

    /** What became of a request, or empty if there is no such row. */
    Optional<CommandOutcome> outcome(long id);

    /**
     * Deletes every settled request older than {@code days}, and answers how many.
     *
     * <h2>Who calls this, and when</h2>
     * The updater, once, at the start of {@code serve} - next to {@code settleOrphans} and for the
     * same kind of reason: it is a bounded piece of housekeeping that is safe exactly because
     * nothing else is running yet. It is deliberately <b>not</b> on a timer. docs/updater.md's first
     * rule for that process is that {@code serve} is not a scheduler, and a retention sweep is not a
     * good enough reason to make it one; a container that has not restarted for thirty days keeps
     * thirty-one days of rows, which is the trade.
     *
     * @param days the retention window - a settled row older than this is deleted
     * @return how many rows went
     */
    int deleteSettledOlderThan(int days);

    @Override
    void close();

    /**
     * Over a pool somebody else owns and closes.
     *
     * <p>Which is every process in this network: each already has one, and a second pool for the
     * command inbox would be a second set of connections held open for a table that is empty almost
     * all of the time.</p>
     */
    static CommandRequests borrowing(final DataSource dataSource) {
        return JdbiCommandRequests.borrowing(dataSource);
    }
}
