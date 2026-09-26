package eu.nordtal.s2.common.command;

import eu.nordtal.s2.common.audit.AuditLine;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * The inbox every process shares: one admin command, addressed to the JVM that can carry it out.
 *
 * The processes share nothing but one PostgreSQL - there is no socket between them - so a command
 * is a row plus a {@code pg_notify} that the owning process listens for. A request therefore
 * survives a target that happens to be restarting.
 *
 * The asker writes the row, waits, and writes {@code EXPIRED} when it gives up; {@link #expire}
 * only touches a row still {@code PENDING}, so it can never cancel work already running. The target
 * claims atomically, refuses anything past its expiry, re-reads the admin flag, runs the command and
 * settles the row - it never writes {@code EXPIRED}, which is what makes that status mean exactly
 * "nothing ever picked this up".
 *
 * It does not know what a command is: {@link #submit} takes a path and an argument line as
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
     * Write a request, its journal line and the wake-up as one statement.
     *
     * For a surface that has to record who asked: the row and the line commit together or
     * neither does. Writing the row first and the journal second is the ordinary rule everywhere
     * else in this schema (see {@code AuditDirectory#record}), and it is the wrong rule here -
     * this table is not a record of something that happened, it is work somebody is about to do.
     * A committed row whose journal line failed is a command that runs while its asker is being
     * told it did not, and what an operator does when told that is press the button again.
     *
     * @param request the request, exactly as {@link #submit(NewCommandRequest)} takes it
     * @param journal the line to write beside it
     * @return the row's id, to read the outcome back with
     */
    long submit(NewCommandRequest request, AuditLine journal);

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
     * Called once by steward-worker at the start of {@code serve}, where nothing else is running
     * yet. Deliberately not on a timer, so a container that has not restarted keeps its rows longer
     * than the window.
     *
     * @param days the retention window - a settled row older than this is deleted
     * @return how many rows went
     */
    int deleteSettledOlderThan(int days);

    @Override
    void close();

    /** Returns requests over a pool somebody else owns and closes. */
    static CommandRequests borrowing(final DataSource dataSource) {
        return JdbiCommandRequests.borrowing(dataSource);
    }
}
