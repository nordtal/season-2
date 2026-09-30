package eu.nordtal.s2.steward.worker.serve;

import eu.nordtal.s2.database.update.UpdateReport;
import eu.nordtal.s2.database.update.UpdateReports;
import eu.nordtal.s2.database.update.UpdateRequest;
import eu.nordtal.s2.steward.worker.apply.ApplyResult;
import eu.nordtal.s2.steward.worker.backup.DatabaseDump;
import eu.nordtal.s2.steward.worker.ops.ImageResult;
import eu.nordtal.s2.steward.worker.ops.RuntimeResult;
import eu.nordtal.s2.steward.worker.plan.PlanReport;
import eu.nordtal.s2.steward.worker.plan.Report;
import eu.nordtal.s2.steward.worker.plan.Topology;
import eu.nordtal.s2.steward.worker.plan.UpdatePlan;
import eu.nordtal.s2.steward.worker.run.Runs;
import java.util.List;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/**
 * The choreographed part of an update: resolve, open the standbys, count down, stop, migrate, install, start, verify.
 */
@Slf4j
final class UpdateSequence {

    private UpdateSequence() {}

    /** What an update needs to decide between its three outcomes: nothing, foreign-only, or the real sequence. */
    record Preparation(List<String> holds, UpdatePlan plan, UpdateReport planned, List<String> foreign) {}

    /** Resolves the plan, the report and the foreign images for one update, once, for every branch. */
    static Preparation prepareUpdate(final Runner runner, final UpdateRequest request, final ImageResult images) {
        final List<String> scope = runner.directory.scopeOf(request.id());
        // Held services are removed first, since starting one to verify it is what the hold forbids.
        final List<String> holds = runner.held();
        final UpdatePlan plan =
                Runs.resolve(runner.config, runner.plugins).onlyServices(scope).withoutServices(holds);
        UpdateReport planned =
                ForeignImages.withImages(PlanReport.of(plan), images, scope).withoutLines(holds);
        final List<String> skipped = holds.stream()
                .filter(service -> scope.isEmpty() || scope.contains(service))
                .toList();
        if (!skipped.isEmpty()) {
            planned = planned.withNote(String.join(", ", skipped) + " is being held down and was"
                    + " left out of this run. Start it again and ask for the update once more.");
        }
        // Worked out here, so both branches below agree on the same answer.
        final List<String> foreign = ForeignImages.staleForeign(images).stream()
                // A scoped run renews a foreign image only when the scope names it.
                .filter(service -> scope.isEmpty() || scope.contains(service))
                // A held service is not renewed either: recreating its container is starting it.
                .filter(service -> !holds.contains(service))
                .toList();
        return new Preparation(holds, plan, planned, foreign);
    }

    /** Places the newer steward-worker if needed and hands it the run; nothing is stopped. */
    static Outcome handOver(
            final Runner runner,
            final Preparation prep,
            final Handover.HandOver handOver,
            final Consumer<UpdateReport> progress) {
        UpdateReport report = prep.planned();
        if (handOver.install()) {
            final ApplyResult result =
                    Runs.apply(runner.config, prep.plan().onlyServices(List.of(Topology.STEWARD_WORKER)));
            if (result.hasFailures()) {
                return Outcome.failed(UpdateReports.toJson(report.withStage(UpdateReport.Stage.FAILED)
                        .withNote(Report.render(result))
                        .withNote("NOTHING WAS STOPPED. The newer steward-worker could not be placed, and this"
                                + " run is not carried out by an older worker than the release it installs.")));
            }
            report = report.with(report.line(Topology.STEWARD_WORKER).at(UpdateReport.State.INSTALLED));
        }
        report = report.withStage(UpdateReport.Stage.RESOLVING).withNote(Handover.note(handOver.version()));
        progress.accept(report);
        log.info("Handing the run to steward-worker {}", handOver.version());
        return Outcome.handedOver(UpdateReports.toJson(report));
    }

    /** A run with no server in it: nobody is stopped, so nobody needs a countdown. */
    static Outcome updateWithNoServer(
            final Runner runner, final UpdateRun run, final Preparation prep, final Consumer<UpdateReport> progress) {
        final UpdateReport renewed = ForeignImages.renewForeign(
                runner.containers,
                run,
                prep.planned().withStage(UpdateReport.Stage.INSTALLING),
                prep.foreign(),
                progress);
        final UpdateReport settled = Runner.settle(
                renewed,
                run.unverifiedStops(),
                "its image was renewed",
                prep.plan().hasFailures(),
                Runner.Doubt.FAILS_THE_RUN);
        return settled.stage() == UpdateReport.Stage.FAILED
                ? Outcome.failed(UpdateReports.toJson(settled))
                : Outcome.done(UpdateReports.toJson(settled));
    }

    /** The choreographed part of an update: open the standbys, count down, stop, migrate, install, start, verify. */
    static Outcome run(
            final Runner runner,
            final UpdateRequest request,
            final UpdateRun run,
            final RuntimeResult runtime,
            final ImageResult images,
            final Preparation prep,
            final Consumer<UpdateReport> progress) {
        final Choreography choreography = new Choreography(runner.containers, runner.occupancy(), runner.waiting);
        UpdateReport planned;
        try {
            planned = openUpdateStandbys(choreography, prep.planned(), progress);
        } catch (final EarlyOutcome early) {
            return early.outcome;
        }
        try {
            if (!runner.countDown(request.id(), planned, progress)) {
                return Runner.cancelled();
            }

            // After the countdown the run waits for the players to move, then stops regardless after ten seconds.
            final String stillOn = choreography.waitUntilEmpty(Runner.movingServices(planned));
            if (stillOn != null) {
                planned = planned.withNote(stillOn);
                progress.accept(planned);
            }

            final UpdateRun.Stopped stopped = run.stop(planned, runtime);

            final Outcome refused = refuseIfNotStopped(planned, stopped, run, runtime);
            if (refused != null) {
                return refused;
            }

            final Installed installed;
            try {
                installed = migrateAndApply(runner, run, stopped, runtime, prep.plan(), images, progress);
            } catch (final EarlyOutcome early) {
                return early.outcome;
            }

            // Last, once the Minecraft services are healthy again; see FOREIGN_IMAGES.
            final UpdateReport renewed =
                    ForeignImages.renewForeign(runner.containers, run, installed.verified(), prep.foreign(), progress);

            final UpdateReport told = Runner.noteStandbys(renewed, choreography.close());
            final UpdateReport finished = Runner.settle(
                    told,
                    run.unverifiedStops(),
                    "the jars were moved into its plugins directory",
                    installed.hasFailures(),
                    Runner.Doubt.FAILS_THE_RUN);
            return finished.stage() == UpdateReport.Stage.FAILED
                    ? Outcome.failed(UpdateReports.toJson(finished))
                    : Outcome.done(UpdateReports.toJson(finished));
        } finally {
            // Every exit closes the standbys, so none is left running all night.
            choreography.close();
        }
    }

    /** Opens the standbys an update needs before anybody is warned, or throws {@link EarlyOutcome} when one fails. */
    private static UpdateReport openUpdateStandbys(
            final Choreography choreography, final UpdateReport planned, final Consumer<UpdateReport> progress) {
        final Choreography.Window window = choreography.open(Runner.movingServices(planned));
        if (!window.opened()) {
            throw new EarlyOutcome(Outcome.failed(UpdateReports.toJson(planned.withStage(UpdateReport.Stage.FAILED)
                    .withNote("NOTHING WAS STOPPED AND NOTHING WAS INSTALLED. " + window.refusal()
                            + ". This run stops a service whose players have to go somewhere,"
                            + " and the somewhere is that standby - so a standby that does not"
                            + " come up is a run that would take the network down with nowhere"
                            + " to put anybody."))));
        }
        if (window.isEmpty()) {
            return planned;
        }
        final UpdateReport told = planned.withNote(String.join(", ", window.standbys())
                + " started and healthy, so this run has somewhere to put the players.");
        progress.accept(told);
        return told;
    }

    /** Refuses the run when a service that has work did not stop, or {@code null} when every one of them did. */
    private static @Nullable Outcome refuseIfNotStopped(
            final UpdateReport planned,
            final UpdateRun.Stopped stopped,
            final UpdateRun run,
            final RuntimeResult runtime) {
        final List<String> notStopped = planned.services().stream()
                // isMoving, matching UpdateRun#stop: an artefact with no build yet was never asked to stop.
                .filter(UpdateReport.ServiceLine::isMoving)
                .map(UpdateReport.ServiceLine::service)
                .filter(service -> !Topology.STEWARD_WORKER.equals(service))
                // Same exemption as above: only a backup's report carries a `database` line.
                .filter(service -> !DatabaseDump.NAME.equals(service))
                .filter(service -> !stopped.services().contains(service))
                .toList();
        if (notStopped.isEmpty()) {
            return null;
        }
        final UpdateReport back = run.start(new UpdateRun.Stopped(
                stopped.report()
                        .withNote("NOTHING WAS INSTALLED. " + String.join(", ", notStopped)
                                + " could not be stopped, and installing into a server"
                                + " that is still running is the failure this sequence exists to"
                                + " prevent. Every service that did stop has been started again."),
                stopped.services(),
                runtime));
        return Outcome.failed(UpdateReports.toJson(
                run.verify(back, stopped.services(), UpdateRun.Waiting.real()).withStage(UpdateReport.Stage.FAILED)));
    }

    /** What {@link #migrateAndApply} produced: the verified report, and whether the install had any failure in it. */
    private record Installed(UpdateReport verified, boolean hasFailures) {}

    /** Thrown out of {@link #migrateAndApply} to end the sequence early, carrying the {@link Outcome} to return. */
    private static final class EarlyOutcome extends RuntimeException {

        private final Outcome outcome;

        EarlyOutcome(final Outcome outcome) {
            super(null, null, false, false);
            this.outcome = outcome;
        }
    }

    /** Migrates, applies the plan, starts and verifies the stopped services, or throws {@link EarlyOutcome}. */
    private static Installed migrateAndApply(
            final Runner runner,
            final UpdateRun run,
            final UpdateRun.Stopped stopped,
            final RuntimeResult runtime,
            final UpdatePlan plan,
            final ImageResult images,
            final Consumer<UpdateReport> progress) {
        try {
            eu.nordtal.s2.steward.worker.schema.Schema.migrate(runner.database);
        } catch (final RuntimeException failure) {
            log.error("The migration failed; no jar was touched", failure);
            // Started again before this is reported, so a failed migration never leaves the network stopped.
            final UpdateReport back = run.start(new UpdateRun.Stopped(
                    stopped.report().withNote("THE MIGRATION FAILED AND NOTHING WAS INSTALLED: " + failure),
                    stopped.services(),
                    runtime));
            throw new EarlyOutcome(
                    Outcome.failed(UpdateReports.toJson(run.verify(back, stopped.services(), UpdateRun.Waiting.real())
                            .withStage(UpdateReport.Stage.FAILED))));
        }

        UpdateReport report = stopped.report().withStage(UpdateReport.Stage.INSTALLING);
        progress.accept(report);
        final ApplyResult result = Runs.apply(runner.config, plan);
        report = report.withNote(Report.render(result));
        for (final String service : stopped.services()) {
            // Only where the apply succeeded; marking every stopped service INSTALLED here would be premature.
            final String failure = failureFor(result, service);
            report = report.with(
                    failure == null
                            ? report.line(service).at(UpdateReport.State.INSTALLED)
                            : report.line(service).failed(failure));
        }
        progress.accept(report);

        final UpdateReport started = run.start(new UpdateRun.Stopped(report, stopped.services(), runtime), images);
        final UpdateReport verified = run.verify(started, stopped.services(), UpdateRun.Waiting.real());
        return new Installed(verified, result.hasFailures());
    }

    /** Why one service's install did not happen, or {@code null} when it did, counting {@code SKIPPED} as failed. */
    private static @Nullable String failureFor(final ApplyResult result, final String service) {
        return result.outcomes().stream()
                .filter(outcome -> service.equals(outcome.service()))
                .filter(outcome ->
                        outcome.status() == ApplyResult.Status.FAILED || outcome.status() == ApplyResult.Status.SKIPPED)
                .findFirst()
                .map(outcome -> outcome.artifact() + ": "
                        + (outcome.detail() == null
                                ? outcome.status().name().toLowerCase(java.util.Locale.ROOT)
                                : outcome.detail()))
                .orElse(null);
    }
}
