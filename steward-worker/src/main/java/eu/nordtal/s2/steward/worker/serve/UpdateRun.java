package eu.nordtal.s2.steward.worker.serve;

import eu.nordtal.s2.database.update.UpdateReport;
import eu.nordtal.s2.steward.worker.backup.DatabaseDump;
import eu.nordtal.s2.steward.worker.backup.SnapshotResult;
import eu.nordtal.s2.steward.worker.backup.Snapshots;
import eu.nordtal.s2.steward.worker.ops.ContainerOps;
import eu.nordtal.s2.steward.worker.ops.ImageResult;
import eu.nordtal.s2.steward.worker.ops.RedeployResult;
import eu.nordtal.s2.steward.worker.ops.RuntimeResult;
import eu.nordtal.s2.steward.worker.ops.ServiceRuntime;
import eu.nordtal.s2.steward.worker.plan.Topology;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;

/**
 * Stops the servers, does the work, starts them again, and proves they came back.
 *
 * It never starts without a runtime, stops a server with nothing to install, stops itself or rolls back.
 */
@Slf4j
final class UpdateRun {

    /** How long a service may take to come back before the run calls it a failure: a cold start plus a heartbeat. */
    static final Duration HEALTH_PATIENCE = Duration.ofMinutes(5);

    /** How often the runtime is re-read while waiting: one call over the local socket. */
    private static final Duration HEALTH_POLL = Duration.ofSeconds(5);

    private final ContainerOps containers;
    private final Snapshots snapshots;
    private final Consumer<UpdateReport> progress;

    /** Stops this run made whose ending nobody could read, in order; see {@link Snapshots#markUnverified}. */
    private final List<String> unverifiedStops = new ArrayList<>();

    /**
     * Services left on their old image because the recreate failed, each with the known half of its report line.
     *
     * {@link #verify} waits for them like everything else, then finishes the sentence with what it saw.
     */
    private final Map<String, String> fellBack = new LinkedHashMap<>();

    UpdateRun(final ContainerOps containers, final Snapshots snapshots, final Consumer<UpdateReport> progress) {
        this.containers = containers;
        this.snapshots = snapshots;
        this.progress = progress;
    }

    /** Reads the project's runtime, or hands back the reason the run must not start, before anything moves. */
    RuntimeResult check() {
        return containers.runtime();
    }

    /**
     * Stops every service the report has work for, in the order the report lists them.
     *
     * @return the services actually stopped, which {@link #start} and {@link #verify} act on
     */
    Stopped stop(final UpdateReport planned, final RuntimeResult runtime) {
        UpdateReport report = planned.withStage(UpdateReport.Stage.STOPPING);
        progress.accept(report);

        final List<String> stopped = new ArrayList<>();
        for (final UpdateReport.ServiceLine line : planned.services()) {
            // isMoving, not "has changes": no build for this version is no reason to take a server down.
            if (!line.isMoving()) {
                continue;
            }
            if (DatabaseDump.NAME.equals(line.service())) {
                // Not a service: `database` is not the compose service (`postgres`), so looking it up finds nothing.
                continue;
            }
            if (Topology.STEWARD_WORKER.equals(line.service())) {
                // Its own jar is placed and picked up at its next start; this sequence is running inside it.
                report = report.with(line.at(UpdateReport.State.INSTALLED));
                progress.accept(report);
                continue;
            }
            final ServiceRuntime service = runtime.service(line.service()).orElse(null);
            if (service == null || service.containerId() == null) {
                report = report.with(line.failed("the compose project has no container for this"
                        + " service, so it could not be stopped and nothing was installed for it"));
                progress.accept(report);
                continue;
            }
            final RedeployResult result = containers.stop(service.containerId());
            if (!result.triggered()) {
                report = report.with(line.failed("could not be stopped: " + result.message()));
                progress.accept(report);
                continue;
            }
            stopped.add(line.service());
            if (result.verified()) {
                report = report.with(line.at(UpdateReport.State.STOPPED));
            } else {
                unverifiedStops.add(line.service());
                report = report.with(line.at(UpdateReport.State.STOPPED).withDetail(result.message()));
            }
            progress.accept(report);
        }
        return new Stopped(report, List.copyOf(stopped), runtime);
    }

    /**
     * Saves every volume, one at a time, with the servers already stopped.
     *
     * @param volumes the Docker volume names, from {@code steward.yml#backup.volumes}
     * @return one line per volume, {@code SAVED} with size and duration, or {@code FAILED}
     */
    UpdateReport save(final UpdateReport stopped, final List<String> volumes) {
        UpdateReport report = stopped.withStage(UpdateReport.Stage.BACKING_UP);
        progress.accept(report);

        for (final String volume : volumes) {
            report = report.with(new UpdateReport.ServiceLine(
                    volume,
                    UpdateReport.State.STARTING,
                    List.of(new UpdateReport.Change("backup", null, "saving")),
                    null));
            progress.accept(report);

            final SnapshotResult result = snapshots.save(volume);
            String detail = result.ok() ? null : result.message();
            if (result.ok() && result.file() != null && !unverifiedStops.isEmpty()) {
                // The archive is kept: it is probably fine, and somebody must be told before restoring it.
                final String why = "The servers were stopped for this backup and the end of "
                        + String.join(", ", unverifiedStops) + " could not be read back, so nothing"
                        + " here knows whether the world had finished saving. The archive is"
                        + " readable; what is unverified is the moment it was taken.";
                final String mark = snapshots.markUnverified(result.file(), why);
                detail = mark == null
                        ? "UNVERIFIED STOP - and the mark beside the archive could not be written: " + why
                        : "UNVERIFIED STOP - see " + mark;
            }
            final UpdateReport.ServiceLine line = new UpdateReport.ServiceLine(
                    volume,
                    result.ok() ? UpdateReport.State.SAVED : UpdateReport.State.FAILED,
                    List.of(new UpdateReport.Change("backup", null, result.message())),
                    detail);
            report = report.with(line);
            progress.accept(report);
        }
        return report;
    }

    /**
     * Which of this run's stops had an ending nobody could read, or empty when every one was clean.
     *
     * {@code Runner} settles such a run {@code FAILED}, so nothing downstream trusts its archives.
     */
    List<String> unverifiedStops() {
        return List.copyOf(unverifiedStops);
    }

    /**
     * Starts everything this run stopped, recreating each service whose image {@link ImageResult} calls outdated.
     *
     * Each recreate is reported before it is asked for, so a run that never returns from one names it.
     */
    UpdateReport start(final Stopped state) {
        return start(state, ImageResult.of(java.util.Map.of()));
    }

    /**
     * Starts everything this run stopped.
     *
     * @param images the services to recreate on a newer image; empty on every path that promises no version change
     */
    UpdateReport start(final Stopped state, final ImageResult images) {
        UpdateReport report = state.report().withStage(UpdateReport.Stage.STARTING);
        progress.accept(report);

        for (final String service : state.services()) {
            final UpdateReport.ServiceLine line = report.line(service);

            if (images.isOutdated(service)) {
                report = startOutdated(report, state, service);
                progress.accept(report);
                continue;
            }

            final ServiceRuntime entry = state.runtime().service(service).orElse(null);
            if (entry == null || entry.containerId() == null) {
                report = report.with(line.failed("no container id to start it with"));
                progress.accept(report);
                continue;
            }
            final RedeployResult result = containers.start(entry.containerId());
            report = report.with(
                    result.triggered()
                            ? line.at(UpdateReport.State.STARTING)
                            : line.failed("could not be started: " + result.message()));
            progress.accept(report);
        }
        return report;
    }

    // A recreate this process cannot do is no reason to leave a server off: the old image is put back.
    private UpdateReport startOutdated(final UpdateReport before, final Stopped state, final String service) {
        // Written before the call, so a recreate that never returns leaves this as the report's last word.
        final UpdateReport report = before.with(before.line(service)
                .at(UpdateReport.State.STARTING)
                .withDetail("pulling its image and recreating the container"));
        progress.accept(report);
        final RedeployResult recreated = containers.deploy(service);
        if (recreated.triggered()) {
            return report.with(report.line(service).at(UpdateReport.State.STARTING));
        }

        final String why =
                "its image is out of date and the container could not be" + " recreated: " + recreated.message();
        final ServiceRuntime outdated = state.runtime().service(service).orElse(null);
        if (outdated == null || outdated.containerId() == null) {
            return report.with(
                    report.line(service).failed(why + " - and there is no container id to put the old one back with"));
        }
        final RedeployResult back = containers.start(outdated.containerId());
        if (back.triggered()) {
            // Docker accepting a start is not a service coming back; verify() finishes this with what it saw.
            final String half = why + ". It was started again on the image it already had";
            fellBack.put(service, half);
            return report.with(report.line(service).failed(half + ", and has not been seen coming back yet."));
        }
        return report.with(report.line(service)
                .failed(why + ", and starting it again on the old image failed too: " + back.message()));
    }

    /**
     * Waits until every started service passes its healthcheck, or says which one did not.
     *
     * @param clock how "now" is told, so the wait can be driven in a test without sleeping
     */
    UpdateReport verify(final UpdateReport started, final List<String> services, final Waiting clock) {
        UpdateReport report = started.withStage(UpdateReport.Stage.VERIFYING);
        progress.accept(report);

        final UpdateReport starting = report;
        final List<String> pending = new ArrayList<>(services.stream()
                .filter(service ->
                        starting.line(service).state() == UpdateReport.State.STARTING || fellBack.containsKey(service))
                .toList());
        final Instant deadline = clock.now().plus(HEALTH_PATIENCE);

        while (!pending.isEmpty()) {
            final RuntimeResult now = containers.runtime();
            if (now.reached()) {
                final List<String> back = new ArrayList<>();
                for (final String service : pending) {
                    if (now.service(service).map(ServiceRuntime::isBack).orElse(false)) {
                        back.add(service);
                        // A fallback line stays FAILED and gains the half of the sentence that is now known.
                        report = report.with(
                                fellBack.containsKey(service)
                                        ? report.line(service)
                                                .failed(fellBack.get(service) + ", and it is back on that old version.")
                                        : report.line(service).at(UpdateReport.State.HEALTHY));
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
                report = timedOut(report, pending, now);
                progress.accept(report);
                break;
            }
            if (!clock.sleep(HEALTH_POLL)) {
                report = interrupted(report, pending);
                progress.accept(report);
                break;
            }
        }
        return report;
    }

    // The snapshot this iteration already read, not a fresh GET per pending service.
    private UpdateReport timedOut(final UpdateReport before, final List<String> pending, final RuntimeResult last) {
        UpdateReport report = before;
        for (final String service : pending) {
            final String seen = last.reached()
                    ? last.service(service).map(ServiceRuntime::describe).orElse("no container for it in the project")
                    : "the container runtime could not be read: " + last.message();
            report = report.with(
                    fellBack.containsKey(service)
                            ? report.line(service)
                                    .failed(fellBack.get(service)
                                            + ", and it did NOT come back within "
                                            + HEALTH_PATIENCE.toMinutes() + " minutes (" + seen + "). The"
                                            + " service is down.")
                            : report.line(service)
                                    .failed("did not come back within "
                                            + HEALTH_PATIENCE.toMinutes() + " minutes (" + seen + ") - its"
                                            + " own log is where the reason is, and the jar it was running"
                                            + " before this update is still on disk"));
        }
        return report;
    }

    // Interrupted: say so rather than reporting a timeout that did not happen.
    private UpdateReport interrupted(final UpdateReport before, final List<String> pending) {
        UpdateReport report = before;
        for (final String service : pending) {
            report = report.with(report.line(service)
                    .failed(
                            fellBack.containsKey(service)
                                    ? fellBack.get(service) + ", and steward-worker stopped before it was"
                                            + " seen coming back."
                                    : "steward-worker stopped while waiting for this service"));
        }
        return report;
    }

    /** What {@link #stop} produced, carried to the two steps after it. */
    record Stopped(UpdateReport report, List<String> services, RuntimeResult runtime) {}

    /** The clock and the wait, as one seam, so {@link #verify}'s timeout can be tested without real minutes. */
    interface Waiting {

        Instant now();

        /** Sleeps, and returns false when interrupted, which ends the wait rather than swallowing it. */
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
