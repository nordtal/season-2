package eu.nordtal.s2.database.update;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;

/**
 * steward-worker's inbox, as seen by every process that can ask for a run.
 *
 * A request is a row plus an empty {@code pg_notify}; the notification is never the state, so readers poll.
 */
public interface UpdateDirectory {

    /** The PostgreSQL channel every request is announced on, an alias for {@code Channels#UPDATE}. */
    String CHANNEL = eu.nordtal.s2.database.notify.Channels.UPDATE;

    /**
     * How long between an update being asked for and the servers going down, which is also the cancel window.
     *
     * It moves together with {@code Countdown.CHAT_THRESHOLDS}; a threshold above it is never heard.
     */
    Duration UPDATE_COUNTDOWN = Duration.ofSeconds(60);

    /** Returns a directory over {@code dataSource}; it holds no resource of its own. */
    static UpdateDirectory using(final DataSource dataSource) {
        return new JdbiUpdateDirectory(dataSource);
    }

    /**
     * Asks for something to happen, and announces it in the same statement.
     *
     * @param kind        what to do
     * @param source      which surface is asking
     * @param requestedBy a Discord id, a Minecraft name, or {@code null} for the console
     * @param delay       how long the worker must wait; {@link Duration#ZERO} in practice, negative means zero
     * @return the row as written
     * @throws RunRefused when another run is open anywhere, or a take-down names a held service
     */
    UpdateRequest submit(UpdateKind kind, UpdateSource source, @Nullable String requestedBy, @Nullable Duration delay);

    /**
     * The same, for a run that is only for some of the services.
     *
     * @param services compose service names; empty or {@code null} is the whole network
     */
    default UpdateRequest submit(
            final UpdateKind kind,
            final UpdateSource source,
            final @Nullable String requestedBy,
            final @Nullable Duration delay,
            final java.util.@Nullable List<String> services) {
        // A default so that a directory without scope support, such as a test fake, keeps working.
        return submit(kind, source, requestedBy, delay);
    }

    /** Returns which services a run is for, or empty for the whole network and for an unknown id. */
    default java.util.List<String> scopeOf(final long id) {
        return java.util.List.of();
    }

    /** Returns every service being held down on purpose, newest first; usually none. */
    default java.util.List<ServiceHold> holds() {
        return java.util.List.of();
    }

    /** Returns whether that one service is being held down. */
    default boolean isHeld(final String service) {
        return holds().stream().anyMatch(hold -> hold.service().equals(service));
    }

    /**
     * Writes a hold, or refreshes the one already there.
     *
     * The default throws, so a directory that cannot write never pretends it did.
     */
    default void hold(final String service, final @Nullable String heldBy, final @Nullable Long requestId) {
        throw new UnsupportedOperationException("this directory cannot hold a service down: " + service);
    }

    /** Takes the hold off, if there is one; doing it twice is not an error. */
    default void release(final String service) {
        throw new UnsupportedOperationException("this directory cannot release a service: " + service);
    }

    /**
     * Reads a request back.
     *
     * @param id what {@link #submit} returned
     * @return the row, or empty if it has been deleted by hand
     */
    Optional<UpdateRequest> find(long id);

    /**
     * Returns the most recent requests, newest first.
     *
     * @param limit the page size; a number below 1 is clamped to 1
     */
    java.util.List<UpdateRequest> recent(int limit);

    /**
     * Returns every request written after the one named, oldest first.
     *
     * @param id the last one already drawn; {@code 0} for everything
     */
    java.util.List<UpdateRequest> since(long id);

    /** Returns the highest id there is, or zero; where a feed starts, so a restart posts no history. */
    long latestId();

    /** Returns every request that finished within the window, including ones below {@link #latestId()}. */
    java.util.List<UpdateRequest> finishedWithin(Duration window);

    /**
     * Returns the most recent backup that finished {@code DONE} and saved at least one volume; blocking.
     *
     * @param within how far back to look; hours, so yesterday's backup cannot authorise today's reset
     * @return the run, or empty when there was none, it failed or it saved nothing
     */
    Optional<UpdateRequest> lastSuccessfulBackup(Duration within);

    /**
     * Takes the oldest due request and marks it running; only steward-worker calls this.
     *
     * @return the claimed request, or empty when nothing is due
     */
    Optional<UpdateRequest> claimNext();

    /**
     * Writes the answer to a claimed request; only the worker calls this.
     *
     * @param id     the row
     * @param status {@link UpdateStatus#DONE} or {@link UpdateStatus#FAILED}
     * @param result the report, verbatim
     * @return the finished row, or empty if it was not running any more
     * @throws IllegalArgumentException if {@code status} is not a terminal one
     */
    Optional<UpdateRequest> finish(long id, UpdateStatus status, String result);

    /**
     * Rewrites a running request's report without settling it; a settled request is left alone.
     *
     * @param result the report so far, as JSON
     * @return whether a running row was updated
     */
    boolean progress(long id, String result);

    /**
     * Returns a running request to the inbox, for the newer steward-worker the run installed to claim.
     *
     * @return whether a running row was handed over
     */
    default boolean handOver(final long id, final String result) {
        throw new UnsupportedOperationException("this directory cannot hand a run over: " + id);
    }

    /**
     * Starts the countdown on a claimed request, once the worker knows the plan has work in it.
     *
     * @param id     the claimed request
     * @param length how long the countdown runs, from now on the database's clock
     * @return the row with its new {@code not_before}, or empty when it was cancelled since the claim
     */
    Optional<UpdateRequest> startCountdown(long id, Duration length);

    /**
     * Ends the countdown in one statement that decides the race with a cancel at zero; only the worker calls this.
     *
     * @return {@code true} when the run may go ahead, {@code false} when somebody cancelled
     */
    boolean commitCountdown(long id);

    /** Returns the outage counting down right now, which proxy shows, or empty. */
    Optional<UpdateRequest> countingDown();

    /** Returns the run that is happening right now: claimed, past its countdown, servers down. */
    Optional<UpdateRequest> running();

    /** Returns the one open run, {@code PENDING} or {@code RUNNING}, or empty. */
    default Optional<UpdateRequest> open() {
        return Optional.empty();
    }

    /**
     * Withdraws the countdown that is running.
     *
     * @param reason what to record, naming who cancelled
     * @return the cancelled row, or empty when the countdown had already run out
     */
    Optional<UpdateRequest> cancelCountdown(String reason);

    /** Returns when the next pending request becomes due, or empty when there is none. */
    Optional<Instant> nextDue();

    /**
     * Fails everything left {@code RUNNING}, since only one worker serves; only it calls this, once, at startup.
     *
     * @param failed what to write into those rows
     * @return how many there were
     */
    int settleOrphans(String failed);
}
