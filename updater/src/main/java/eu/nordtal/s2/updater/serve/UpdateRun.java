package eu.nordtal.s2.updater.serve;

import eu.nordtal.s2.common.update.UpdateReport;
import eu.nordtal.s2.updater.arcane.ArcaneOps;
import eu.nordtal.s2.updater.arcane.RedeployResult;
import eu.nordtal.s2.updater.arcane.RuntimeResult;
import eu.nordtal.s2.updater.arcane.ServiceRuntime;
import eu.nordtal.s2.updater.plan.Topology;

import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Stop the servers, do the work, start them again, and prove they came back.
 *
 * <h2>Why an update is one sequence and not three buttons</h2>
 * It was three until 2026-09-07: check, install, restart. Install swapped jars into
 * {@code plugins/} <b>while the servers were running</b>, which is finding 147 - the running JVM's
 * jar is replaced underneath it and every class it has not yet loaded is gone. {@code onDisable}
 * died halfway through on a real deployment; what was lost that time was an arena teardown, and
 * what it would have lost with a duel in progress is both fighters' inventories. The gap between
 * stopping and starting is where jars are safe to move, and nothing but this class can create one:
 * Arcane's project-level calls do stop <em>and</em> start in a single request.
 *
 * <h2>What it will not do</h2>
 * <ul>
 *   <li><b>Begin without Arcane.</b> The very first thing is a read of the project's runtime, and a
 *       run that cannot reach Arcane stops there having touched nothing. Swapping anyway would be
 *       the defect this class exists to remove, performed as a fallback.</li>
 *   <li><b>Stop a server with nothing to install.</b> An outage with nothing to show for it is
 *       worse than no update, and the plan already knows which services move.</li>
 *   <li><b>Stop itself.</b> The updater is in the same compose project, so a project-wide call
 *       would kill the process running this sequence - which is exactly why the old design could
 *       never report whether anything came back. Its own new jar is placed and picked up at its
 *       next start, the same way it always was.</li>
 *   <li><b>Roll back.</b> Decided by the owner on 2026-09-07: a rollback that fails is the worst
 *       state of all, and a server that will not start needs somebody reading its log. The
 *       replaced jars are kept rather than deleted and the report names where.</li>
 * </ul>
 */
@Slf4j
final class UpdateRun {

    /**
     * How long a service may take to come back before the run calls it a failure.
     *
     * <p>Five minutes, decided by the owner. A Paper server with a generated world takes well over
     * a minute on a cold start, and the readiness marker this waits for is refreshed every 30
     * seconds - so the number has to leave room for a slow start plus a heartbeat, and still be
     * short enough that somebody watching an embed learns something.</p>
     */
    static final Duration HEALTH_PATIENCE = Duration.ofMinutes(5);

    /** How often the runtime is re-read while waiting. Cheap: one GET against a local Arcane. */
    private static final Duration HEALTH_POLL = Duration.ofSeconds(5);

    private final ArcaneOps arcane;
    private final Consumer<UpdateReport> progress;

    UpdateRun(final @NotNull ArcaneOps arcane, final @NotNull Consumer<UpdateReport> progress) {
        this.arcane = arcane;
        this.progress = progress;
    }

    /**
     * Reads the project's runtime, or hands back the reason the run must not start.
     *
     * <p>Called before anything is resolved, downloaded or moved. That ordering is the whole of
     * decision Q14: an update that cannot stop a server has no safe way to continue.</p>
     */
    @NotNull RuntimeResult check() {
        return arcane.runtime();
    }

    /**
     * Stops every service the report has work for, in the order the report lists them.
     *
     * @return the services actually stopped, which is what {@link #start} and {@link #verify} act
     *         on - a service that could not be stopped is <b>not</b> in it, because starting
     *         something that was never stopped is how one failure becomes two
     */
    @NotNull Stopped stop(final UpdateReport planned, final RuntimeResult runtime) {
        UpdateReport report = planned.withStage(UpdateReport.Stage.STOPPING);
        progress.accept(report);

        final List<String> stopped = new ArrayList<>();
        for (final UpdateReport.ServiceLine line : planned.services()) {
            if (line.changes().isEmpty()) {
                continue;
            }
            if (Topology.UPDATER.equals(line.service())) {
                // Its own jar is placed and picked up at its next start, exactly as before. This
                // sequence is running inside it.
                report = report.with(line.at(UpdateReport.State.INSTALLED));
                progress.accept(report);
                continue;
            }
            final ServiceRuntime service = runtime.service(line.service()).orElse(null);
            if (service == null || service.containerId() == null) {
                report = report.with(line.failed("Arcane does not list a container for this"
                        + " service, so it could not be stopped and nothing was installed for it"));
                progress.accept(report);
                continue;
            }
            final RedeployResult result = arcane.stop(service.containerId());
            if (!result.triggered()) {
                report = report.with(line.failed("could not be stopped: " + result.message()));
                progress.accept(report);
                continue;
            }
            stopped.add(line.service());
            report = report.with(line.at(UpdateReport.State.STOPPED));
            progress.accept(report);
        }
        return new Stopped(report, List.copyOf(stopped), runtime);
    }

    /** Starts everything this run stopped, and says so. */
    @NotNull UpdateReport start(final Stopped state) {
        UpdateReport report = state.report().withStage(UpdateReport.Stage.STARTING);
        progress.accept(report);

        for (final String service : state.services()) {
            final ServiceRuntime entry = state.runtime().service(service).orElse(null);
            final UpdateReport.ServiceLine line = report.line(service);
            if (entry == null || entry.containerId() == null) {
                report = report.with(line.failed("no container id to start it with"));
                progress.accept(report);
                continue;
            }
            final RedeployResult result = arcane.start(entry.containerId());
            report = report.with(result.triggered()
                    ? line.at(UpdateReport.State.STARTING)
                    : line.failed("could not be started: " + result.message()));
            progress.accept(report);
        }
        return report;
    }

    /**
     * Waits until every started service reports back, or says which one did not.
     *
     * <h2>Running is not back</h2>
     * A container whose plugin threw in {@code onEnable} is {@code running} with an open port and
     * no season on it - the first deployment did exactly that. What is waited for is the
     * healthcheck, which reads the readiness marker every one of the five processes writes and
     * refreshes; see {@code common/…/health/Readiness.java}. This is the first thing in the network
     * that reads that evidence and can act on it.
     *
     * @param clock how "now" is told, so the wait can be driven in a test without sleeping
     */
    @NotNull UpdateReport verify(final UpdateReport started, final List<String> services,
                                 final Waiting clock) {
        UpdateReport report = started.withStage(UpdateReport.Stage.VERIFYING);
        progress.accept(report);

        final UpdateReport starting = report;
        final List<String> pending = new ArrayList<>(services.stream()
                .filter(service -> starting.line(service).state() == UpdateReport.State.STARTING)
                .toList());
        final Instant deadline = clock.now().plus(HEALTH_PATIENCE);

        while (!pending.isEmpty()) {
            final RuntimeResult now = arcane.runtime();
            if (now.reached()) {
                final List<String> back = new ArrayList<>();
                for (final String service : pending) {
                    if (now.service(service).map(ServiceRuntime::isBack).orElse(false)) {
                        back.add(service);
                        report = report.with(report.line(service).at(UpdateReport.State.HEALTHY));
                    }
                }
                if (!back.isEmpty()) {
                    pending.removeAll(back);
                    progress.accept(report);
                }
            }
            if (pending.isEmpty()) {
                break;
            }
            if (!clock.now().isBefore(deadline)) {
                // The snapshot this iteration already read, not a fresh GET per pending
                // service: that was one extra request each on the exact path where Arcane is slow
                // or failing, and because every call is its own snapshot the descriptions could
                // disagree with one another inside a single report.
                final RuntimeResult last = now;
                for (final String service : pending) {
                    final String seen = last.reached()
                            ? last.service(service).map(ServiceRuntime::describe)
                                    .orElse("not listed by Arcane")
                            : "Arcane could not be read: " + last.message();
                    report = report.with(report.line(service).failed("did not come back within "
                            + HEALTH_PATIENCE.toMinutes() + " minutes (" + seen + ") - its own log"
                            + " is where the reason is, and the jar it was running before this"
                            + " update is still on disk"));
                }
                progress.accept(report);
                break;
            }
            if (!clock.sleep(HEALTH_POLL)) {
                // Interrupted: the container is going down under us. Say so rather than reporting
                // a timeout that did not happen.
                for (final String service : pending) {
                    report = report.with(report.line(service)
                            .failed("the updater stopped while waiting for this service"));
                }
                progress.accept(report);
                break;
            }
        }
        return report;
    }

    /** What {@link #stop} produced, carried to the two steps after it. */
    record Stopped(UpdateReport report, List<String> services, RuntimeResult runtime) {
    }

    /**
     * The clock and the wait, as one seam.
     *
     * <p>Only so that {@link #verify} can be driven without five real minutes passing. The real one
     * sleeps; the test one advances an instant and answers immediately, which is what makes the
     * timeout branch reachable at all.</p>
     */
    interface Waiting {

        Instant now();

        /** @return false when interrupted, which ends the wait rather than swallowing it */
        boolean sleep(Duration duration);

        static Waiting real() {
            return new Waiting() {
                @Override
                public Instant now() {
                    return Instant.now();
                }

                @Override
                public boolean sleep(final Duration duration) {
                    try {
                        Thread.sleep(duration.toMillis());
                        return true;
                    } catch (final InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            };
        }
    }
}
