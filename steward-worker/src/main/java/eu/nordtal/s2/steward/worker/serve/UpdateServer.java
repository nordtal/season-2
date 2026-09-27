package eu.nordtal.s2.steward.worker.serve;

import eu.nordtal.s2.common.update.UpdateDirectory;
import eu.nordtal.s2.common.update.UpdateRequest;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

/**
 * {@code steward-worker serve}: the loop that turns {@code update_request} rows into runs; nothing runs without one.
 *
 * Every reconnect drains the table before waiting, so a lost notification costs latency, not a run.
 */
@Slf4j
public final class UpdateServer implements AutoCloseable {

    /** How long to wait before opening a new {@code LISTEN} connection after one failed. */
    private static final Duration RECONNECT_BACKOFF = Duration.ofSeconds(5);

    /** The floor on any wait, so a due row another worker holds cannot spin the loop. */
    private static final Duration MINIMUM_WAIT = Duration.ofSeconds(1);

    private final UpdateDirectory directory;
    private final RequestRunner runner;
    private final Notifications.Connector connector;
    private final Duration pollInterval;
    private final Clock clock;
    private final Duration reconnectBackoff;

    private volatile boolean running = true;

    public UpdateServer(
            final UpdateDirectory directory,
            final RequestRunner runner,
            final Notifications.Connector connector,
            final Duration pollInterval,
            final Clock clock) {
        this(directory, runner, connector, pollInterval, clock, RECONNECT_BACKOFF);
    }

    /** Package-visible so a test can watch several reconnects without waiting seconds for each. */
    UpdateServer(
            final UpdateDirectory directory,
            final RequestRunner runner,
            final Notifications.Connector connector,
            final Duration pollInterval,
            final Clock clock,
            final Duration reconnectBackoff) {
        this.directory = directory;
        this.runner = runner;
        this.connector = connector;
        this.pollInterval = pollInterval;
        this.clock = clock;
        this.reconnectBackoff = reconnectBackoff;
    }

    /** Runs until {@link #close()}, blocking the calling thread, which is {@code main}'s. */
    public void serve() {
        settleOrphans();

        while (running) {
            try (Notifications notifications = connector.listen()) {
                log.info("Listening for update requests on {}", UpdateDirectory.CHANNEL);
                while (running) {
                    // Drain before waiting for anything: a notification received while disconnected is lost.
                    drain();
                    if (!running) {
                        // Handed over: returning ends the process, and Docker starts the new jar.
                        return;
                    }
                    notifications.awaitNotification(waitFor());
                }
            } catch (final SQLException failure) {
                if (!running) {
                    return;
                }
                log.warn(
                        "The update listener connection failed; reconnecting in {}s",
                        reconnectBackoff.toSeconds(),
                        failure);
                sleep(reconnectBackoff);
            } catch (final RuntimeException failure) {
                // A bug in the loop itself must not turn into a container that is up and deaf.
                if (!running) {
                    return;
                }
                log.error("The update loop threw; restarting it in {}s", reconnectBackoff.toSeconds(), failure);
                sleep(reconnectBackoff);
            }
        }
    }

    /** Runs everything that is due, oldest first, until nothing is, since a notification can arrive mid-run. */
    void drain() {
        Optional<UpdateRequest> claimed = directory.claimNext();
        while (running && claimed.isPresent()) {
            final UpdateRequest request = claimed.get();
            log.info(
                    "Running request {}: {} asked for by {} from {}",
                    request.id(),
                    request.kind(),
                    request.requestedBy(),
                    request.source());

            // The row is the progress bar: every stage the run reaches redraws the Discord embed and chat line.
            final Outcome outcome = runner.run(request, report -> {
                // A progress write must never decide the run, or a stopped service would stay stopped.
                try {
                    if (!directory.progress(request.id(), eu.nordtal.s2.common.update.UpdateReports.toJson(report))) {
                        // No longer RUNNING, cancelled or settled elsewhere; the run carries on regardless.
                        log.warn(
                                "Request {} is no longer RUNNING, so its progress was not"
                                        + " recorded; the run itself continues",
                                request.id());
                    }
                } catch (final RuntimeException failure) {
                    log.warn("Could not record progress for request {}; the run continues", request.id(), failure);
                }
            });

            if (outcome.isHandedOver()) {
                // Stopped first, so this loop cannot claim back the row it hands over.
                running = false;
                if (directory.handOver(request.id(), outcome.report())) {
                    log.info(
                            "Request {} is handed to the steward-worker this run installed; exiting so that it"
                                    + " starts and finishes the request",
                            request.id());
                } else {
                    log.info(
                            "Request {} was settled by somebody else before it could be handed over; exiting"
                                    + " anyway, so that the steward-worker this run installed starts",
                            request.id());
                }
                return;
            }

            // Empty when the row is no longer RUNNING, ordinarily a stopped countdown, so nothing is overwritten.
            if (directory
                    .finish(request.id(), outcome.status(), outcome.report())
                    .isEmpty()) {
                log.info(
                        "Request {} was settled by somebody else while it ran - most likely"
                                + " cancelled during its countdown - so its report was not written",
                        request.id());
            } else {
                log.info("Request {} finished as {}", request.id(), outcome.status());
            }
            claimed = directory.claimNext();
        }
    }

    /** How long to block before looking again: the poll interval or the time until the next row is due. */
    Duration waitFor() {
        final Instant now = clock.instant();
        final Duration untilDue =
                directory.nextDue().map(due -> Duration.between(now, due)).orElse(pollInterval);

        final Duration wait = untilDue.compareTo(pollInterval) < 0 ? untilDue : pollInterval;
        return wait.compareTo(MINIMUM_WAIT) < 0 ? MINIMUM_WAIT : wait;
    }

    /**
     * Settles whatever the previous instance of this container left behind as failed.
     *
     * Safe only because {@code ServeLock} keeps a second {@code serve} from starting.
     */
    private void settleOrphans() {
        try {
            final int settled = directory.settleOrphans(
                    "Steward-worker stopped while this request was running, so it did not finish."
                            + " Nothing here says how far it got - check the report of the next run"
                            + " before assuming anything was installed.");
            if (settled > 0) {
                log.info("Settled {} request(s) left open by the previous instance", settled);
            }
        } catch (final RuntimeException failure) {
            // Not fatal: the stale rows are cosmetic, and refusing to start over them is worse.
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
