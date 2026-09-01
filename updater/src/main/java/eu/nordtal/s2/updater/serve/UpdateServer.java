package eu.nordtal.s2.updater.serve;

import eu.nordtal.s2.common.update.UpdateDirectory;
import eu.nordtal.s2.common.update.UpdateRequest;

import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * {@code updater serve}: the loop that turns rows in {@code update_request} into runs.
 *
 * <h2>The poll is the guarantee, the notification is the speed</h2>
 * Exactly the rule the phase model states and for the same reason: notifications are lost while a
 * process is disconnected, so every reconnect drains the table unconditionally before it waits for
 * anything, and a listener that has died costs latency rather than correctness. The payload is
 * empty on purpose - there is nothing to be tempted into trusting.
 *
 * <h2>Why this container is allowed to run all the time</h2>
 * The first rule of this module is that <b>nothing updates on a schedule</b>: a crash restart at
 * three in the morning must not move a version. That rule is about <em>what</em> the loop does, not
 * about whether it exists. This loop does nothing at all until somebody writes a row - there is no
 * timer, no watch and no "check for updates on boot". A container that comes back up comes back on
 * exactly the jars it was running.
 *
 * <h2>Sleeping exactly as long as it should</h2>
 * A restart request sits in the table for a minute before it may be claimed, and the proxy counts
 * players down towards that instant. Sleeping for a fixed poll interval would fire it up to a poll
 * late - a counter that reaches zero and then waits. So each wait is the shorter of the poll
 * interval and the time until the next pending row is due.
 */
@Slf4j
public final class UpdateServer implements AutoCloseable {

    /** How long to wait before opening a new {@code LISTEN} connection after one failed. */
    private static final Duration RECONNECT_BACKOFF = Duration.ofSeconds(5);

    /**
     * The floor on any wait.
     * <p>
     * Without it, a row whose {@code not_before} has just passed but which cannot be claimed - for
     * the moment another updater holds it - would spin this loop as fast as the database can
     * answer.
     * </p>
     */
    private static final Duration MINIMUM_WAIT = Duration.ofSeconds(1);

    private final UpdateDirectory directory;
    private final RequestRunner runner;
    private final Notifications.Connector connector;
    private final Duration pollInterval;
    private final Clock clock;
    private final Duration reconnectBackoff;

    private volatile boolean running = true;

    public UpdateServer(final @NotNull UpdateDirectory directory,
                        final @NotNull RequestRunner runner,
                        final @NotNull Notifications.Connector connector,
                        final @NotNull Duration pollInterval,
                        final @NotNull Clock clock) {
        this(directory, runner, connector, pollInterval, clock, RECONNECT_BACKOFF);
    }

    /** Package-visible so a test can watch several reconnects without waiting seconds for each. */
    UpdateServer(final @NotNull UpdateDirectory directory,
                 final @NotNull RequestRunner runner,
                 final @NotNull Notifications.Connector connector,
                 final @NotNull Duration pollInterval,
                 final @NotNull Clock clock,
                 final @NotNull Duration reconnectBackoff) {
        this.directory = directory;
        this.runner = runner;
        this.connector = connector;
        this.pollInterval = pollInterval;
        this.clock = clock;
        this.reconnectBackoff = reconnectBackoff;
    }

    /**
     * Runs until {@link #close()}. Blocks the calling thread - this is the container's whole job,
     * so it is {@code main}'s thread and not a daemon one.
     */
    public void serve() {
        settleOrphans();

        while (running) {
            try (Notifications notifications = connector.listen()) {
                log.info("Listening for update requests on {}", UpdateDirectory.CHANNEL);
                while (running) {
                    // THE rule: drain before waiting for anything. A request written while this
                    // process was disconnected produced a notification nobody received, and no
                    // later notification will repeat it.
                    drain();
                    notifications.awaitNotification(waitFor());
                }
            } catch (final SQLException failure) {
                if (!running) {
                    return;
                }
                log.warn("The update listener connection failed; reconnecting in {}s",
                        reconnectBackoff.toSeconds(), failure);
                sleep(reconnectBackoff);
            } catch (final RuntimeException failure) {
                // A bug in the loop itself must not turn into a container that is up and deaf.
                if (!running) {
                    return;
                }
                log.error("The update loop threw; restarting it in {}s", reconnectBackoff.toSeconds(),
                        failure);
                sleep(reconnectBackoff);
            }
        }
    }

    /**
     * Runs everything that is due, oldest first, until nothing is.
     * <p>
     * Package-visible for the test. Draining rather than taking one row per wake-up matters on the
     * path where it is least convenient: a request written while the updater was busy with the
     * previous one produced a notification that arrived during the run and was never waited for.
     * </p>
     */
    void drain() {
        Optional<UpdateRequest> claimed;
        while (running && (claimed = directory.claimNext()).isPresent()) {
            final UpdateRequest request = claimed.get();
            log.info("Running request {}: {} asked for by {} from {}",
                    request.id(), request.kind(), request.requestedBy(), request.source());

            final Outcome outcome = runner.run(request);

            // The one place a RESTART usually does not reach: by now this container is on its way
            // down and the row stays RUNNING, which is exactly how the next start recognises that
            // the redeploy worked.
            directory.finish(request.id(), outcome.status(), outcome.report());
            log.info("Request {} finished as {}", request.id(), outcome.status());
        }
    }

    /**
     * How long to block before looking again.
     * <p>
     * Package-visible and side-effect free apart from the one query, because the arithmetic is the
     * part worth testing: this is what decides whether a countdown fires on time.
     * </p>
     */
    Duration waitFor() {
        final Instant now = clock.instant();
        final Duration untilDue = directory.nextDue()
                .map(due -> Duration.between(now, due))
                .orElse(pollInterval);

        final Duration wait = untilDue.compareTo(pollInterval) < 0 ? untilDue : pollInterval;
        return wait.compareTo(MINIMUM_WAIT) < 0 ? MINIMUM_WAIT : wait;
    }

    /**
     * Settles whatever the previous instance of this container left behind.
     * <p>
     * Nothing is running those rows: the only process that claims one is an updater, and this one
     * has just started. A {@code RESTART} found in that state is the successful outcome of the last
     * thing the previous instance did - see {@code UpdateDirectory#settleOrphans}.
     * </p>
     */
    private void settleOrphans() {
        try {
            final int settled = directory.settleOrphans(
                    "The redeploy happened: the updater was restarted while this request was"
                            + " running, which is what a restart does to it.",
                    "The updater stopped while this request was running, so it did not finish."
                            + " Nothing here says how far it got - check the report of the next run"
                            + " before assuming anything was installed.");
            if (settled > 0) {
                log.info("Settled {} request(s) left open by the previous instance", settled);
            }
        } catch (final RuntimeException failure) {
            // Not fatal: the loop below still works, and the stale rows are cosmetic until the
            // next restart. Refusing to start over it would be the worse trade.
            log.error("Could not settle the requests left open by the previous instance", failure);
        }
    }

    private void sleep(final Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }

    /** Asks the loop to stop. It finishes the request it is on first. */
    @Override
    public void close() {
        running = false;
    }
}
