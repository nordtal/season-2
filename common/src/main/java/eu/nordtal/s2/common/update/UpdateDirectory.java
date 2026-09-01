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

    /** The PostgreSQL channel every request is announced on. Next to {@code nordtal_phase}. */
    String CHANNEL = "nordtal_update";

    /**
     * How long between a restart being asked for and it happening.
     *
     * <h2>Why a constant and not a setting</h2>
     * Three processes submit restarts - the bot, the SMP plugin, and a person at a console - and a
     * fourth renders the countdown to every player on the network. A configurable value would have
     * to be configured in all four, in four files that are read by four containers, and the first
     * time one of them disagreed the players would see a counter reach zero and nothing happen.
     * The instant itself still travels on the row ({@code not_before}), so the updater and the
     * proxy never compute it twice - this is only the length the submitters use.
     *
     * <p>A minute is enough to notice a mistake and not enough to be annoying. It is also the
     * window a cancel has to fit into: {@code /smp update restart cancel} and the button in
     * Discord both work right up until an updater claims the row.</p>
     */
    Duration RESTART_COUNTDOWN = Duration.ofSeconds(60);

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
     *                    everything but a restart, whose countdown this is. Negative is treated as
     *                    zero rather than rejected - a caller computing a delay from two clocks
     *                    should get "now", not an exception
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
     * The restart that has been asked for and has not fired yet.
     *
     * @return the pending restart, or empty. This is what network-control counts down towards
     */
    Optional<UpdateRequest> pendingRestart();

    /**
     * Withdraws the pending restart.
     *
     * @param reason what to record, naming who cancelled
     * @return the cancelled row, or empty when the countdown had already run out - which is the
     *         answer the admin needs, not an error
     */
    Optional<UpdateRequest> cancelPendingRestart(String reason);

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
     * Marks restarts left {@code RUNNING} as done, and everything else left {@code RUNNING} as
     * failed. <b>Only the updater calls this, once, at startup.</b>
     *
     * <p>A restart is the one request that is <em>supposed</em> to end this way: the redeploy takes
     * this container down while the row is still open, so finding one on the next boot is how the
     * updater learns that the restart it asked for actually happened.</p>
     *
     * @param restarted what to write into orphaned restarts
     * @param failed    what to write into everything else
     * @return how many rows were touched in total
     */
    int settleOrphans(String restarted, String failed);
}
