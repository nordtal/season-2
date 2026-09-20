package eu.nordtal.s2.common.access;

import javax.sql.DataSource;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * The bot's inbox for access changes: one row per request, carried out by the one process that can
 * carry it out (season-2-community/08).
 *
 * <h2>Why every surface asks instead of doing</h2>
 * Granting access is four things at once - a row, a Discord role, a direct message in the
 * recipient's own language, and a line in the admin channel - and only the bot holds a JDA session.
 * Until this existed, {@code POST /api/access/grant} wrote the row and silently skipped the other
 * three: somebody granted access through steward was never told. One executor is the only shape in
 * which "grant" means the same thing from every surface.
 *
 * <h2>A row, not a call</h2>
 * The processes share one PostgreSQL and nothing else, so the request is a row plus a
 * {@code pg_notify} the bot listens for - and therefore survives a bot that happens to be
 * restarting, which is the case an HTTP call cannot survive. The poll is the guarantee; the
 * notification only makes it immediate, and a reconnecting listener re-reads {@link #pending()} in
 * full because a notification is delivered once and lost while nobody is connected.
 *
 * <h2>The patience</h2>
 * A bot that is never coming back and a bot that is busy look identical from the outside. Every row
 * therefore carries an expiry, and the two halves of it never meet: {@link #claim()} refuses a row
 * past it, and {@link #outcome(long)} is what writes {@code EXPIRED}. That is deliberate - the case
 * this exists for is a bot that is not running, so the bot cannot be the one to notice.
 */
public interface AccessRequests {

    /**
     * How long a request waits for the bot before it is given up on.
     *
     * <p>Two minutes, the same patience {@code command_request} has. It is a constant rather than a
     * setting for the reason {@code Channels} gives about its own: nothing is gained by making it
     * settable, and what is lost is being able to tell a misconfigured deployment from a working
     * one. A surface that needs its own can pass one to {@link #submit(NewAccessRequest, Duration)}.
     */
    Duration PATIENCE = Duration.ofMinutes(2);

    /** What a surface hands in. */
    record NewAccessRequest(AccessRequestKind kind,
                            String subject,
                            String argument,
                            AccessRequestSource source,
                            String requestedBy) {

        /** The three kinds that need no argument. */
        public static NewAccessRequest of(final AccessRequestKind kind, final String subject,
                                          final AccessRequestSource source,
                                          final String requestedBy) {
            return new NewAccessRequest(kind, subject, null, source, requestedBy);
        }

        /** The two that do - days for a grant, seconds for a play time. */
        public static NewAccessRequest of(final AccessRequestKind kind, final String subject,
                                          final long argument,
                                          final AccessRequestSource source,
                                          final String requestedBy) {
            return new NewAccessRequest(kind, subject, String.valueOf(argument), source,
                    requestedBy);
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

    /**
     * Take the oldest request that has not expired, and mark it running. Call it in a loop until it
     * answers empty: one notification can stand for several rows, and a notification can be missed
     * altogether, which is why the inbox polls as well.
     */
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
     * <p>Expires the overdue first, which is what makes a row stop waiting when the bot is not
     * running at all.</p>
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
