package eu.nordtal.s2.common.access;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;

/**
 * The bot's inbox for access changes, carried out by the only process holding a Discord session.
 *
 * A request is a row plus a {@code pg_notify}, so it survives a restarting bot; the poll is the guarantee.
 * Every row expires: {@link #claim()} refuses a row past it and {@link #outcome(long)} writes {@code EXPIRED}.
 */
public interface AccessRequests {

    /**
     * How long a request waits for the bot before it is given up on.
     *
     * Two minutes, the same patience {@code command_request} has. It is a constant rather than a
     * setting for the reason {@code Channels} gives about its own: nothing is gained by making it
     * settable, and what is lost is being able to tell a misconfigured deployment from a working
     * one. A surface that needs its own can pass one to {@link #submit(NewAccessRequest, Duration)}.
     */
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

        /** The two that do - days for a grant, seconds for a play time. */
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

    /** Write a request and wake the bot. */
    AccessRequest submit(NewAccessRequest request);

    /** The same, with a patience of this caller's own instead of {@link #PATIENCE}. */
    AccessRequest submit(NewAccessRequest request, Duration patience);

    /** Claims the oldest unexpired request; call it until empty, as one notification may stand for several. */
    Optional<AccessRequest> claim();

    /**
     * Settle a claimed request.
     *
     * @param ok     whether it was carried out; {@code false} records it as {@code FAILED}
     * @param result what happened, as JSON - the row carries its own answer so that no surface
     *               composes a second rendering of it
     */
    void finish(long id, boolean ok, String result);

    /**
     * What became of a request, or empty if there is no such row.
     *
     * Expires the overdue first, which is what makes a row stop waiting when the bot is not
     * running at all.
     */
    Optional<AccessRequest> outcome(long id);

    /** Every row still waiting, oldest first - what a listener reads on every wake-up. */
    List<AccessRequest> pending();

    /**
     * Give up on every pending row whose patience has run out.
     *
     * @return how many rows this call expired
     */
    int expireDue();

    /**
     * Delete every settled request older than {@code age}.
     *
     * @return how many rows went
     */
    int purge(Duration age);
}
