package eu.nordtal.s2.common.update;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * The updater's inbox, as seen by every process: the Discord bot, the SMP plugin, the proxy and
 * the updater itself.
 *
 * <h2>Why a table and not a call</h2>
 * The updater is a separate container. Nothing in this deployment can call it - no shared process,
 * no socket, and deliberately no Docker socket anywhere (docs/updater.md#the-restart-and-why-not-the-docker-socket).
 * What all four processes share is one PostgreSQL, so a request travels through it: a row, a
 * {@code pg_notify}, and the updater listening. That is the machinery the phase model already uses,
 * so it is not a new kind of wiring - and it means a request survives an updater that happens to be
 * restarting at that moment.
 *
 * <h2>The notification is never the state</h2>
 * Same rule as {@code PhaseDirectory}: notifications are lost while a process is disconnected, so
 * every reader polls as its guarantee and treats {@code LISTEN} as the thing that makes it feel
 * instant. The payload is empty so that there is nothing to be tempted by.
 *
 * <h2>Platform</h2>
 * Nothing here names Paper, Velocity, JDA, JDBI or HikariCP - the factory takes a
 * {@link DataSource}, a JDK type, and every process hands in the pool it already owns.
 */
public interface UpdateDirectory {

    /**
     * The PostgreSQL channel every request is announced on.
     *
     * <p>An alias for {@link eu.nordtal.s2.common.notify.Channels#UPDATE} rather than a second
     * spelling of it: a channel name is only ever right in pairs, and two constants holding one
     * string is the shape that lets a listener point at a channel nobody publishes on.</p>
     */
    String CHANNEL = eu.nordtal.s2.common.notify.Channels.UPDATE;

    /**
     * How long between an update being asked for and the servers going down.
     *
     * <h2>Why a constant and not a setting</h2>
     * The updater starts the countdown and the proxy renders it to every player on the network. A
     * configurable value would have to be configured in two containers, and the first time they
     * disagreed the players would see a counter reach zero and nothing happen. The instant itself
     * travels on the row ({@code not_before}), so the two never compute it twice - this is only the
     * length {@link #startCountdown} asks for.
     *
     * <h2>Thirty seconds, and what that is long enough for</h2>
     * It was sixty until 2026-09-07, when the run stopped being "ask Arcane to redeploy" and became
     * stop, swap, start, verify. The owner shortened it deliberately: the countdown is now the
     * warning before an outage rather than before a restart, and a minute of warning for a thing
     * that then takes several minutes is mostly a minute of waiting. What has to fit inside it is
     * the backends' own tidying up - a duel settled and both inventories handed back, open graves
     * written, a spin paid out - all of which happen in one tick at zero.
     *
     * <p>It is also the cancel window, and halving it halves that. "Stop the countdown" works right
     * up until {@link #commitCountdown} takes the row, which is the instant the counter reaches
     * zero rather than the instant the request was claimed.</p>
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
     * @param delay       how long the updater must wait before acting. <b>{@link Duration#ZERO}
     *                    for every kind, since 2026-09-08</b> - the countdown is started by
     *                    {@link #startCountdown} once the updater knows there is work to do, so a
     *                    request that finds nothing new never counts anything down. Negative is
     *                    treated as zero rather than rejected
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
     * Every request written after the one named, oldest first.
     *
     * <p>What the Discord bot's admin-channel feed reads. A run asked for in game or at a console
     * had no surface anybody else could see until 2026-09-08: the only run visible to an admin who
     * did not start it was one started in Discord, which is the one that already has somebody
     * watching it.</p>
     *
     * @param id the last one already drawn; {@code 0} for everything there is
     */
    java.util.List<UpdateRequest> since(long id);

    /**
     * The highest id there is, or zero.
     *
     * <p>Where a feed starts, so a restart does not post a season of history into a channel.</p>
     */
    long latestId();

    /**
     * Every request that finished within the given window.
     *
     * <p>The other half of that start: a run that finished while the bot was restarting has an id
     * below {@link #latestId()} and would otherwise be the one run nobody ever saw the answer to.</p>
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
     * Rewrites a running request's report without settling it.
     *
     * <h2>V7 said a request is never amended, and V12 says why that changed</h2>
     * A run used to be one call to Arcane and a sentence about it. It is now a countdown, a stop
     * per service, a migration, a swap, a start per service and up to five minutes of waiting for
     * healthchecks - and a message that does not change for five minutes is indistinguishable from
     * one that has hung. So the report is written as the run moves, and the surfaces watching the
     * row redraw.
     *
     * <p>Only a {@code RUNNING} row is touched. A request that has been settled - or cancelled
     * while the updater was working - keeps the answer it has, so a late progress write cannot
     * reopen a finished request or overwrite the reason it failed.</p>
     *
     * @param id     the claimed request
     * @param result the report so far, as JSON
     * @return whether a running row was updated
     */
    boolean progress(long id, String result);

    /**
     * Starts the countdown on a request this process has claimed. <b>Only the updater calls this.</b>
     *
     * <h2>Why the updater and not the submitter, since 2026-09-08</h2>
     * Every surface used to write {@code not_before = now() + 30s} and the updater was forbidden to
     * act before it. So a countdown ran for <em>every</em> update asked for, including the ordinary
     * one where nothing is new: thirty seconds of "the servers are going down" shown to everybody
     * playing, ending in "everything is already current". The updater now resolves first and only
     * counts down when the plan has work in it, which only it can know.
     *
     * @param id      the claimed request
     * @param seconds how long the countdown runs, from now on the database's clock
     * @return the row with its new {@code not_before}, or empty when it is no longer running -
     *         which means it was cancelled between the claim and this call
     */
    Optional<UpdateRequest> startCountdown(long id, Duration length);

    /**
     * Ends the countdown and says whether it was still there to end. <b>Only the updater calls
     * this.</b>
     *
     * <p>The one statement that decides the race at zero: a {@code /update cancel} arriving in the
     * same millisecond either wins (and this answers false) or is refused. Reading the status and
     * then acting on it would leave exactly the window this closes.</p>
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
     * Withdraws the countdown that is running.
     *
     * @param reason what to record, naming who cancelled
     * @return the cancelled row, or empty when the countdown had already run out - which is the
     *         answer the admin needs, not an error
     */
    Optional<UpdateRequest> cancelCountdown(String reason);

    /**
     * When the next pending request becomes due.
     *
     * <p>What keeps a countdown honest: the updater sleeps until this instant rather than for a
     * fixed interval, so a restart fires when its counter reaches zero and not up to one poll
     * later.</p>
     *
     * @return the earliest {@code not_before} among pending rows, or empty when there are none
     */
    Optional<Instant> nextDue();

    /**
     * Fails everything left {@code RUNNING}. <b>Only the updater calls this, once, at startup.</b>
     *
     * <p>Nothing is running those rows: the only process that claims one is an updater, exactly one
     * {@code serve} may exist, and this one has just started.</p>
     *
     * <h2>A restart used to be closed as {@code DONE}, and that is gone</h2>
     * It was right while a restart was one Arcane redeploy of the whole project, which took this
     * container down mid-call - so an orphaned {@code RESTART} was how the updater learned the
     * restart had happened. Since 2026-09-07 a restart cycles the four Minecraft services and never
     * stops the updater, so an orphaned one means what every other kind means: it died in the
     * middle. Reporting that as success is the one reading nobody can act on.
     *
     * @param failed what to write into those rows
     * @return how many there were
     */
    int settleOrphans(String failed);
}
