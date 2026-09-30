package eu.nordtal.s2.database.access;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;

/**
 * The bot's inbox for access changes, carried out by the only process holding a Discord session.
 * A request is a row plus a {@code pg_notify}; every row expires, and the poll is the guarantee.
 */
public interface AccessRequests {

    /** How long a request waits for the bot before it is given up on. */
    Duration PATIENCE = Duration.ofMinutes(2);

    /** What a surface hands in. */
    record NewAccessRequest(
            AccessRequestKind kind,
            String subject,
            @Nullable String argument,
            AccessRequestSource source,
            String requestedBy) {

        /** The three kinds that need no argument. */
        public static NewAccessRequest of(
                final AccessRequestKind kind,
                final String subject,
                final AccessRequestSource source,
                final String requestedBy) {
            return new NewAccessRequest(kind, subject, null, source, requestedBy);
        }

        /** The two that do: days for a grant, seconds for a play time. */
        public static NewAccessRequest of(
                final AccessRequestKind kind,
                final String subject,
                final long argument,
                final AccessRequestSource source,
                final String requestedBy) {
            return new NewAccessRequest(kind, subject, String.valueOf(argument), source, requestedBy);
        }
    }

    /** Borrows the pool it is given and owns nothing; the process that built it closes it. */
    static AccessRequests on(final DataSource dataSource) {
        return new JdbiAccessRequests(dataSource);
    }

    /** Writes a request and wakes the bot. */
    AccessRequest submit(NewAccessRequest request);

    /** Writes a request with a patience of this caller's own instead of {@link #PATIENCE}. */
    AccessRequest submit(NewAccessRequest request, Duration patience);

    /** Claims the oldest unexpired request; call it until empty, as one notification may stand for several. */
    Optional<AccessRequest> claim();

    /**
     * Settles a claimed request.
     *
     * @param ok whether it was carried out; {@code false} records it as {@code FAILED}
     * @param result what happened, as JSON, so no surface composes a second rendering of it
     */
    void finish(long id, boolean ok, String result);

    /**
     * Returns what became of a request, or empty if there is no such row.
     * It expires the overdue first, so a row stops waiting even when the bot is down.
     */
    Optional<AccessRequest> outcome(long id);

    /** Returns every row still waiting, oldest first, which a listener reads on every wake-up. */
    List<AccessRequest> pending();

    /**
     * Gives up on every pending row whose patience has run out.
     *
     * @return how many rows this call expired
     */
    int expireDue();

    /**
     * Deletes every settled request older than {@code age}.
     *
     * @return how many rows went
     */
    int purge(Duration age);
}
