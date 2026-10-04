package eu.nordtal.s2.database.update;

import eu.nordtal.s2.common.id.Actor;
import eu.nordtal.s2.messages.MessageRef;
import eu.nordtal.s2.messages.Refused;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;

/**
 * steward's inbox, as seen by every process that can ask for a run.
 *
 * A request is a row plus an empty {@code pg_notify}; the notification is never the state, so a wake-up re-reads.
 */
public interface UpdateDirectory {

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
     * @param kind  what to do
     * @param actor who is asking
     * @param delay how long steward must wait before claiming it; negative means zero
     * @return the row as written
     * @throws Refused with an {@link UpdateRefusal} when another run is open anywhere, or a take-down names a
     *     held service
     */
    default UpdateRequest submit(final UpdateKind kind, final Actor actor, final @Nullable Duration delay) {
        return submit(kind, actor, delay, null);
    }

    /**
     * The same, for a run that is only for some of the services.
     *
     * @param services compose service names; empty or {@code null} is the whole network
     */
    default UpdateRequest submit(
            final UpdateKind kind,
            final Actor actor,
            final @Nullable Duration delay,
            final java.util.@Nullable List<String> services) {
        return submit(kind.request(cleaned(services)), actor, delay);
    }

    /**
     * The same, for a request of any kind, the ones that name more than their services included.
     *
     * @throws Refused with an {@link UpdateRefusal} when another run is open anywhere, or a take-down names a
     *     held service
     */
    UpdateRequest submit(eu.nordtal.s2.database.inbox.StewardRequest request, Actor actor, @Nullable Duration delay);

    /** Returns the request a run was asked as, or empty for an unknown id. */
    default Optional<eu.nordtal.s2.database.inbox.StewardRequest> requestOf(final long id) {
        return Optional.empty();
    }

    /** Returns service names trimmed, without blanks and duplicates, in the order given; {@code null} is none. */
    static java.util.List<String> cleaned(final java.util.@Nullable List<String> services) {
        if (services == null) {
            return java.util.List.of();
        }
        return services.stream()
                .filter(java.util.Objects::nonNull)
                .map(String::strip)
                .filter(service -> !service.isEmpty())
                .distinct()
                .toList();
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
    default void hold(final String service, final Actor heldBy, final @Nullable Long requestId) {
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
     * Takes the oldest due request and marks it running; only steward calls this.
     *
     * @return the claimed request, or empty when nothing is due
     */
    Optional<UpdateRequest> claimNext();

    /**
     * Writes the answer to a claimed request; only steward calls this.
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
     * Starts the countdown on a claimed request, once steward knows the plan has work in it.
     *
     * @param id     the claimed request
     * @param length how long the countdown runs, from now on the database's clock
     * @param moving the services the run stops, which the proxy evacuates once the countdown runs out
     * @return the row with its {@code countdown_end}, or empty when it was cancelled since the claim
     */
    Optional<UpdateRequest> startCountdown(long id, Duration length, java.util.Collection<String> moving);

    /**
     * Ends the countdown in one statement that decides the race with a cancel at zero; only steward calls this.
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
     * Withdraws the countdown that is running; its report says {@code CANCELLED}, and the journal says who.
     *
     * @return the cancelled row, or empty when the countdown had already run out
     */
    Optional<UpdateRequest> cancelCountdown();

    /** Returns when the next pending request becomes due, or empty when there is none. */
    Optional<Instant> nextDue();

    /**
     * Fails everything left {@code RUNNING} but a run handed to a container that still runs; once, at startup.
     *
     * @param why the one note of the failed report written into those rows
     * @param stillRunning asks the daemon whether the container a run was handed to still runs
     * @return how many there were
     */
    int settleOrphans(MessageRef why, java.util.function.Predicate<String> stillRunning);

    /**
     * Hands a running request to a one-shot container, which settles it; the default hands nothing over.
     *
     * @param runner the container's name, which {@link #settleOrphans} asks the daemon about
     * @return whether a running row took the name
     */
    default boolean handOver(final long id, final String runner) {
        return false;
    }

    /** Returns the container a running request was handed to, or empty; the default knows of none. */
    default Optional<String> runnerOf(final long id) {
        return Optional.empty();
    }

    /** Returns the container the open run is handed to, or empty; the default knows of none. */
    default Optional<String> handedTo() {
        return Optional.empty();
    }

    /**
     * Returns one row whole, as JSON, so a database restore can put it back; empty for an unknown id.
     *
     * The default carries nothing, for a directory that cannot write.
     */
    default Optional<String> carry(final long id) {
        return Optional.empty();
    }

    /**
     * After a restore: fails every row the dump held open, then writes the carried row back and counts on from it.
     *
     * @param row what {@link #carry} returned before the restore
     * @param why the one note of the failed report written into the rows the dump held open
     */
    default void putBack(final String row, final MessageRef why) {
        throw new UnsupportedOperationException("this directory cannot put a row back");
    }
}
