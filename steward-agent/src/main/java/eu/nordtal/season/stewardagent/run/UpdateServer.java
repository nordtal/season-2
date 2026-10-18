package eu.nordtal.season.stewardagent.run;

import static eu.nordtal.season.database.AdminTexts.TEXTS;

import eu.nordtal.season.database.notify.Channel;
import eu.nordtal.season.database.notify.Doorbell;
import eu.nordtal.season.database.notify.SignalHub;
import eu.nordtal.season.database.update.UpdateDirectory;
import eu.nordtal.season.database.update.UpdateReports;
import eu.nordtal.season.database.update.UpdateRequest;
import eu.nordtal.season.database.update.UpdateStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;

/**
 * The loop of steward-agent that turns the rows of the run inbox into runs.
 *
 * It drains the inbox on its own thread when the {@link SignalHub} signals and when a row falls due.
 */
@Slf4j
public final class UpdateServer implements AutoCloseable {

    /** The shortest wait, so a due row held by another claimer cannot spin the loop. */
    private static final Duration MINIMUM_WAIT = Duration.ofSeconds(1);

    private final UpdateDirectory directory;
    private final RequestRunner runner;

    /** Asks the daemon whether the one-shot of a run still runs. */
    private final Predicate<String> stillRunning;

    private final Doorbell doorbell = new Doorbell();
    private final Duration longestWait;
    private final Clock clock;

    private volatile boolean running = true;

    public UpdateServer(
            final UpdateDirectory directory,
            final RequestRunner runner,
            final Predicate<String> stillRunning,
            final Clock clock) {
        this(directory, runner, stillRunning, SignalHub.RECONCILIATION, clock);
    }

    /** Package-visible so a test can bound the wait without the hub. */
    UpdateServer(
            final UpdateDirectory directory,
            final RequestRunner runner,
            final Predicate<String> stillRunning,
            final Duration longestWait,
            final Clock clock) {
        this.directory = directory;
        this.runner = runner;
        this.stillRunning = stillRunning;
        this.longestWait = longestWait;
        this.clock = clock;
    }

    /** Wakes the loop on every signal, connect and reconciliation of {@code signals}. */
    public void listen(final SignalHub signals) {
        signals.on(Channel.UPDATE, "the update inbox", this::wake);
    }

    void wake() {
        doorbell.ring();
    }

    /** Runs until {@link #close()} and blocks the calling thread. */
    public void serve() {
        log.info("Serving update requests");
        while (running) {
            try {
                drain();
                if (!running) {
                    return;
                }
                doorbell.await(waitFor());
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (final RuntimeException failure) {
                // A bug in the loop must not leave a container that is up and deaf.
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

    /**
     * Settles what nothing carries out any more, then runs everything due, oldest first, until nothing is.
     *
     * A notification can arrive during a run.
     */
    void drain() {
        // Between runs nothing of this process's own is open, so an open row is a one-shot's or nobody's.
        settleOrphans();
        Optional<UpdateRequest> claimed = claimNext();
        while (running && claimed.isPresent()) {
            carryOut(claimed.get());
            claimed = claimNext();
        }
    }

    /**
     * Claims the next due request, or nothing while a run is handed to a one-shot.
     *
     * Until that row is settled the agent serves state, logs and the console and runs nothing.
     */
    private Optional<UpdateRequest> claimNext() {
        final Optional<String> oneShot = directory.handedTo();
        if (oneShot.isPresent()) {
            log.debug("{} carries out a run, so nothing is claimed until it settles it", oneShot.get());
            return Optional.empty();
        }
        return directory.claimNext();
    }

    /**
     * Carries out the run handed to this one-shot and settles its row.
     *
     * @return whether the run is still open and handed to this one-shot
     */
    public boolean carryOutHanded(final long id, final String oneShot) {
        final Optional<UpdateRequest> handed = directory
                .find(id)
                .filter(request -> request.status() == UpdateStatus.RUNNING)
                .filter(request ->
                        directory.runnerOf(id).filter(oneShot::equals).isPresent());
        if (handed.isEmpty()) {
            log.error("Request {} is not a run handed to {}, so nothing was carried out", id, oneShot);
            return false;
        }
        carryOut(handed.get());
        return true;
    }

    private void carryOut(final UpdateRequest request) {
        log.info("Running request {}: {} asked for by {}", request.id(), request.kind(), request.actor());
        // Every stage the run reaches redraws the Discord card and the chat line.
        final Outcome outcome = runner.run(request, report -> {
            // A progress write must never decide the run, or a stopped service would stay stopped.
            try {
                if (!directory.progress(request.id(), UpdateReports.toJson(report))) {
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
            // The one-shot settles the row. Until then the row keeps every other run out.
            log.info("Request {} is carried out by the one-shot it was handed to", request.id());
            return;
        }
        // Empty when the row is no longer RUNNING, ordinarily a stopped countdown, so nothing is overwritten.
        if (directory.finish(request.id(), outcome.status(), outcome.report()).isEmpty()) {
            log.info(
                    "Request {} was settled by somebody else while it ran - most likely"
                            + " cancelled during its countdown - so its report was not written",
                    request.id());
        } else {
            log.info("Request {} finished as {}", request.id(), outcome.status());
        }
    }

    /** Returns the time until the next row is due, at most the reconciliation interval. */
    Duration waitFor() {
        final Instant now = clock.instant();
        final Duration untilDue =
                directory.nextDue().map(due -> Duration.between(now, due)).orElse(longestWait);

        final Duration wait = untilDue.compareTo(longestWait) < 0 ? untilDue : longestWait;
        return wait.compareTo(MINIMUM_WAIT) < 0 ? MINIMUM_WAIT : wait;
    }

    /**
     * Fails every open run except one that is handed to a one-shot that still runs.
     *
     * Safe only while one agent claims runs, and only between its runs; a one-shot never calls it.
     */
    private void settleOrphans() {
        try {
            final int settled = directory.settleOrphans(TEXTS.report().orphaned(), stillRunning);
            if (settled > 0) {
                log.info("Settled {} request(s) no steward-agent carries out any more", settled);
            }
        } catch (final RuntimeException failure) {
            // The stale rows are cosmetic, and refusing to start over them is worse.
            log.error("Could not settle the requests left open by the previous instance", failure);
        }
    }

    /** Asks the loop to stop after the request it is on. */
    @Override
    public void close() {
        running = false;
        doorbell.ring();
    }
}
