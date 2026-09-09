package eu.nordtal.s2.common.update;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * The updater's inbox, as seen by every process that can ask for a run.
 *
 * <p>The updater is a separate container and nothing in this deployment can call it - there is no
 * socket between the processes - so a request travels through the one PostgreSQL they share: a row,
 * a {@code pg_notify}, and the updater listening. A request therefore survives an updater that
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
     * <p>A constant rather than a setting: the updater starts the countdown and the proxy renders it
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
     * @param delay       how long the updater must wait before acting. {@link Duration#ZERO} for
     *                    every kind: the countdown is started by {@link #startCountdown} once the
     *                    updater knows there is work to do, so a request that finds nothing new
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
     * Takes the oldest due request and marks it running. <b>Only the updater calls this.</b>
     *
     * @return the claimed request, or empty when nothing is due
     */
    Optional<UpdateRequest> claimNext();

    /**
     * Writes the answer to a claimed request. <b>Only the updater calls this.</b>
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
     * Starts the countdown on a request this process has claimed. <b>Only the updater calls this.</b>
     *
     * <p>The updater and not the submitter, because only the updater knows whether the plan has work
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
     * Ends the countdown and says whether it was still there to end. <b>Only the updater calls
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
     * When the next pending request becomes due. The updater sleeps until this instant rather than
     * for a fixed interval, so a restart fires when its counter reaches zero and not a poll later.
     *
     * @return the earliest {@code not_before} among pending rows, or empty when there are none
     */
    Optional<Instant> nextDue();

    /**
     * Fails everything left {@code RUNNING}. <b>Only the updater calls this, once, at startup.</b>
     *
     * <p>Nothing is running those rows: the only process that claims one is an updater, exactly one
     * {@code serve} may exist, and this one has just started. An orphaned request of any kind means
     * it died in the middle, so all of them fail rather than any being reported as success.
     *
     * @param failed what to write into those rows
     * @return how many there were
     */
    int settleOrphans(String failed);
}
