package eu.nordtal.s2.steward.worker.serve;

import eu.nordtal.jcore.persistence.sql.Database;
import eu.nordtal.s2.common.time.Waiting;
import eu.nordtal.s2.database.update.ServiceHold;
import eu.nordtal.s2.database.update.UpdateDirectory;
import eu.nordtal.s2.database.update.UpdateReport;
import eu.nordtal.s2.database.update.UpdateReports;
import eu.nordtal.s2.database.update.UpdateRequest;
import eu.nordtal.s2.database.update.UpdateStatus;
import eu.nordtal.s2.steward.worker.backup.Backups;
import eu.nordtal.s2.steward.worker.backup.DatabaseDump;
import eu.nordtal.s2.steward.worker.config.StewardSpec;
import eu.nordtal.s2.steward.worker.ops.ContainerOps;
import eu.nordtal.s2.steward.worker.ops.ImageResult;
import eu.nordtal.s2.steward.worker.ops.RuntimeResult;
import eu.nordtal.s2.steward.worker.plan.PlanReport;
import eu.nordtal.s2.steward.worker.plan.Topology;
import eu.nordtal.s2.steward.worker.plan.UpdatePlan;
import eu.nordtal.s2.steward.worker.run.Runs;
import eu.nordtal.s2.steward.worker.schema.RunLock;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/**
 * Carries out one claimed {@link UpdateRequest} and answers with an {@link UpdateReport}, never throwing.
 *
 * The countdown starts only after resolving, so players are warned only when the plan has work.
 */
@Slf4j
public final class Runner implements RequestRunner {

    /** How often the row is re-read while the countdown runs, so a cancel ends the wait at once. */
    private static final Duration COUNTDOWN_TICK = Duration.ofSeconds(1);

    final StewardSpec config;
    final Database database;
    final ContainerOps containers;
    final Backups backups;
    final UpdateDirectory directory;
    final Waiting waiting;

    /** The plugins an admin added, handed to every resolve; {@code PluginDirectory#NONE} by default. */
    final eu.nordtal.s2.steward.worker.plugin.PluginDirectory plugins;

    /** How many players are on a service, built on first use since a {@code REPORT} run never asks. */
    private volatile @Nullable Occupancy occupancy;

    Occupancy occupancy() {
        if (occupancy == null) {
            occupancy = Occupancy.over(database.dataSource(), waiting::now);
        }
        return occupancy;
    }

    /** {@code message()} is set whenever the runtime could not be reached, the only case this asks for it. */
    static String unreachableMessage(final RuntimeResult runtime) {
        return Objects.requireNonNull(runtime.message(), "unreachable result carries no message");
    }

    /** Package-visible so a test can drive a thirty-second countdown without waiting for one. */
    Runner(
            final StewardSpec config,
            final Database database,
            final ContainerOps containers,
            final Backups backups,
            final UpdateDirectory directory,
            final Waiting waiting) {
        this(
                config,
                database,
                containers,
                backups,
                directory,
                waiting,
                eu.nordtal.s2.steward.worker.plugin.PluginDirectory.NONE);
    }

    public Runner(
            final StewardSpec config,
            final Database database,
            final ContainerOps containers,
            final Backups backups,
            final UpdateDirectory directory,
            final Waiting waiting,
            final eu.nordtal.s2.steward.worker.plugin.PluginDirectory plugins) {
        this.plugins = plugins;
        this.config = config;
        this.database = database;
        this.containers = containers;
        this.backups = backups;
        this.directory = directory;
        this.waiting = waiting;
    }

    @Override
    public Outcome run(final UpdateRequest request, final Consumer<UpdateReport> progress) {
        try {
            return switch (request.kind()) {
                case REPORT -> report();
                // Retired and refused: this must never quietly swap jars underneath a running server.
                case APPLY ->
                    Outcome.failed(UpdateReports.toJson(UpdateReport.at(UpdateReport.Stage.FAILED)
                            .withNote("This request asks for the retired 'install without stopping'"
                                    + " step, which replaced jars underneath running servers. Ask for"
                                    + " an update instead - it stops each server first.")));
                case UPDATE -> update(request, progress);
                case RESTART -> restart(request, progress);
                case BACKUP -> backup(request, progress);
                case DOWN -> down(request, progress);
                case START -> startHeld(request, progress);
            };
        } catch (final RuntimeException failure) {
            log.error("Request {} ({}) failed", request.id(), request.kind(), failure);
            return Outcome.failed("This request failed: " + failure + "\nSteward-worker's log has the stack trace.");
        }
    }

    private Outcome report() {
        final UpdatePlan plan = Runs.resolve(config, plugins);
        // The images too, or an update that stops four servers could contradict this "nothing to do".
        final UpdateReport report = ForeignImages.withImages(PlanReport.of(plan), containers.images());
        // A plan with unchecked rows is still a report; failing the request would look like a broken worker.
        return Outcome.done(UpdateReports.toJson(
                report.withStage(report.isWork() ? UpdateReport.Stage.PLANNED : UpdateReport.Stage.NOTHING_TO_DO)));
    }

    /**
     * The whole sequence: check, resolve, count down (already spent), stop, migrate, swap, start, verify.
     *
     * The runtime is read before anything resolves, so a run that cannot stop a server never moves a jar.
     */
    private Outcome update(final UpdateRequest request, final Consumer<UpdateReport> progress) {
        final UpdateRun run = new UpdateRun(containers, backups.volumes(), progress);

        final RuntimeResult runtime = run.check();
        if (!runtime.reached()) {
            return Outcome.failed(UpdateReports.toJson(
                    UpdateReport.at(UpdateReport.Stage.FAILED).withNote(unreachableMessage(runtime))));
        }

        progress.accept(UpdateReport.at(UpdateReport.Stage.RESOLVING));

        final Optional<RunLock> lock;
        try {
            lock = RunLock.tryAcquire(database.dataSource());
        } catch (final SQLException failure) {
            return Outcome.failed(UpdateReports.toJson(UpdateReport.at(UpdateReport.Stage.FAILED)
                    .withNote("Could not reach the database to take the steward-worker lock: " + failure)));
        }
        if (lock.isEmpty()) {
            return Outcome.failed(UpdateReports.toJson(UpdateReport.at(UpdateReport.Stage.FAILED)
                    .withNote("Another steward-worker run is in progress - nothing was done. That"
                            + " is either another update or a `docker compose run --rm"
                            + " steward-worker bootstrap` somebody started on the host. Wait for it"
                            + " to finish and ask again.")));
        }

        // After the runtime check, before anything is resolved: it belongs in the plan a person confirms.
        final ImageResult images = containers.images();

        try (RunLock held = lock.get()) {
            final UpdateSequence.Preparation prep = UpdateSequence.prepareUpdate(this, request, images);
            // First, so a newer worker migrates and installs this release.
            final Handover.Decision handover = Handover.decide(prep.plan(), Handover.ownVersion(), request.result());
            if (handover instanceof final Handover.Refuse refuse) {
                return Outcome.failed(UpdateReports.toJson(
                        prep.planned().withStage(UpdateReport.Stage.FAILED).withNote(refuse.reason())));
            }
            if (handover instanceof final Handover.HandOver handOver) {
                return UpdateSequence.handOver(this, prep, handOver, progress);
            }
            if (!prep.planned().isWork() && !prep.foreign().isEmpty()) {
                return UpdateSequence.updateWithNoServer(this, run, prep, progress);
            }
            if (!prep.planned().isWork()) {
                // A third answer: nothing to check, no work, no failure, and no countdown.
                return Outcome.done(UpdateReports.toJson(prep.planned()
                        .withStage(
                                prep.plan().hasFailures()
                                        ? UpdateReport.Stage.FAILED
                                        : UpdateReport.Stage.NOTHING_TO_DO)));
            }
            return UpdateSequence.run(this, request, run, runtime, images, prep, progress);
        }
    }

    /**
     * Counts down on this run's own row to the database's instant, so the proxy's countdown agrees with it.
     *
     * @return {@code true} when the countdown ran out and the run may proceed; {@code false} when cancelled
     */
    boolean countDown(final long id, final UpdateReport planned, final Consumer<UpdateReport> progress) {
        final Optional<UpdateRequest> counting = directory.startCountdown(id, UpdateDirectory.UPDATE_COUNTDOWN);
        if (counting.isEmpty()) {
            // No longer RUNNING between the claim and here, which in practice means cancelled.
            log.info("Request {} is no longer running, so no countdown was started", id);
            return false;
        }
        progress.accept(planned.withStage(UpdateReport.Stage.COUNTDOWN));

        final Instant due = counting.get().notBefore();
        while (waiting.now().isBefore(due)) {
            final Duration left = Duration.between(waiting.now(), due);
            if (!waiting.sleep(left.compareTo(COUNTDOWN_TICK) < 0 ? left : COUNTDOWN_TICK)) {
                log.warn("The countdown for request {} was interrupted; nothing was stopped", id);
                return false;
            }
            // Ends the wait here, so a cancel is not ignored for the rest of the countdown.
            final boolean stillRunning = directory
                    .find(id)
                    .map(row -> row.status() == UpdateStatus.RUNNING)
                    .orElse(false);
            if (!stillRunning) {
                log.info("Request {} was cancelled during its countdown; nothing was stopped", id);
                return false;
            }
        }
        // The race at zero: a cancel arriving now either takes the row or is refused by SKIP LOCKED.
        if (!directory.commitCountdown(id)) {
            log.info("Request {} was cancelled as its countdown ran out; nothing was stopped", id);
            return false;
        }
        return true;
    }

    /** What an unverified stop costs on the path being settled. */
    enum Doubt {

        /** The run wrote an archive or jars while those servers were down, so it settles {@code FAILED}. */
        FAILS_THE_RUN,

        /** The run wrote nothing in between, so the doubt is only noted at run level. */
        IS_ONLY_SAID
    }

    /**
     * Settles a run that stopped servers, noting any stop whose ending nobody could read.
     *
     * @param verified the report after {@code verify}, with its stage not yet settled
     * @param unverified {@link UpdateRun#unverifiedStops()}
     * @param whatIsAtRisk what the run did meanwhile, as the middle of a sentence ("the jars were moved")
     * @param alreadyFailed whether something else has already failed this run
     * @param doubt what an unverified stop costs here
     * @return the report with its stage set, and the note on it when there was one to make
     */
    static UpdateReport settle(
            final UpdateReport verified,
            final List<String> unverified,
            final String whatIsAtRisk,
            final boolean alreadyFailed,
            final Doubt doubt) {
        final UpdateReport told = unverified.isEmpty()
                ? verified
                : verified.withNote("UNVERIFIED STOP. " + String.join(", ", unverified)
                        + " stopped, and how it ended could not be read back, so nothing here knows"
                        + " whether the server had finished writing when " + whatIsAtRisk + "."
                        + " Nothing was thrown away and nothing was undone."
                        + (doubt == Doubt.FAILS_THE_RUN
                                ? " This run is reported as FAILED for that reason alone, so"
                                        + " that nothing counts this archive as one."
                                : " This run is not reported as a failure over it: it left nothing"
                                        + " behind that anybody has to decide whether to trust."));
        final boolean failed = (doubt == Doubt.FAILS_THE_RUN && !unverified.isEmpty())
                || alreadyFailed
                || told.services().stream().anyMatch(line -> line.state() == UpdateReport.State.FAILED);
        return told.withStage(failed ? UpdateReport.Stage.FAILED : UpdateReport.Stage.DONE);
    }

    /**
     * Which of the services this run asked to stop are not among the ones that did.
     *
     * Only {@code PLANNED} lines count, so neither the database dump nor steward-worker itself is ever blamed.
     */
    static List<String> servicesThatRefused(final UpdateReport planned, final Collection<String> stopped) {
        return planned.services().stream()
                .filter(line -> line.state() == UpdateReport.State.PLANNED)
                .map(UpdateReport.ServiceLine::service)
                .filter(service -> !Topology.STEWARD_WORKER.equals(service))
                .filter(service -> !stopped.contains(service))
                .toList();
    }

    /** The answer to a run somebody stopped, usually discarded since the row is already {@code CANCELLED}. */
    static Outcome cancelled() {
        return Outcome.done(UpdateReports.toJson(UpdateReport.at(UpdateReport.Stage.CANCELLED)
                .withNote("Stopped during the countdown. Nothing was stopped and nothing was" + " installed.")));
    }

    /**
     * Counts down, stops the servers, snapshots the volumes, starts the servers and waits for them.
     *
     * Every path from the stop ends in a start, so a failed snapshot never leaves the network down.
     */
    private Outcome backup(final UpdateRequest request, final Consumer<UpdateReport> progress) {
        final UpdateRun run = new UpdateRun(containers, backups.volumes(), progress);

        final RuntimeResult runtime = run.check();
        if (!runtime.reached()) {
            return Outcome.failed(UpdateReports.toJson(
                    UpdateReport.at(UpdateReport.Stage.FAILED).withNote(unreachableMessage(runtime))));
        }

        final List<String> volumes = config.backup().volumes().stream()
                .filter(volume -> volume != null && !volume.isBlank())
                .map(String::trim)
                .toList();
        if (volumes.isEmpty()) {
            // Not a quiet success: an emptied volumes list must not take the network down for nothing.
            return Outcome.failed(UpdateReports.toJson(UpdateReport.at(UpdateReport.Stage.FAILED)
                    .withNote("backup.volumes in steward.yml is empty, so there is nothing to save"
                            + " and nothing was stopped.")));
        }

        // The same lock an update takes, so a backup never overlaps a run moving jars.
        final Optional<RunLock> lock;
        try {
            lock = RunLock.tryAcquire(database.dataSource());
        } catch (final SQLException failure) {
            return Outcome.failed(UpdateReports.toJson(UpdateReport.at(UpdateReport.Stage.FAILED)
                    .withNote("Could not reach the database to take the steward-worker lock: " + failure)));
        }
        if (lock.isEmpty()) {
            return Outcome.failed(UpdateReports.toJson(UpdateReport.at(UpdateReport.Stage.FAILED)
                    .withNote("Another steward-worker run is in progress - nothing was backed up."
                            + " That is either an update, a restart or a `docker compose run --rm"
                            + " steward-worker bootstrap` somebody started on the host. Wait for it"
                            + " and ask again.")));
        }
        try (RunLock held = lock.get()) {
            return BackupSequence.runUnderLock(this, request, run, runtime, volumes, progress);
        }
    }

    /** The same sequence with nothing installed: stop the servers, start them, wait for them. */
    private Outcome restart(final UpdateRequest request, final Consumer<UpdateReport> progress) {
        final UpdateRun run = new UpdateRun(containers, backups.volumes(), progress);

        final RuntimeResult runtime = run.check();
        if (!runtime.reached()) {
            return Outcome.failed(UpdateReports.toJson(
                    UpdateReport.at(UpdateReport.Stage.FAILED).withNote(unreachableMessage(runtime))));
        }

        // The same lock an update takes: `bootstrap` holds it while moving jars, and a restart must not race it.
        final Optional<RunLock> lock;
        try {
            lock = RunLock.tryAcquire(database.dataSource());
        } catch (final SQLException failure) {
            return Outcome.failed(UpdateReports.toJson(UpdateReport.at(UpdateReport.Stage.FAILED)
                    .withNote("Could not reach the database to take the steward-worker lock: " + failure)));
        }
        if (lock.isEmpty()) {
            return Outcome.failed(UpdateReports.toJson(UpdateReport.at(UpdateReport.Stage.FAILED)
                    .withNote("Another steward-worker run is in progress - nothing was restarted."
                            + " That is either an update or a `docker compose run --rm"
                            + " steward-worker bootstrap` somebody started on the host. Wait for it"
                            + " and ask again.")));
        }
        try (RunLock held = lock.get()) {
            return RestartSequence.runUnderLock(this, request, run, runtime, progress);
        }
    }

    /**
     * Which Minecraft services a restart takes round.
     *
     * @param scope what the request names, empty for the whole network
     * @param holds what somebody is deliberately keeping down, never restarted
     * @return the services to stop and start again, in {@link Topology}'s own order
     */
    static List<String> restarted(final List<String> scope, final List<String> holds) {
        return Topology.SERVICES.stream()
                .map(Topology.Service::name)
                .filter(service -> !holds.contains(service))
                .filter(service -> scope.isEmpty() || scope.contains(service))
                .toList();
    }

    /** The services somebody is deliberately holding down, in no particular order. */
    List<String> held() {
        return directory.holds().stream().map(ServiceHold::service).toList();
    }

    /** Stops the named services and leaves them stopped; see {@link HoldSequence#down}. */
    private Outcome down(final UpdateRequest request, final Consumer<UpdateReport> progress) {
        return HoldSequence.down(this, request, progress);
    }

    /** Takes the hold off named (or every held) services and starts them again; see {@link HoldSequence#startHeld}. */
    private Outcome startHeld(final UpdateRequest request, final Consumer<UpdateReport> progress) {
        return HoldSequence.startHeld(this, request, progress);
    }

    /** Whether that service is one of the four somebody can be standing on. */
    static boolean isMinecraft(final String service) {
        return Topology.SERVICES.stream().anyMatch(one -> one.name().equals(service));
    }

    /** The services a report says this run is going to stop, without steward-worker or the database dump line. */
    static List<String> movingServices(final UpdateReport report) {
        return report.services().stream()
                .filter(UpdateReport.ServiceLine::isMoving)
                .map(UpdateReport.ServiceLine::service)
                .filter(service -> !Topology.STEWARD_WORKER.equals(service))
                .filter(service -> !DatabaseDump.NAME.equals(service))
                .toList();
    }

    /**
     * Puts what {@link Choreography#close()} said into the report as notes, since a service line would be evacuated.
     */
    static UpdateReport noteStandbys(final UpdateReport report, final List<String> said) {
        UpdateReport told = report;
        for (final String sentence : said) {
            told = told.withNote(sentence);
        }
        return told;
    }
}
