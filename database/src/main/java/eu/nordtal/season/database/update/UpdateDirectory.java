package eu.nordtal.season.database.update;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.Refused;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;

/**
 * The run inbox of steward, for every process that can ask for a run.
 * A request is a row and an empty {@code pg_notify}. A wake-up re-reads the row.
 */
public interface UpdateDirectory {

    /** The time between asking for an update and the servers going down, which is also the cancel window. */
    Duration UPDATE_COUNTDOWN = Duration.ofSeconds(60);

    /** Returns a directory over {@code dataSource}. */
    static UpdateDirectory using(final DataSource dataSource) {
        return using(dataSource, () -> true);
    }

    /**
     * Returns a directory for code that can run ahead of the schema.
     *
     * @param current whether the schema has every table this code writes. Rows of a run wait for it
     */
    static UpdateDirectory using(final DataSource dataSource, final BooleanSupplier current) {
        return new JdbiUpdateDirectory(dataSource, current);
    }

    /**
     * Writes a request and announces it in the same statement.
     *
     * @param delay how long steward waits before claiming it. Negative means zero
     * @throws Refused with an {@link UpdateRefusal} when another run is open or a take-down names a held service
     */
    default UpdateRequest submit(final UpdateKind kind, final Actor actor, final @Nullable Duration delay) {
        return submit(kind, actor, delay, null);
    }

    /**
     * Writes a request for some services.
     *
     * @param services compose service names. Empty or {@code null} is the whole network
     */
    default UpdateRequest submit(
            final UpdateKind kind,
            final Actor actor,
            final @Nullable Duration delay,
            final java.util.@Nullable List<String> services) {
        return submit(kind.request(cleaned(services)), actor, delay);
    }

    /**
     * Writes a request of any kind.
     *
     * @throws Refused with an {@link UpdateRefusal} when another run is open or a take-down names a held service
     */
    UpdateRequest submit(
            eu.nordtal.season.database.inbox.StewardRequest request, Actor actor, @Nullable Duration delay);

    /**
     * Writes a finished run beside any open one, for the install when steward-agent starts.
     *
     * @param status {@link UpdateStatus#DONE} or {@link UpdateStatus#FAILED}
     * @param took how long it ran, which puts its start that far before now
     * @throws IllegalArgumentException if {@code status} is another one
     */
    default UpdateRequest record(
            final eu.nordtal.season.database.inbox.StewardRequest request,
            final Actor actor,
            final UpdateStatus status,
            final String result,
            final Duration took) {
        throw new UnsupportedOperationException("this directory cannot write a run: " + request);
    }

    /** Returns the request a run was asked as, or empty for an unknown id. */
    default Optional<eu.nordtal.season.database.inbox.StewardRequest> requestOf(final long id) {
        return Optional.empty();
    }

    /** Returns the service names trimmed, without blanks and duplicates. {@code null} gives none. */
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

    /** Returns the services of a run, empty for the whole network and for an unknown id. */
    default java.util.List<String> scopeOf(final long id) {
        return java.util.List.of();
    }

    /** Returns the held services, newest first. */
    default java.util.List<ServiceHold> holds() {
        return java.util.List.of();
    }

    /** Returns whether the service is held down. */
    default boolean isHeld(final String service) {
        return holds().stream().anyMatch(hold -> hold.service().equals(service));
    }

    /** Writes a hold, or refreshes the existing one. The default throws. */
    default void hold(final String service, final Actor heldBy, final @Nullable Long requestId) {
        throw new UnsupportedOperationException("this directory cannot hold a service down: " + service);
    }

    /** Removes the hold, if there is one. */
    default void release(final String service) {
        throw new UnsupportedOperationException("this directory cannot release a service: " + service);
    }

    /** Returns the request with the id, or empty if it was deleted. */
    Optional<UpdateRequest> find(long id);

    /**
     * Returns the newest requests first.
     *
     * @param limit the page size, at least 1
     */
    java.util.List<UpdateRequest> recent(int limit);

    /** Returns every request after the id, oldest first. */
    java.util.List<UpdateRequest> since(long id);

    /** Returns the highest id, or zero. */
    long latestId();

    /** Returns every request that finished within the window. */
    java.util.List<UpdateRequest> finishedWithin(Duration window);

    /**
     * Returns the newest backup that is {@code DONE} and saved a volume.
     *
     * @param within how far back to look
     */
    Optional<UpdateRequest> lastSuccessfulBackup(Duration within);

    /** Marks the oldest due request as running and returns it. Only steward calls this. */
    Optional<UpdateRequest> claimNext();

    /**
     * Writes the answer to a claimed request. Only steward calls this.
     *
     * @param status {@link UpdateStatus#DONE} or {@link UpdateStatus#FAILED}
     * @return the finished row, or empty if it was not running
     * @throws IllegalArgumentException if {@code status} is not final
     */
    Optional<UpdateRequest> finish(long id, UpdateStatus status, String result);

    /** Rewrites the report of a running request and returns whether a running row was updated. */
    boolean progress(long id, String result);

    /**
     * Starts the countdown on a claimed request.
     *
     * @param moving the services the run stops
     * @return the row with its {@code countdown_end}, or empty if it was cancelled
     */
    Optional<UpdateRequest> startCountdown(long id, Duration length, java.util.Collection<String> moving);

    /** Ends the countdown and returns whether the run may go ahead, false after a cancel. Only steward calls this. */
    boolean commitCountdown(long id);

    /** Returns the outage counting down, or empty. */
    Optional<UpdateRequest> countingDown();

    /** Returns the run whose servers are going down, or empty. */
    Optional<UpdateRequest> running();

    /** Returns the open run, {@code PENDING} or {@code RUNNING}, or empty. */
    default Optional<UpdateRequest> open() {
        return Optional.empty();
    }

    /** Withdraws the running countdown and returns the cancelled row, or empty after it ran out. */
    Optional<UpdateRequest> cancelCountdown();

    /** Returns when the next pending request is due, or empty. */
    Optional<Instant> nextDue();

    /**
     * Fails every {@code RUNNING} row except a run whose container still runs, at startup.
     *
     * @param why the note written into the failed report
     * @param stillRunning whether the daemon still runs the container with that name
     * @return how many rows it failed
     */
    int settleOrphans(MessageRef why, java.util.function.Predicate<String> stillRunning);

    /**
     * Gives a running request to a temporary container that finishes it. The default gives nothing.
     *
     * @return whether a running row took the name
     */
    default boolean handOver(final long id, final String runner) {
        return false;
    }

    /** Returns the container of a running request, or empty. */
    default Optional<String> runnerOf(final long id) {
        return Optional.empty();
    }

    /** Returns the container of the open run, or empty. */
    default Optional<String> handedTo() {
        return Optional.empty();
    }

    /** Returns one row as JSON for a restore, empty for an unknown id. */
    default Optional<String> carry(final long id) {
        return Optional.empty();
    }

    /**
     * After a restore, fails every row the dump held open and writes the saved row back.
     *
     * @param row what {@link #carry} returned before the restore
     */
    default void putBack(final String row, final MessageRef why) {
        throw new UnsupportedOperationException("this directory cannot put a row back");
    }

    /** Returns the steps of the run in the order they began, none when the database cannot say. */
    default List<RunProgress.Step> stepsOf(final UpdateRequest run) {
        return List.of();
    }

    /** Returns the stage and the steps of the run with their facts, nothing when the database cannot say. */
    default RunRecord recordOf(final long run) {
        return RunRecord.EMPTY;
    }

    /** Returns where the steps and facts of a run are written. */
    default RunRecorder recorder() {
        return RunRecorder.NONE;
    }
}
