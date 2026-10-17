package eu.nordtal.season.stewardagent.run;

import static eu.nordtal.season.database.AdminTexts.TEXTS;

import eu.nordtal.season.common.time.Waiting;
import eu.nordtal.season.database.update.ByteSize;
import eu.nordtal.season.database.update.RunStep;
import eu.nordtal.season.database.update.UpdateReport;
import eu.nordtal.season.database.update.UpdateReport.Act;
import eu.nordtal.season.database.update.UpdateReport.Facts;
import eu.nordtal.season.internalapi.agent.ImageResult;
import eu.nordtal.season.internalapi.agent.RedeployResult;
import eu.nordtal.season.internalapi.agent.RuntimeResult;
import eu.nordtal.season.internalapi.agent.ServiceRuntime;
import eu.nordtal.season.internalapi.agent.SnapshotResult;
import eu.nordtal.season.messages.MessageRef;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/**
 * Stops the servers, does the work, starts them again, and proves they came back.
 *
 * It never starts without a runtime, stops a server with nothing to install, stops itself or rolls back.
 */
@Slf4j
final class UpdateRun {

    /** How long a service may take to come back before the run calls it a failure: a cold start plus a heartbeat. */
    static final Duration HEALTH_PATIENCE = Duration.ofMinutes(5);

    /** The artefact a container made again changes, as its run_change row names it. */
    static final String IMAGE = "image";

    /** How often the runtime is re-read while waiting: one call over the local socket. */
    private static final Duration HEALTH_POLL = Duration.ofSeconds(5);

    /** The run these steps belong to, which a mark beside an archive names. */
    private final long run;

    private final ContainerOps containers;
    private final Snapshots snapshots;
    private final Consumer<UpdateReport> progress;

    /** Where each step is written as a row. */
    final Steps record;

    /** Stops this run made whose ending nobody could read, in order; see {@link Snapshots#markUnverified}. */
    private final List<String> unverifiedStops = new ArrayList<>();

    /**
     * Services left on their old image because the recreate failed, each with the facts of that, which they keep.
     *
     * {@link #verify} waits for them like everything else, then adds how the wait ended.
     */
    private final Map<String, Facts> fellBack = new LinkedHashMap<>();

    UpdateRun(
            final long run,
            final ContainerOps containers,
            final Snapshots snapshots,
            final Consumer<UpdateReport> progress) {
        this(run, containers, snapshots, progress, Steps.NONE);
    }

    UpdateRun(
            final long run,
            final ContainerOps containers,
            final Snapshots snapshots,
            final Consumer<UpdateReport> progress,
            final Steps record) {
        this.run = run;
        this.containers = containers;
        this.snapshots = snapshots;
        this.progress = progress;
        this.record = record;
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
            if (Snapshots.DATABASE.equals(line.service())) {
                // Not a service: `database` is not the compose service (`postgres`), so looking it up finds nothing.
                continue;
            }
            final Steps.Step step = record.begin(RunStep.Kind.STOP, line.service());
            final ServiceRuntime service = runtime.service(line.service()).orElse(null);
            if (service == null || service.containerId() == null) {
                step.failed(RunStep.Reason.NO_CONTAINER);
                report = report.with(line.failed(Facts.refused(Act.STOP, null)));
                progress.accept(report);
                continue;
            }
            final RedeployResult result = containers.stop(service.containerId());
            if (!result.triggered()) {
                step.failed(RunStep.Reason.REFUSED);
                report = report.with(line.failed(Facts.refused(Act.STOP, result.message())));
                progress.accept(report);
                continue;
            }
            stopped.add(line.service());
            if (result.verified()) {
                step.done();
                report = report.with(line.at(UpdateReport.State.STOPPED));
            } else {
                step.warned(RunStep.Reason.UNVERIFIED_STOP);
                unverifiedStops.add(line.service());
                // It did stop; what the runtime answered about its ending stays on the line.
                report = report.with(
                        line.at(UpdateReport.State.STOPPED).with(Facts.refused(Act.STOP, result.message())));
            }
            progress.accept(report);
        }
        return new Stopped(report, List.copyOf(stopped), runtime);
    }

    /**
     * Saves every volume, one at a time, with the servers already stopped.
     *
     * @param volumes the Docker volume names, from the agent's backup mounts in compose.yml
     * @return one line per volume, {@code SAVED} with size and duration, or {@code FAILED}
     */
    UpdateReport save(final UpdateReport stopped, final List<String> volumes) {
        UpdateReport report = stopped.withStage(UpdateReport.Stage.BACKING_UP);
        progress.accept(report);

        for (final String volume : volumes) {
            report = report.with(new UpdateReport.ServiceLine(
                    volume,
                    UpdateReport.State.STARTING,
                    List.of(UpdateReport.Change.told("backup", TEXTS.report().saving())),
                    null));
            progress.accept(report);

            final Steps.Step step = record.begin(RunStep.Kind.SAVE_VOLUME, volume);
            final SnapshotResult result = snapshots.save(volume);
            if (result.ok()) {
                step.done();
            } else {
                step.failed(RunStep.Reason.TOOL_FAILED);
            }
            UpdateReport.Mark mark = null;
            if (result.ok() && result.file() != null && !unverifiedStops.isEmpty()) {
                // The archive is kept: it is probably fine, and somebody must be told before restoring it.
                mark = snapshots.markUnverified(
                                result.file(), new Snapshots.Unverified(run, List.copyOf(unverifiedStops)))
                        ? UpdateReport.Mark.WRITTEN
                        : UpdateReport.Mark.NOT_WRITTEN;
            }
            report = report.with(saved(volume, result, mark));
            progress.accept(report);
        }
        return report;
    }

    /**
     * One save as its line: saved into its archive, or failed with what the save answered.
     *
     * @param mark whether the mark of an unverified stop lies beside the archive, or none after a clean stop
     */
    static UpdateReport.ServiceLine saved(
            final String name, final SnapshotResult result, final UpdateReport.@Nullable Mark mark) {
        final String file = result.file();
        return new UpdateReport.ServiceLine(
                name,
                result.ok() ? UpdateReport.State.SAVED : UpdateReport.State.FAILED,
                List.of(backedUp(result)),
                result.ok()
                        ? Facts.saved(
                                file == null
                                        ? null
                                        : Path.of(file).getFileName().toString(),
                                mark)
                        : Facts.refused(Act.BACKUP, String.valueOf(result.message())));
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

    /** Starts them, recreating each whose image is out of date from a pulled one. */
    UpdateReport start(final Stopped state, final ImageResult images) {
        return start(state, images, List.of(), true);
    }

    /**
     * Starts everything this run stopped.
     *
     * @param images the services to recreate on a newer image; empty on every path that promises no version change
     */
    UpdateReport start(final Stopped state, final ImageResult images, final List<String> remade, final boolean pulls) {
        UpdateReport report = state.report().withStage(UpdateReport.Stage.STARTING);
        progress.accept(report);

        for (final String service : state.services()) {
            final UpdateReport.ServiceLine line = report.line(service);

            if (images.isOutdated(service) || remade.contains(service)) {
                report = startRemade(report, state, service, images.isOutdated(service), pulls);
                progress.accept(report);
                continue;
            }

            final Steps.Step step = record.begin(RunStep.Kind.START, service);
            final ServiceRuntime entry = state.runtime().service(service).orElse(null);
            if (entry == null || entry.containerId() == null) {
                step.failed(RunStep.Reason.NO_CONTAINER);
                report = report.with(line.failed(Facts.refused(Act.START, null)));
                progress.accept(report);
                continue;
            }
            final RedeployResult result = containers.start(entry.containerId());
            endBy(step, result);
            report = report.with(
                    result.triggered()
                            ? line.at(UpdateReport.State.STARTING)
                            : line.failed(Facts.refused(Act.START, result.message())));
            progress.accept(report);
        }
        return report;
    }

    // A recreate this process cannot do is no reason to leave a server off: the old image is put back.
    private UpdateReport startRemade(
            final UpdateReport before,
            final Stopped state,
            final String service,
            final boolean outdated,
            final boolean pulls) {
        // An out-of-date image is always pulled; a recreate asked for by name pulls when the kind does.
        final UpdateReport.Image image =
                outdated ? UpdateReport.Image.OUTDATED : pulls ? UpdateReport.Image.PULLED : UpdateReport.Image.KEPT;
        // Written before the call, so a recreate that never returns leaves this as the report's last word.
        final UpdateReport report = before.with(before.line(service)
                .at(UpdateReport.State.STARTING)
                .with(Facts.of(Act.RECREATE).made(image)));
        progress.accept(report);
        final Steps.Step install = record.begin(RunStep.Kind.INSTALL, service)
                .change(
                        IMAGE,
                        null,
                        null,
                        image == UpdateReport.Image.KEPT ? RunStep.Image.KEPT : RunStep.Image.PULLED);
        final RedeployResult recreated =
                image == UpdateReport.Image.KEPT ? containers.recreate(service) : containers.deploy(service);
        if (recreated.triggered()) {
            install.done();
            return report;
        }
        install.failed(RunStep.Reason.REFUSED);

        final String why = recreated.message();
        final Steps.Step fallback = record.begin(RunStep.Kind.START, service);
        final ServiceRuntime old = state.runtime().service(service).orElse(null);
        if (old == null || old.containerId() == null) {
            fallback.failed(RunStep.Reason.NO_CONTAINER);
            return report.with(
                    report.line(service).failed(Facts.notRecreated(image, why, UpdateReport.Fallback.NONE, null)));
        }
        final RedeployResult back = containers.start(old.containerId());
        endBy(fallback, back);
        if (back.triggered()) {
            // Docker accepting a start is not a service coming back; verify() finishes this with what it saw.
            final Facts started = Facts.notRecreated(image, why, UpdateReport.Fallback.STARTED, null);
            fellBack.put(service, started);
            return report.with(report.line(service).failed(started));
        }
        return report.with(report.line(service)
                .failed(Facts.notRecreated(image, why, UpdateReport.Fallback.REFUSED, back.message())));
    }

    /** Ends a step by whether Docker took what it asked; a start's coming back is the verify step's. */
    static void endBy(final Steps.Step step, final RedeployResult result) {
        if (result.triggered()) {
            step.done();
        } else {
            step.failed(RunStep.Reason.REFUSED);
        }
    }

    /** A service back on the container made again from a newer image: that image is renewed, no longer out of date. */
    private static UpdateReport.ServiceLine renewed(final UpdateReport.ServiceLine back) {
        final Act act = back.facts() == null ? null : back.facts().act();
        // An abort starts the old container again, which renews nothing.
        if (act != Act.RECREATE && act != Act.RENEW) {
            return back;
        }
        final MessageRef outdated = TEXTS.report().imageOutdated();
        return new UpdateReport.ServiceLine(
                back.service(),
                back.state(),
                back.changes().stream()
                        .map(change -> outdated.equals(change.told())
                                ? UpdateReport.Change.told(
                                        change.artefact(), TEXTS.report().imageRenewed())
                                : change)
                        .toList(),
                back.facts());
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
        final Map<String, Steps.Step> verifying = new LinkedHashMap<>();
        pending.forEach(service -> verifying.put(service, record.begin(RunStep.Kind.VERIFY, service)));
        final Instant deadline = clock.now().plus(HEALTH_PATIENCE);

        while (!pending.isEmpty()) {
            final RuntimeResult now = containers.runtime();
            if (now.reached()) {
                final List<String> back = new ArrayList<>();
                for (final String service : pending) {
                    if (now.service(service).map(ServiceRuntime::isBack).orElse(false)) {
                        back.add(service);
                        Objects.requireNonNull(verifying.get(service)).done();
                        // A fallback line stays FAILED, its facts now saying that the service is back.
                        report = report.with(
                                fellBack.containsKey(service)
                                        ? report.line(service)
                                                .failed(Objects.requireNonNull(fellBack.get(service))
                                                        .came(UpdateReport.Back.YES, null, null))
                                        : renewed(report.line(service).at(UpdateReport.State.HEALTHY)));
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
                pending.forEach(service ->
                        Objects.requireNonNull(verifying.get(service)).failed(RunStep.Reason.NOT_HEALTHY));
                report = timedOut(report, pending, now);
                progress.accept(report);
                break;
            }
            if (!clock.sleep(HEALTH_POLL)) {
                pending.forEach(service ->
                        Objects.requireNonNull(verifying.get(service)).failed(RunStep.Reason.INTERRUPTED));
                report = interrupted(report, pending);
                progress.accept(report);
                break;
            }
        }
        return report;
    }

    /** One save as a backup change: its size and how long it took, or that it was not saved. */
    static UpdateReport.Change backedUp(final SnapshotResult result) {
        return UpdateReport.Change.told(
                "backup",
                result.ok()
                        ? TEXTS.report().saved(ByteSize.of(result.bytes()).message(), result.took())
                        : TEXTS.report().notSaved());
    }

    /** What the runtime showed of a service that did not come back: Docker's status and health, or why it is unread. */
    static Facts.Seen seen(final RuntimeResult runtime, final String service) {
        if (!runtime.reached()) {
            return new Facts.Seen(null, null, String.valueOf(runtime.message()));
        }
        return runtime.service(service)
                .filter(state -> state.status() != null)
                .map(state -> new Facts.Seen(state.status(), state.health(), null))
                .orElseGet(() -> new Facts.Seen(null, null, null));
    }

    // The snapshot this iteration already read, not a fresh GET per pending service.
    private UpdateReport timedOut(final UpdateReport before, final List<String> pending, final RuntimeResult last) {
        UpdateReport report = before;
        for (final String service : pending) {
            final Facts.Seen seen = seen(last, service);
            report = report.with(report.line(service)
                    .failed(fellBack.getOrDefault(service, Facts.of(Act.VERIFY))
                            .came(UpdateReport.Back.NO, HEALTH_PATIENCE.toMinutes(), seen)));
        }
        return report;
    }

    // Interrupted: say so rather than reporting a timeout that did not happen.
    private UpdateReport interrupted(final UpdateReport before, final List<String> pending) {
        UpdateReport report = before;
        for (final String service : pending) {
            report = report.with(report.line(service)
                    .failed(fellBack.getOrDefault(service, Facts.of(Act.VERIFY))
                            .came(UpdateReport.Back.INTERRUPTED, null, null)));
        }
        return report;
    }

    /** What {@link #stop} produced, carried to the two steps after it. */
    record Stopped(UpdateReport report, List<String> services, RuntimeResult runtime) {}
}
