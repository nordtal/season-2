package eu.nordtal.s2.steward.worker.serve;

import eu.nordtal.s2.common.update.UpdateReport;
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
 * Stop the servers, do the work, start them again, and prove they came back.
 *
 * Why an update is one sequence and not three separate steps: a check, an install and a restart run independently
 * would install jars into {@code plugins/} while the servers are running - the running JVM's jar is replaced
 * underneath it and every class it has not yet loaded is gone. {@code onDisable} can die halfway through a live
 * deployment; what is lost is whatever state that shutdown hook was tearing down, up to and including a player's
 * inventory. The gap between stopping and starting is where jars are safe to move, and nothing but this class can
 * create one: a project-level redeploy does stop and start in a single request, which leaves no gap at all.
 *
 * What it will not do:
 *
 * - Begin without a container runtime. The very first thing is a read of the project's runtime, and a run that
 *   cannot read it stops there having touched nothing. Swapping anyway would be the defect this class exists to
 *   remove, performed as a fallback.
 * - Stop a server with nothing to install. An outage with nothing to show for it is worse than no update, and the
 *   plan already knows which services move.
 * - Stop itself. Steward-worker is in the same compose project, so a project-wide call would kill the process
 *   running this sequence - which is exactly why that shape could never report whether anything came back. Its own
 *   new jar is placed and picked up at its next start, the same way every other service's is.
 * - Roll back. A rollback that fails is the worst state of all, and a server that will not start needs somebody
 *   reading its log. The replaced jars are kept rather than deleted and the report names where.
 */
@Slf4j
final class UpdateRun {

    /**
     * How long a service may take to come back before the run calls it a failure.
     *
     * Five minutes. A Paper server with a generated world takes well over a minute on a cold start, and the
     * readiness marker this waits for is refreshed every 30 seconds - so the number has to leave room for a slow
     * start plus a heartbeat, and still be short enough that somebody watching an embed learns something.
     */
    static final Duration HEALTH_PATIENCE = Duration.ofMinutes(5);

    /** How often the runtime is re-read while waiting. Cheap: one call over the local socket. */
    private static final Duration HEALTH_POLL = Duration.ofSeconds(5);

    private final ContainerOps containers;
    private final Snapshots snapshots;
    private final Consumer<UpdateReport> progress;

    /**
     * Stops this run made, whose ending nobody could read, in the order they were made.
     *
     * Docker's stop call succeeds whether the container shut down or was killed at the end of the grace period, so
     * {@code DockerOps#stop} inspects afterwards - and that inspect can itself fail, at which point this run has a
     * stopped container and no idea whether the server finished writing. Refusing there would take the network
     * down over an unreadable inspect, and calling it an ordinary success would make the report unusable. So the
     * run carries on, and every archive it then writes gets a mark beside it naming what could not be confirmed:
     * see {@link Snapshots#markUnverified}.
     */
    private final List<String> unverifiedStops = new ArrayList<>();

    /**
     * Services running the image they already had because the recreate could not be done.
     *
     * Each is kept with the half of its report line that was known before the wait.
     *
     * They are {@code FAILED} lines - the update did not happen - and they are also lines this process asked
     * Docker to start, which is a different claim from "the service is back". {@link #start} only knows that the
     * daemon accepted the request; a container that exits on the first tick or never passes its healthcheck is
     * accepted just as readily. So the names are kept here and {@link #verify} waits for them on exactly the same
     * healthcheck as everything else, and then finishes the sentence with which of the two happened.
     *
     * The first half is kept here rather than read back off the report line, because reading it back would make the
     * finished sentence depend on nobody having rewritten that line in between - true today at all six call sites, and
     * one edit away from a report that reads "null, and it is back on that old version".
     */
    private final Map<String, String> fellBack = new LinkedHashMap<>();

    UpdateRun(final ContainerOps containers, final Snapshots snapshots, final Consumer<UpdateReport> progress) {
        this.containers = containers;
        this.snapshots = snapshots;
        this.progress = progress;
    }

    /**
     * Reads the project's runtime, or hands back the reason the run must not start.
     *
     * Called before anything is resolved, downloaded or moved. That ordering is the whole of decision Q14: an update
     * that cannot stop a server has no safe way to continue.
     */
    RuntimeResult check() {
        return containers.runtime();
    }

    /**
     * Stops every service the report has work for, in the order the report lists them.
     *
     * @return the services actually stopped, which is what {@link #start} and {@link #verify} act on - a service that
     *     could not be stopped is not in it, because starting something that was never stopped is how one failure
     *     becomes two
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
     * Saves every volume, with the servers already stopped.
     *
     * One at a time, and that is a change: The version that asked a panel over HTTP started every snapshot at once and
     * then waited for all of them, because it was asking somebody else to do the work and could not do it faster by
     * waiting differently. A local {@code tar} is this container's own CPU and this host's own disk: running eight of
     * them at once would not shorten the outage, it would lengthen it by making them fight for the same disk. So they
     * run in order, and the report shows each one finishing.
     *
     * Saved means a file exists, not that something was asked for: every line carries the size and the duration. A
     * volume that produced nothing is FAILED even if every call succeeded, so a backup can never report success
     * while having saved zero volumes.
     *
     * @param volumes the Docker volume names, from {@code steward.yml#backup.volumes}
     * @return the report with one line per volume, each {@code SAVED} or {@code FAILED}
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
                // The archive is kept: it is very probably fine, and somebody has to be told that before restoring it.
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
     * Read by {@code Runner} to settle the run. A stop like this is not a failure of anything this process did - which
     * is why the service line stays {@code STOPPED} - and a backup taken over it is still a real archive of a real
     * volume. What it is not is something to report as an ordinary success: a green report over a backup nobody
     * should have trusted. The run settles {@code FAILED}, so that nothing downstream - a report, a retention count,
     * an operator reading the list - counts an archive nobody can vouch for as one.
     */
    List<String> unverifiedStops() {
        return List.copyOf(unverifiedStops);
    }

    /**
     * Starts everything this run stopped, and says so.
     *
     * Two ways back up, and the image decides which: A {@code start} hands the container back to Docker on exactly the
     * image it was created from. That is right for the ordinary case and wrong for the one where the image itself has
     * moved: the jars would be new and {@code entrypoint.sh}, the JRE and every change to {@code compose.yml} would
     * still be whatever was pulled at the last deploy. A service {@link ImageResult} calls outdated is therefore
     * recreated - its image is pulled and the container is brought back up from that - and every other one is started
     * exactly as before.
     *
     * The recreate is asked for one service at a time and reported before it is asked for, so a run that never comes
     * back from one names it. That matters more here than anywhere else in this class: the call can take steward-worker
     * down with it if compose considers it a diverged dependency, and then this line is the last thing written.
     */
    UpdateReport start(final Stopped state) {
        return start(state, ImageResult.of(java.util.Map.of()));
    }

    /**
     * @param images which services are running a stale image and must be recreated rather than
     *               started. Empty on every path that is putting the network back the way it was -
     *               an abort, a restart, a backup: all three promise to change no version, and
     *               pulling an image during one would change the biggest version there is
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

    // A recreate this process cannot do is not a reason to leave a server off: the old image is put back.
    private UpdateReport startOutdated(final UpdateReport before, final Stopped state, final String service) {
        // Written before the call: a recreate that never returns leaves this as the report's last word.
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
     * Waits until every started service reports back, or says which one did not.
     *
     * Running is not back: a container whose plugin threw in {@code onEnable} is {@code running} with an open port
     * and no season on it. What is waited for is the healthcheck, which reads the readiness marker every one of the
     * five processes writes and refreshes; see {@code common/…/health/Readiness.java}. This is the first thing in
     * the network that reads that evidence and can act on it.
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

    /**
     * The clock and the wait, as one seam.
     *
     * Only so that {@link #verify} can be driven without five real minutes passing. The real one sleeps; the test one
     * advances an instant and answers immediately, which is what makes the timeout branch reachable at all.
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
