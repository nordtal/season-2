package eu.nordtal.s2.steward.worker.serve;

import eu.nordtal.s2.database.notify.Channel;
import eu.nordtal.s2.database.notify.Doorbell;
import eu.nordtal.s2.database.notify.SignalHub;
import eu.nordtal.s2.database.update.UpdateDirectory;
import eu.nordtal.s2.database.update.UpdateRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

/**
 * {@code steward-worker serve}: the loop that turns the rows of the worker's inbox into runs; nothing runs without one.
 *
 * It drains on its own thread whenever the process's {@link SignalHub} rings its doorbell, and when a row falls due.
 */
@Slf4j
public final class UpdateServer implements AutoCloseable {

    /** The floor on any wait, so a due row another worker holds cannot spin the loop. */
    private static final Duration MINIMUM_WAIT = Duration.ofSeconds(1);

    private final UpdateDirectory directory;
    private final RequestRunner runner;
    private final Doorbell doorbell = new Doorbell();
    private final Duration longestWait;
    private final Clock clock;

    private volatile boolean running = true;

    public UpdateServer(final UpdateDirectory directory, final RequestRunner runner, final Clock clock) {
        this(directory, runner, SignalHub.RECONCILIATION, clock);
    }

    /** Package-visible so a test can bound the wait without the hub. */
    UpdateServer(
            final UpdateDirectory directory,
            final RequestRunner runner,
            final Duration longestWait,
            final Clock clock) {
        this.directory = directory;
        this.runner = runner;
        this.longestWait = longestWait;
        this.clock = clock;
    }

    /** Rings this server's doorbell on every signal, connect and reconciliation of {@code signals}. */
    public void listen(final SignalHub signals) {
        signals.on(Channel.UPDATE, "the update inbox", this::wake);
    }

    /** Wakes the loop, as a signal on {@link Channel#UPDATE} does. */
    void wake() {
        doorbell.ring();
    }

    /** Runs until {@link #close()}, blocking the calling thread, which is {@code main}'s. */
    public void serve() {
        settleOrphans();
        log.info("Serving update requests");
        while (running) {
            try {
                drain();
                if (!running) {
                    // Handed over: returning ends the process, and Docker starts the new jar.
                    return;
                }
                doorbell.await(waitFor());
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (final RuntimeException failure) {
                // A bug in the loop itself must not turn into a container that is up and deaf.
                log.error("The update loop threw; it carries on at the next wake-up", failure);
                try {
                    doorbell.await(longestWait);
                } catch (final InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** Runs everything that is due, oldest first, until nothing is, since a notification can arrive mid-run. */
    void drain() {
        Optional<UpdateRequest> claimed = directory.claimNext();
        while (running && claimed.isPresent()) {
            final UpdateRequest request = claimed.get();
            log.info("Running request {}: {} asked for by {}", request.id(), request.kind(), request.actor());

            // The row is the progress bar: every stage the run reaches redraws the Discord embed and chat line.
            final Outcome outcome = runner.run(request, report -> {
                // A progress write must never decide the run, or a stopped service would stay stopped.
                try {
                    if (!directory.progress(request.id(), eu.nordtal.s2.database.update.UpdateReports.toJson(report))) {
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

    /** How long to block before looking again: until the next row is due, at most the reconciliation. */
    Duration waitFor() {
        final Instant now = clock.instant();
        final Duration untilDue =
                directory.nextDue().map(due -> Duration.between(now, due)).orElse(longestWait);

        final Duration wait = untilDue.compareTo(longestWait) < 0 ? untilDue : longestWait;
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

    /** Asks the loop to stop. It finishes the request it is on first. */
    @Override
    public void close() {
        running = false;
        doorbell.ring();
    }
}
