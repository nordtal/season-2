package eu.nordtal.s2.common.update;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * steward-worker's inbox, as seen by every process that can ask for a run.
 *
 * <p>The worker is a separate container and nothing in this deployment can call it - there is no
 * socket between the processes - so a request travels through the one PostgreSQL they share: a row,
 * a {@code pg_notify}, and the worker listening. A request therefore survives a worker that
 * happens to be restarting.
 *
 * <p><b>The notification is never the state.</b> Notifications are lost while a process is
 * disconnected, so every reader polls as its guarantee; the payload is empty so there is nothing to
 * be tempted by.
 *
 * <p>Nothing here names Paper, Velocity, JDA, JDBI or HikariCP: the factory takes a
 * {@link DataSource} and every process hands in the pool it already owns.
 */
public interface UpdateDirectory {

    /**
     * The PostgreSQL channel every request is announced on - an alias for
     * {@link eu.nordtal.s2.common.notify.Channels#UPDATE}, never a second spelling of it.
     */
    String CHANNEL = eu.nordtal.s2.common.notify.Channels.UPDATE;

    /**
     * How long between an update being asked for and the servers going down.
     *
     * <p>A constant rather than a setting: the worker starts the countdown and the proxy renders it
     * to every player, and two containers configured separately would eventually disagree. The
     * instant itself travels on the row ({@code not_before}), so it is never computed twice.
     *
     * <p>It is also the cancel window - a cancel works right up until {@link #commitCountdown} takes
     * the row - and it has to be long enough for the backends' own tidying up at zero.
     *
     * <h2>Sixty since 2026-09-20, and thirty before that (season-2-ops/132)</h2>
     * Till's decision, and the objection that had held it at thirty for a day turned out to be an
     * objection to something else. It read: a sixty-second chat line would be planned for an
     * instant already behind every countdown this network runs, so it would never be spoken. True -
     * while the countdown was a duration the proxy started when the message arrived. It has not
     * been that since season-2-ops/118: the row carries the absolute instant the run happens, the
     * proxy plans its beats against that instant, and sixty seconds is as speakable as any other
     * number the moment the worker sets that instant far enough out.
     *
     * <p><b>This number and {@code Countdown.CHAT_THRESHOLDS} move together or not at all.</b> A
     * threshold above this value is a line nobody ever hears.</p>
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
    UpdateRequest submit(UpdateKind kind, UpdateSource source, String requestedBy, Duration delay);

    /**
     * The same, for a run that is only for some of the services (season-2-ops/127).
     *
     * <h2>A scoped run is not an abbreviated one</h2>
     * It resolves, counts down, parks the players in limbo, waits for health and writes the same
     * report. The only thing that is smaller is what it touches. That is Till's own wording of the
     * requirement and it is the whole reason this is a value on the row rather than a second code
     * path: one sequence, one report, one set of things that can go wrong.
     *
     * @param services compose service names. <b>Empty or {@code null} is the whole network</b>, the
     *                 same thing {@link #submit} asks for - not "no services", which would be a run
     *                 that stops nothing while claiming to be scoped
     */
    default UpdateRequest submit(
            final UpdateKind kind,
            final UpdateSource source,
            final String requestedBy,
            final Duration delay,
            final java.util.List<String> services) {
        // A default, so that a directory which knows nothing about scope - every test fake in this
        // repository - keeps working and answers with the run it has always written. The real
        // implementation overrides it; see JdbiUpdateDirectory.
        return submit(kind, source, requestedBy, delay);
    }

    /**
     * Which services a run is for.
     *
     * <h2>Why it is read separately rather than sitting on {@link UpdateRequest}</h2>
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
     * Every service that is being held down on purpose (season-2-ops/125).
     *
     * <p>Read by anything that is about to start a container: a run that brings a network back up
     * must not bring back the one service somebody stopped in order to work on it. Also read by the
     * interface, which is the only way it can draw "down because somebody said so" differently from
     * "down because it fell over" - the container runtime cannot tell those apart.</p>
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
     * <p>Unlike {@link #holds()} this has no harmless default: a directory that cannot write a hold
     * and pretends it did leaves a service stopped with nothing saying why, and the next run starts
     * it again. Loud beats silent, so the default throws and the two directories that can do this
     * override it.</p>
     */
    default void hold(final String service, final String heldBy, final Long requestId) {
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
     * <p>A number below 1 is clamped to 1, exactly as {@code AuditDirectory#recent} clamps it - a
     * caller that computed a page size down to zero wants the newest run, not an empty table with
     * no explanation in it. It was already the behaviour; saying so here is the point, because a
     * clamp nobody documents is a promise of "at most {@code limit} rows" that the implementation
     * quietly does not keep.</p>
     *
     * @param limit how many, at most. A screenful; this is a page, not an export. Below 1 is 1
     */
    java.util.List<UpdateRequest> recent(int limit);

    /**
     * Every request written after the one named, oldest first - what the Discord bot's
     * admin-channel feed reads, so a run started in game is visible to an admin who did not start
     * it.
     *
     * @param id the last one already drawn; {@code 0} for everything there is
     */
    java.util.List<UpdateRequest> since(long id);

    /** The highest id there is, or zero - where a feed starts, so a restart posts no history. */
    long latestId();

    /**
     * Every request that finished within the given window - a run that finished while the bot was
     * restarting has an id below {@link #latestId()} and would otherwise never be reported.
     */
    java.util.List<UpdateRequest> finishedWithin(Duration window);

    /**
     * The most recent nightly backup that finished, succeeded, and can be <em>shown</em> to have
     * saved something.
     *
     * <h2>What it was for, and that it currently has no caller</h2>
     * The farm world's nightly reset asked this before deleting anything: no provable backup meant
     * no reset. The farm world went on 2026-09-20 (season-2-ingame/30) and nothing has asked since.
     *
     * <p>It is kept rather than deleted because the question it answers is not about the farm world
     * - "is there an archive from the last N hours that demonstrably saved something" is the
     * question anything irreversible should ask, and the three conditions below are the part that
     * was expensive to get right. Whether it stays is not this ticket's to decide.</p>
     *
     * <h2>How deep "successful" goes, and why exactly this deep</h2>
     * Three conditions, and each one is there because the one before it is not enough:
     * <ol>
     *   <li>{@code kind = 'BACKUP'} and {@code status = 'DONE'} - a run that failed is not a
     *       backup, and neither is one still going.</li>
     *   <li>the {@code result} parses as an {@link UpdateReport} at {@link UpdateReport.Stage#DONE}
     *       - a row whose report nobody can read proves nothing, and proving nothing is the same
     *       answer as having nothing. The cost is that a worker old enough to write plain text into
     *       that column would stop the reset; that is a version skew of minutes inside one
     *       deployment, against a window of hours.</li>
     *   <li>{@link UpdateReport#savedSomething()} - A23 settled {@code DONE} having saved zero
     *       volumes. The status cannot see that and the report can.</li>
     * </ol>
     * It goes no deeper. It does not check <em>which</em> volume was saved: that list lives in the
     * worker's own config and a copy of it here would be two lists that drift apart quietly. What
     * that costs is written down on {@link UpdateReport#savedSomething()}.
     *
     * <p><b>This is a blocking database call.</b> A Paper plugin calls it from its async executor
     * and never from the server thread.</p>
     *
     * @param within how far back to look. Hours, not days: a window of a day or more lets
     *               yesterday's backup authorise today's irreversible thing, and one shorter than a
     *               slow backup refuses every night
     * @return the run, so a caller's log line can name it and an admin can read the row. Empty
     *         means all three of "there was none", "they failed" and "they saved nothing" - which
     *         are one outcome for whoever is deciding whether to do something irreversible
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
     * Rewrites a running request's report without settling it, so a run that takes minutes is not
     * indistinguishable from one that has hung.
     *
     * <p>Only a {@code RUNNING} row is touched: a settled or cancelled request keeps the answer it
     * has, so a late progress write cannot reopen it or overwrite the reason it failed.
     *
     * @param id     the claimed request
     * @param result the report so far, as JSON
     * @return whether a running row was updated
     */
    boolean progress(long id, String result);

    /**
     * Starts the countdown on a request this process has claimed. <b>Only the worker calls this.</b>
     *
     * <p>The worker and not the submitter, because only the worker knows whether the plan has work
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
     * Ends the countdown and says whether it was still there to end. <b>Only the worker calls
     * this.</b>
     *
     * <p>The one statement that decides the race at zero: a cancel arriving in the same millisecond
     * either wins (and this answers false) or is refused. Reading the status and then acting on it
     * would leave exactly the window this closes.
     *
     * @param id the claimed request
     * @return {@code true} when the run may go ahead, {@code false} when somebody cancelled - in
     *         which case <b>nothing may be stopped</b>
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
     * <p>The other half of {@link #countingDown()}. The proxy needs both because the two together
     * are the whole outage: the countdown is when it moves players out of the way, and this is the
     * minutes afterwards during which the waiting room has to keep telling them why they are
     * sitting in it. Reading only the countdown would put the right title on the screen for thirty
     * seconds and the wrong one for the five minutes that matter.</p>
     *
     * @return the running request, or empty
     */
    Optional<UpdateRequest> running();

    /**
     * The one run that is open - {@code PENDING} or {@code RUNNING}, counting down or not.
     *
     * <p>{@link #submit} refuses a second one while this answers, so there is at most one to name.
     * An interface reads it to say what the network is doing and to lock what would be refused.</p>
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
     * When the next pending request becomes due. The worker sleeps until this instant rather than
     * for a fixed interval, so a restart fires when its counter reaches zero and not a poll later.
     *
     * @return the earliest {@code not_before} among pending rows, or empty when there are none
     */
    Optional<Instant> nextDue();

    /**
     * Fails everything left {@code RUNNING}. <b>Only the worker calls this, once, at startup.</b>
     *
     * <p>Nothing is running those rows: the only process that claims one is a worker, exactly one
     * {@code serve} may exist, and this one has just started. An orphaned request of any kind means
     * it died in the middle, so all of them fail rather than any being reported as success.
     *
     * @param failed what to write into those rows
     * @return how many there were
     */
    int settleOrphans(String failed);
}
