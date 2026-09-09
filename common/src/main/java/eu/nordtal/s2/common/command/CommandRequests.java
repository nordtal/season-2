package eu.nordtal.s2.common.command;

import javax.sql.DataSource;

import java.util.Optional;

/**
 * The inbox every process shares: one admin command, addressed to the JVM that can carry it out.
 *
 * <p>The processes share nothing but one PostgreSQL - there is no socket between them - so a command
 * is a row plus a {@code pg_notify} that the owning process listens for. A request therefore
 * survives a target that happens to be restarting.
 *
 * <p>The asker writes the row, waits, and writes {@code EXPIRED} when it gives up; {@link #expire}
 * only touches a row still {@code PENDING}, so it can never cancel work already running. The target
 * claims atomically, refuses anything past its expiry, re-reads the admin flag, runs the command and
 * settles the row - it never writes {@code EXPIRED}, which is what makes that status mean exactly
 * "nothing ever picked this up".
 *
 * <p>It does not know what a command is: {@link #submit} takes a path and an argument line as
 * strings, because {@code :common} is compiled against no platform and no command model.
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
     * Call it in a loop until it answers empty: one notification can stand for several rows, and a
     * notification can be missed altogether, which is why the inbox polls as well.
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
     * <p>Called once by the updater at the start of {@code serve}, where nothing else is running
     * yet. Deliberately not on a timer, so a container that has not restarted keeps its rows longer
     * than the window.
     *
     * @param days the retention window - a settled row older than this is deleted
     * @return how many rows went
     */
    int deleteSettledOlderThan(int days);

    @Override
    void close();

    /**
     * Over a pool somebody else owns and closes - every process in this network already has one, and
     * a second pool would hold connections open for a table that is almost always empty.
     */
    static CommandRequests borrowing(final DataSource dataSource) {
        return JdbiCommandRequests.borrowing(dataSource);
    }
}
