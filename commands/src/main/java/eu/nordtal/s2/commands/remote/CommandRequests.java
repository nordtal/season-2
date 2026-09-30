package eu.nordtal.s2.commands.remote;

import eu.nordtal.s2.commands.Target;
import eu.nordtal.s2.database.audit.AuditLine;
import java.time.Clock;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * A travelling command as the servers' inboxes carry it, one {@code COMMAND} row in the target's inbox.
 * {@link #over} is the one implementation, on the database's inbox; nothing here is a state machine of its own.
 */
public interface CommandRequests {

    /**
     * Writes a request into its target's inbox and wakes the target.
     *
     * @return the row's id, to read the outcome back with
     */
    long submit(NewCommandRequest request);

    /**
     * Writes a request and its journal line in one transaction.
     *
     * @return the row's id, to read the outcome back with
     */
    long submit(NewCommandRequest request, AuditLine journal);

    /** Claims the oldest due request in {@code target}'s own inbox; call it until empty. */
    Optional<CommandRequest> claim(Target target);

    /**
     * Settles a claimed request.
     *
     * @param ok     whether the command ran; {@code false} records it as {@code FAILED}
     * @param result what to show the asker, already rendered in their language
     */
    void finish(Target target, long id, boolean ok, String result);

    /**
     * Stops waiting for a request, which only reads it: a request past its expiry is never claimed.
     *
     * @return {@code true} when nothing claimed it; {@code false} means the target has it
     */
    boolean expire(Target target, long id);

    /** Returns what became of a request, or empty if there is no such row. */
    Optional<CommandOutcome> outcome(Target target, long id);

    /** Returns the requests over the servers' inboxes on a pool somebody else owns and closes. */
    static CommandRequests over(final DataSource dataSource, final Clock clock) {
        return new InboxCommandRequests(dataSource, clock);
    }
}
