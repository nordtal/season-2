package eu.nordtal.s2.common.command;

import eu.nordtal.s2.common.audit.AuditLine;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * The inbox every process shares: one admin command, addressed to the JVM that can carry it out.
 * Only the asker writes {@code EXPIRED}, on a row still {@code PENDING}, so it means nothing picked the row up.
 */
public interface CommandRequests extends AutoCloseable {

    /**
     * Writes a request and wakes its target.
     *
     * @return the row's id, to read the outcome back with
     */
    long submit(NewCommandRequest request);

    /**
     * Writes a request, its journal line and the wake-up as one statement, so they commit together or not at all.
     *
     * @param request the request, exactly as {@link #submit(NewCommandRequest)} takes it
     * @param journal the line to write beside it
     * @return the row's id, to read the outcome back with
     */
    long submit(NewCommandRequest request, AuditLine journal);

    /**
     * Claims the oldest unexpired request addressed to {@code target} and marks it running.
     * Call it until it answers empty: one notification can stand for several rows.
     *
     * @param target the caller's own {@code Target#name()}, never anybody else's
     */
    Optional<CommandRequest> claim(String target);

    /**
     * Settles a claimed request.
     *
     * @param id      the row
     * @param ok      whether the command ran; {@code false} records it as {@code FAILED}
     * @param result  what to show the asker, already rendered in their language
     */
    void finish(long id, boolean ok, String result);

    /**
     * Stops waiting for a request nothing has claimed.
     *
     * @return {@code true} when this call expired it; {@code false} means a target already claimed it
     */
    boolean expire(long id);

    /** Returns what became of a request, or empty if there is no such row. */
    Optional<CommandOutcome> outcome(long id);

    /**
     * Deletes every settled request older than {@code days} and answers how many.
     * It runs once at the start of {@code serve}, not on a timer.
     */
    int deleteSettledOlderThan(int days);

    @Override
    void close();

    /** Returns requests over a pool somebody else owns and closes. */
    static CommandRequests borrowing(final DataSource dataSource) {
        return JdbiCommandRequests.borrowing(dataSource);
    }
}
