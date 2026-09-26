package eu.nordtal.s2.common.update;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;

/**
 * steward-worker's inbox, as seen by every process that can ask for a run.
 *
 * The worker is a separate container and nothing in this deployment can call it - there is no
 * socket between the processes - so a request travels through the one PostgreSQL they share: a row,
 * a {@code pg_notify}, and the worker listening. A request therefore survives a worker that
 * happens to be restarting.
 *
 * <b>The notification is never the state.</b> Notifications are lost while a process is
 * disconnected, so every reader polls as its guarantee; the payload is empty so there is nothing to
 * be tempted by.
 *
 * Nothing here names Paper, Velocity, JDA, JDBI or HikariCP: the factory takes a
 * {@link DataSource} and every process hands in the pool it already owns.
 */
public interface UpdateDirectory {

    /** The PostgreSQL channel every request is announced on, an alias for {@code Channels#UPDATE}. */
    String CHANNEL = eu.nordtal.s2.common.notify.Channels.UPDATE;

    /**
     * How long between an update being asked for and the servers going down.
     *
     * A constant rather than a setting: the worker starts the countdown and the proxy renders it
     * to every player, and two containers configured separately would eventually disagree. The
     * instant itself travels on the row ({@code not_before}), so it is never computed twice.
     *
     * It is also the cancel window - a cancel works right up until {@link #commitCountdown} takes
     * the row - and it has to be long enough for the backends' own tidying up at zero.
     *
     * <b>Why sixty seconds is speakable</b>
     *
     * The row carries the absolute instant the run happens, the proxy plans its beats against that
     * instant, and sixty seconds is as speakable as any other number as long as the worker sets
     * that instant far enough out.
     *
     * <b>This number and {@code Countdown.CHAT_THRESHOLDS} move together or not at all.</b> A
     * threshold above this value is a line nobody ever hears.
     */
    Duration UPDATE_COUNTDOWN = Duration.ofSeconds(60);

    /**
     * @param dataSource the pool - the same one this process already reads access or the phase
     *                   through
     * @return a directory over that pool. Holds no resource of its own, so there is nothing to close
     */
    static UpdateDirectory using(final DataSource dataSource) {
        return new JdbiUpdateDirectory(dataSource);
    }

    /**
     * Asks for something to happen, and announces it in the same statement.
     *
     * @param kind        what to do
     * @param source      which surface is asking
     * @param requestedBy a Discord id, a Minecraft name, or {@code null} for the console
     * @param delay       how long the worker must wait before acting. {@link Duration#ZERO} for
     *                    every kind: the countdown is started by {@link #startCountdown} once the
     *                    worker knows there is work to do, so a request that finds nothing new
     *                    never counts anything down. Negative is treated as zero
     * @return the row as written, with the id to read the answer back by
     * @throws RunRefused when another run is pending or running anywhere in the network, or a
     *                    take-down names a service that is already held - one run at a time,
     *                    decided here because every source submits through here
     */
    UpdateRequest submit(UpdateKind kind, UpdateSource source, @Nullable String requestedBy, @Nullable Duration delay);

    /**
     * The same, for a run that is only for some of the services.
     *
     * <b>A scoped run is not an abbreviated one</b>
     *
     * It resolves, counts down, parks the players in limbo, waits for health and writes the same
     * report. The only thing that is smaller is what it touches - which is the whole reason this is
     * a value on the row rather than a second code path: one sequence, one report, one set of
     * things that can go wrong.
     *
     * @param services compose service names. <b>Empty or {@code null} is the whole network</b>, the
     *                 same thing {@link #submit} asks for - not "no services", which would be a run
     *                 that stops nothing while claiming to be scoped
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

    /**
     * Which services a run is for.
     *
     * <b>Why it is read separately rather than sitting on {@link UpdateRequest}</b>
     *
     * Exactly one process asks this question - steward-worker, once, when it has claimed the row -
     * and {@code UpdateRequest} is constructed in twenty-two places across six modules, every one
     * of which would have gained a parameter it never reads. The column is on the row; the record
     * is the shape everybody passes around. Those are allowed to be different.
     *
     * @param id a request id
     * @return the services, or <b>empty for the whole network</b> - which is also what a row
     *         written before this column existed says, and what an id that no longer exists says.
     *         All three mean "do not narrow anything", which is the safe direction: the worst case
     *         is a run that does what runs have always done
     */
    default java.util.List<String> scopeOf(final long id) {
        return java.util.List.of();
    }

    /**
     * Every service that is being held down on purpose.
     *
     * Read by anything that is about to start a container: a run that brings a network back up
     * must not bring back the one service somebody stopped in order to work on it. Also read by the
     * interface, which is the only way it can draw "down because somebody said so" differently from
     * "down because it fell over" - the container runtime cannot tell those apart.
     *
     * @return the holds, newest first; <b>empty is the ordinary case</b> and is what a directory
     *         that knows nothing about holds answers, which is the safe direction: the worst case
     *         is a run that does what runs have always done
     */
    default java.util.List<ServiceHold> holds() {
        return java.util.List.of();
    }

    /** @return whether that one service is being held down */
    default boolean isHeld(final String service) {
        return holds().stream().anyMatch(hold -> hold.service().equals(service));
    }

    /**
     * Writes a hold, or refreshes the one already there.
     *
     * Unlike {@link #holds()} this has no harmless default: a directory that cannot write a hold
     * and pretends it did leaves a service stopped with nothing saying why, and the next run starts
     * it again. Loud beats silent, so the default throws and the two directories that can do this
     * override it.
     */
    default void hold(final String service, final @Nullable String heldBy, final @Nullable Long requestId) {
        throw new UnsupportedOperationException("this directory cannot hold a service down: " + service);
    }

    /** Takes the hold off, if there is one. Doing it twice is not an error. */
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
     * The most recent requests, newest first - what the interface's list of runs is drawn from.
     *
     * A number below 1 is clamped to 1, exactly as {@code AuditDirectory#recent} clamps it - a
     * caller that computed a page size down to zero wants the newest run, not an empty table with
     * no explanation in it. It was already the behaviour; saying so here is the point, because a
     * clamp nobody documents is a promise of "at most {@code limit} rows" that the implementation
     * quietly does not keep.
     *
     * @param limit how many, at most. A screenful; this is a page, not an export. Below 1 is 1
     */
    java.util.List<UpdateRequest> recent(int limit);

    /**
     * Returns every request written after the one named, oldest first.
     *
     * @param id the last one already drawn; {@code 0} for everything there is
     */
    java.util.List<UpdateRequest> since(long id);

    /** The highest id there is, or zero - where a feed starts, so a restart posts no history. */
    long latestId();

    /** Returns every request that finished within the given window, including ones below {@link #latestId()}. */
    java.util.List<UpdateRequest> finishedWithin(Duration window);

    /**
     * Returns the most recent nightly backup that finished, succeeded and demonstrably saved something.
     *
     * Successful means {@code kind = 'BACKUP'}, {@code status = 'DONE'}, a result that parses as an
     * {@link UpdateReport} at {@link UpdateReport.Stage#DONE}, and {@link UpdateReport#savedSomething()}.
     * Which volume was saved is not checked. A blocking database call: never call it from the server thread.
     *
     * @param within how far back to look; hours, so yesterday's backup cannot authorise today's reset
     * @return the run, or empty when there was none, it failed or it saved nothing
     */
    Optional<UpdateRequest> lastSuccessfulBackup(Duration within);

    /**
     * Takes the oldest due request and marks it running. <b>Only steward-worker calls this.</b>
     *
     * @return the claimed request, or empty when nothing is due
     */
    Optional<UpdateRequest> claimNext();

    /**
     * Writes the answer to a claimed request. <b>Only the worker calls this.</b>
     *
     * @param id     the row
     * @param status {@link UpdateStatus#DONE} or {@link UpdateStatus#FAILED}
     * @param result the report, verbatim
     * @return the finished row, or empty if it was not running any more
     * @throws IllegalArgumentException if {@code status} is not a terminal one
     */
    Optional<UpdateRequest> finish(long id, UpdateStatus status, String result);

    /**
     * Rewrites a running request's report without settling it.
     *
     * Only a {@code RUNNING} row is touched, so a late progress write cannot reopen a settled request.
     *
     * @param result the report so far, as JSON
     * @return whether a running row was updated
     */
    boolean progress(long id, String result);

    /**
     * Starts the countdown on a request this process has claimed. <b>Only the worker calls this.</b>
     *
     * The worker and not the submitter, because only the worker knows whether the plan has work
     * in it - otherwise every request counts down thirty seconds before announcing that nothing
     * changed.
     *
     * @param id     the claimed request
     * @param length how long the countdown runs, from now on the database's clock
     * @return the row with its new {@code not_before}, or empty when it is no longer running -
     *         which means it was cancelled between the claim and this call
     */
    Optional<UpdateRequest> startCountdown(long id, Duration length);

    /**
     * Ends the countdown and says whether it was still there to end; only the worker calls this.
     *
     * One statement decides the race with a cancel at zero.
     *
     * @return {@code true} when the run may go ahead, {@code false} when somebody cancelled and nothing may stop
     */
    boolean commitCountdown(long id);

    /**
     * The outage that is counting down right now.
     *
     * @return the request being counted down, or empty. This is what proxy counts down
     *         towards
     */
    Optional<UpdateRequest> countingDown();

    /**
     * The run that is happening right now - claimed, past its countdown, servers down.
     *
     * The other half of {@link #countingDown()}. The proxy needs both because the two together
     * are the whole outage: the countdown is when it moves players out of the way, and this is the
     * minutes afterwards during which the waiting room has to keep telling them why they are
     * sitting in it. Reading only the countdown would put the right title on the screen for thirty
     * seconds and the wrong one for the five minutes that matter.
     *
     * @return the running request, or empty
     */
    Optional<UpdateRequest> running();

    /**
     * The one run that is open - {@code PENDING} or {@code RUNNING}, counting down or not.
     *
     * {@link #submit} refuses a second one while this answers, so there is at most one to name.
     * An interface reads it to say what the network is doing and to lock what would be refused.
     *
     * @return the open request, or empty; empty is also what a directory without it answers
     */
    default Optional<UpdateRequest> open() {
        return Optional.empty();
    }

    /**
     * Withdraws the countdown that is running.
     *
     * @param reason what to record, naming who cancelled
     * @return the cancelled row, or empty when the countdown had already run out - which is the
     *         answer the admin needs, not an error
     */
    Optional<UpdateRequest> cancelCountdown(String reason);

    /**
     * Returns when the next pending request becomes due, so the worker sleeps until exactly then.
     *
     * @return the earliest {@code not_before} among pending rows, or empty when there are none
     */
    Optional<Instant> nextDue();

    /**
     * Fails everything left {@code RUNNING}. <b>Only the worker calls this, once, at startup.</b>
     *
     * Nothing is running those rows: the only process that claims one is a worker, exactly one
     * {@code serve} may exist, and this one has just started. An orphaned request of any kind means
     * it died in the middle, so all of them fail rather than any being reported as success.
     *
     * @param failed what to write into those rows
     * @return how many there were
     */
    int settleOrphans(String failed);
}
