package eu.nordtal.season.stewardagent.run;

import eu.nordtal.season.common.time.Waiting;
import eu.nordtal.season.database.update.RunStage;
import eu.nordtal.season.database.update.RunStep;
import eu.nordtal.season.internalapi.agent.ImageResult;
import eu.nordtal.season.internalapi.agent.RedeployResult;
import eu.nordtal.season.internalapi.agent.RuntimeResult;
import eu.nordtal.season.internalapi.agent.ServiceRuntime;
import eu.nordtal.season.internalapi.agent.SnapshotResult;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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

    /** The time a service gets to come back: a cold start plus a heartbeat. */
    static final Duration HEALTH_PATIENCE = Duration.ofMinutes(5);

    /** The artefact a container made again changes, as its run_change row names it. */
    static final String IMAGE = "image";

    private static final Duration HEALTH_POLL = Duration.ofSeconds(5);

    /** What {@code deploy/minecraft/entrypoint.sh} prints as its last line when it holds a refused start. */
    static final String REFUSED = "[nordtal] REFUSED: ";

    /** The lines of a restarted container's log searched for why its last run ended. */
    private static final int RESTART_LINES = 200;

    /** The run these steps belong to, which a mark beside an archive names. */
    private final long run;

    private final ContainerOps containers;
    private final Snapshots snapshots;
    private final Consumer<RunStage> progress;

    final Steps record;

    /** Stops this run made whose ending nobody could read, in order; see {@link Snapshots#markUnverified}. */
    private final List<String> unverifiedStops = new ArrayList<>();

    /** The services whose start Docker refused, which {@link #verify} does not wait for. */
    private final Set<String> refused = new HashSet<>();

    /** The services left on their old image because the recreate failed, which already failed the run. */
    private final Set<String> fellBack = new HashSet<>();

    UpdateRun(
            final long run,
            final ContainerOps containers,
            final Snapshots snapshots,
            final Consumer<RunStage> progress) {
        this(run, containers, snapshots, progress, Steps.none());
    }

    UpdateRun(
            final long run,
            final ContainerOps containers,
            final Snapshots snapshots,
            final Consumer<RunStage> progress,
            final Steps record) {
        this.run = run;
        this.containers = containers;
        this.snapshots = snapshots;
        this.progress = progress;
        this.record = record;
    }

    /** Reads the runtime of the project, or returns the reason the run must not start. */
    RuntimeResult check() {
        return containers.runtime();
    }

    /**
     * Stops every service the run moves, in that order.
     *
     * @return the services actually stopped, which {@link #start} and {@link #verify} act on
     */
    Stopped stop(final List<String> moving, final RuntimeResult runtime) {
        progress.accept(RunStage.STOPPING);
        final List<String> stopped = new ArrayList<>();
        for (final String name : moving) {
            final Steps.Step step = record.begin(RunStep.Kind.STOP, name);
            final ServiceRuntime service = runtime.service(name).orElse(null);
            if (service == null || service.containerId() == null) {
                step.failed(RunStep.Reason.NO_CONTAINER);
                continue;
            }
            final RedeployResult result = containers.stop(service.containerId());
            if (!result.triggered()) {
                step.failed(RunStep.Reason.REFUSED);
                continue;
            }
            stopped.add(name);
            if (result.verified()) {
                step.done();
            } else {
                // It did stop; nobody could read how it ended.
                step.warned(RunStep.Reason.UNVERIFIED_STOP);
                unverifiedStops.add(name);
            }
        }
        return new Stopped(List.copyOf(stopped), runtime);
    }

    /**
     * Saves every volume, one at a time, with the servers already stopped.
     *
     * @param volumes the Docker volume names, from the agent's backup mounts in compose.yml
     * @return what each save came back with, in the order of the volumes
     */
    List<SnapshotResult> save(final List<String> volumes) {
        progress.accept(RunStage.BACKING_UP);
        final List<SnapshotResult> saved = new ArrayList<>();
        for (final String volume : volumes) {
            final Steps.Step step = record.begin(RunStep.Kind.SAVE_VOLUME, volume);
            final SnapshotResult result = snapshots.save(volume);
            step.archive(result, unverifiedStops.isEmpty());
            step.ended(result);
            if (result.ok() && result.file() != null && !unverifiedStops.isEmpty()) {
                // The archive is kept: it is probably fine, and somebody must be told before restoring it.
                snapshots.markUnverified(result.file(), new Snapshots.Unverified(run, List.copyOf(unverifiedStops)));
            }
            saved.add(result);
        }
        return List.copyOf(saved);
    }

    /**
     * Returns the services whose stop had an end nobody could read, empty when every stop was clean.
     *
     * {@code Runner} settles such a run by its {@link Runner.Doubt}.
     */
    List<String> unverifiedStops() {
        return List.copyOf(unverifiedStops);
    }

    /** Starts everything this run stopped. */
    void start(final Stopped state) {
        start(state, ImageResult.of(Map.of()), List.of(), true);
    }

    /**
     * Starts everything this run stopped, recreating each service whose image {@link ImageResult} calls outdated.
     * Each recreate is written as a step before it starts, so a run that never returns from one names it.
     *
     * @param images the services to recreate on a newer image, empty when the run changes no version
     */
    void start(final Stopped state, final ImageResult images, final List<String> remade, final boolean pulls) {
        progress.accept(RunStage.STARTING);
        for (final String service : state.services()) {
            if (images.isOutdated(service) || remade.contains(service)) {
                startRemade(state, service, images.isOutdated(service) || pulls);
                continue;
            }
            final Steps.Step step = record.begin(RunStep.Kind.START, service);
            final ServiceRuntime entry = state.runtime().service(service).orElse(null);
            if (entry == null || entry.containerId() == null) {
                step.failed(RunStep.Reason.NO_CONTAINER);
                refused.add(service);
                continue;
            }
            final RedeployResult result = containers.start(entry.containerId());
            endBy(step, result);
            if (!result.triggered()) {
                refused.add(service);
            }
        }
    }

    /**
     * Recreates a service, or starts its old container when that fails.
     *
     * @param pulled whether the image is pulled first: always an outdated one, and a named recreate if its kind does
     */
    private void startRemade(final Stopped state, final String service, final boolean pulled) {
        final Steps.Step install = record.begin(RunStep.Kind.INSTALL, service)
                .change(IMAGE, null, null, pulled ? RunStep.Image.PULLED : RunStep.Image.KEPT);
        final RedeployResult recreated = pulled ? containers.deploy(service) : containers.recreate(service);
        if (recreated.triggered()) {
            install.done();
            return;
        }
        install.failed(RunStep.Reason.REFUSED);

        final Steps.Step fallback = record.begin(RunStep.Kind.START, service);
        final ServiceRuntime old = state.runtime().service(service).orElse(null);
        if (old == null || old.containerId() == null) {
            fallback.failed(RunStep.Reason.NO_CONTAINER);
            refused.add(service);
            return;
        }
        final RedeployResult back = containers.start(old.containerId());
        endBy(fallback, back);
        if (back.triggered()) {
            // Docker accepting a start is not a service coming back; verify() finishes this with what it saw.
            fellBack.add(service);
        } else {
            refused.add(service);
        }
    }

    /** Ends a step by whether Docker accepted the command. The verify step checks the start. */
    static void endBy(final Steps.Step step, final RedeployResult result) {
        if (result.triggered()) {
            step.done();
        } else {
            step.failed(RunStep.Reason.REFUSED);
        }
    }

    /** Waits until every started service passes its healthcheck, or says which one did not. */
    void verify(final List<String> services, final Waiting clock) {
        verify(services, clock, Map.of());
    }

    /**
     * Waits until every started service passes its healthcheck, or says which one did not.
     *
     * @param clock the time source, so a test needs no sleep
     * @param installed the artefacts each service was given, whose versions it came back with
     */
    void verify(final List<String> services, final Waiting clock, final Map<String, List<RunStep.Change>> installed) {
        progress.accept(RunStage.VERIFYING);
        final List<String> pending = new ArrayList<>(
                services.stream().filter(service -> !refused.contains(service)).toList());
        final Map<String, Steps.Step> verifying = new LinkedHashMap<>();
        pending.forEach(service -> verifying.put(service, record.begin(RunStep.Kind.VERIFY, service)));
        final Instant deadline = clock.now().plus(HEALTH_PATIENCE);
        final Map<String, Integer> restartsBefore = new LinkedHashMap<>();

        while (!pending.isEmpty()) {
            final RuntimeResult now = containers.runtime();
            if (now.reached()) {
                final List<String> settled = new ArrayList<>();
                for (final String service : pending) {
                    final ServiceRuntime state = now.service(service).orElse(null);
                    final Steps.Step step = Objects.requireNonNull(verifying.get(service));
                    if (state != null && state.isBack()) {
                        settled.add(service);
                        if (!fellBack.contains(service)) {
                            // No container tells its version, so these are the ones installed.
                            installed.getOrDefault(service, List.of()).stream()
                                    .filter(change -> change.to() != null)
                                    .forEach(change -> step.change(change.artefact(), null, change.to(), null));
                        }
                        step.done();
                        continue;
                    }
                    final RunStep.Reason ended = state == null ? null : gaveUp(state, restartsBefore);
                    if (ended != null) {
                        settled.add(service);
                        step.failed(ended);
                    }
                }
                pending.removeAll(settled);
            }
            if (pending.isEmpty()) {
                break;
            }
            if (!clock.now().isBefore(deadline)) {
                pending.forEach(service ->
                        Objects.requireNonNull(verifying.get(service)).failed(RunStep.Reason.NOT_HEALTHY));
                break;
            }
            if (!clock.sleep(HEALTH_POLL)) {
                pending.forEach(service ->
                        Objects.requireNonNull(verifying.get(service)).failed(RunStep.Reason.INTERRUPTED));
                break;
            }
        }
    }

    /**
     * Returns why the wait for a service ends early, or null while it may still come up.
     * A held refusal is the last line of its log, and a restart is a climb over the count first seen.
     */
    private RunStep.@Nullable Reason gaveUp(final ServiceRuntime state, final Map<String, Integer> restartsBefore) {
        final String id = state.containerId();
        if (id == null) {
            return null;
        }
        final List<String> last = containers.lastLines(id, 1);
        if (!last.isEmpty() && last.getLast().contains(REFUSED)) {
            final String line = last.getLast();
            log.warn(
                    "{} refused to start: {}",
                    state.service(),
                    line.substring(line.indexOf(REFUSED) + REFUSED.length()));
            return RunStep.Reason.REFUSED;
        }
        final int before = restartsBefore.computeIfAbsent(state.service(), ignored -> state.restarts());
        if (state.restarts() <= before) {
            return null;
        }
        final List<String> lines = containers.lastLines(id, RESTART_LINES);
        final String why = lines.reversed().stream()
                .filter(line ->
                        line.contains("FATAL") || line.contains("is not starting") || line.contains("server exited"))
                .findFirst()
                .orElse(lines.isEmpty() ? "its log could not be read" : lines.getLast());
        log.warn("{} restarted while it started: {}", state.service(), why.strip());
        return RunStep.Reason.NOT_HEALTHY;
    }

    /** What {@link #stop} produced, carried to the two steps after it. */
    record Stopped(List<String> services, RuntimeResult runtime) {}
}
