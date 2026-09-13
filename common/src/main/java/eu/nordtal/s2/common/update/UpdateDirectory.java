package eu.nordtal.s2.common.update;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

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
     */
    Duration UPDATE_COUNTDOWN = Duration.ofSeconds(30);

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
     */
    UpdateRequest submit(UpdateKind kind, UpdateSource source, String requestedBy, Duration delay);

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
     * @param limit how many, at most. A screenful; this is a page, not an export
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
     * <h2>What it is for</h2>
     * Until 2026-09-13 the guarantee that the farm world had just been saved was two clocks in two
     * config files - {@code smp}'s backup at 04:45 against its reset at 05:00 - and no test
     * anywhere could hold one against the other. The clock moved to steward-worker, so the
     * coupling is gone and the reset asks the database instead: no backup here means no reset, a
     * loud line, and a farm world that survives one more day. That is the trade, and it is the
     * owner's (2026-09-13).
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
     * @param within how far back to look. Hours, not days: see {@code smp}'s
     *               {@code farm-reset-backup-window-hours} for why the number has to be well under
     *               a day and well over one slow backup
     * @return the run, so a caller's log line can name it and an admin can read the row. Empty
     *         means all three of "there was none", "they failed" and "they saved nothing" - which
     *         are one outcome for whoever is deciding whether to delete a world
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
     * @return the request being counted down, or empty. This is what network-control counts down
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
