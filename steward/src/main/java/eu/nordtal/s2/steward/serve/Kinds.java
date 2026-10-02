package eu.nordtal.s2.steward.serve;

import eu.nordtal.s2.database.update.UpdateReport;
import eu.nordtal.s2.database.update.UpdateReports;
import eu.nordtal.s2.database.update.UpdateRequest;
import eu.nordtal.s2.internalapi.agent.AgentWire;
import eu.nordtal.s2.internalapi.agent.ImageResult;
import eu.nordtal.s2.internalapi.agent.Retention;
import eu.nordtal.s2.internalapi.agent.SnapshotResult;
import eu.nordtal.s2.internalapi.agent.Snapshots;
import eu.nordtal.s2.steward.apply.ApplyResult;
import eu.nordtal.s2.steward.config.BackupSpec;
import eu.nordtal.s2.steward.plan.PlanReport;
import eu.nordtal.s2.steward.plan.Topology;
import eu.nordtal.s2.steward.plan.UpdatePlan;
import eu.nordtal.s2.steward.run.Report;
import eu.nordtal.s2.steward.run.Runs;
import java.util.List;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/**
 * What each kind of run plans before anything moves; {@link Run} carries every plan out the same way.
 *
 * A kind answers either a {@link Run.Plan} or, when there is nothing to stop or it must refuse, an {@link Outcome}.
 */
@Slf4j
final class Kinds {

    /** The two services a take-down may never name, because the run reports through them. */
    private static final List<String> NEVER_DOWN = List.of(Topology.STEWARD, "postgres");

    private Kinds() {}

    /** A kind's answer: a plan to carry out, or the outcome when there is nothing to carry out. */
    record Planned(Run.@Nullable Plan plan, @Nullable Outcome outcome) {

        static Planned plan(final Run.Plan plan) {
            return new Planned(plan, null);
        }

        static Planned outcome(final Outcome outcome) {
            return new Planned(null, outcome);
        }
    }

    /** Resolves, installs what is new and recreates what has a newer image; refuses nothing that stops nobody. */
    static Planned update(final Runner runner, final UpdateRequest request, final Consumer<UpdateReport> progress) {
        progress.accept(UpdateReport.at(UpdateReport.Stage.RESOLVING));
        // After the runtime check, before anything is resolved: it belongs in the plan a person confirms.
        final ImageResult images = runner.containers.images();
        final List<String> scope = runner.directory.scopeOf(request.id());
        // Held services are removed first, since starting one to verify it is what the hold forbids.
        final List<String> holds = runner.held();
        final UpdatePlan plan = Runs.resolve(runner.config, runner.plugins, runner.settings())
                .onlyServices(scope)
                .withoutServices(holds);
        UpdateReport planned =
                ForeignImages.withImages(PlanReport.of(plan), images, scope).withoutLines(holds);
        final List<String> skipped = holds.stream()
                .filter(service -> scope.isEmpty() || scope.contains(service))
                .toList();
        if (!skipped.isEmpty()) {
            planned = planned.withNote(String.join(", ", skipped) + " is being held down and was"
                    + " left out of this run. Start it again and ask for the update once more.");
        }
        final List<String> foreign = ForeignImages.staleForeign(images).stream()
                // A scoped run renews a foreign image only when the scope names it.
                .filter(service -> scope.isEmpty() || scope.contains(service))
                // A held service is not renewed either: recreating its container is starting it.
                .filter(service -> !holds.contains(service))
                .toList();

        // First, so a newer steward migrates and installs this release.
        final Handover.Decision handover = Handover.decide(plan, Handover.ownVersion(), request.result());
        if (handover instanceof final Handover.Refuse refuse) {
            return Planned.outcome(Outcome.failed(UpdateReports.toJson(
                    planned.withStage(UpdateReport.Stage.FAILED).withNote(refuse.reason()))));
        }
        if (handover instanceof final Handover.HandOver handOver) {
            return Planned.outcome(handOver(runner, plan, planned, handOver, progress));
        }
        if (!planned.isWork() && foreign.isEmpty()) {
            // A third answer: nothing to check, no work, no failure, and no countdown.
            return Planned.outcome(Outcome.done(UpdateReports.toJson(planned.withStage(
                    plan.hasFailures() ? UpdateReport.Stage.FAILED : UpdateReport.Stage.NOTHING_TO_DO))));
        }

        final UpdateReport stopped = planned;
        return Planned.plan(Run.Plan.of(
                        stopped,
                        stopped.isWork() ? install(runner, plan, progress) : Run.Payload.NONE,
                        "NOTHING WAS INSTALLED: installing into a server that is still running is the failure this"
                                + " sequence exists to prevent.",
                        refusal -> "NOTHING WAS STOPPED AND NOTHING WAS INSTALLED. " + refusal
                                + ". This run stops a service whose players have to go somewhere, and the somewhere"
                                + " is that standby - so a standby that does not come up is a run that would take"
                                + " the network down with nowhere to put anybody.",
                        "this run",
                        "the jars were moved into its plugins directory",
                        Runner.Doubt.FAILS_THE_RUN)
                .renewing(images, foreign, plan.hasFailures()));
    }

    /** Migrates, then moves the plan's files into place, marking each stopped service by how its apply went. */
    private static Run.Payload install(
            final Runner runner, final UpdatePlan plan, final Consumer<UpdateReport> progress) {
        return (steps, state) -> {
            migrate(runner, state);
            UpdateReport report = state.report().withStage(UpdateReport.Stage.INSTALLING);
            progress.accept(report);
            final ApplyResult result = Runs.apply(runner.config, plan, runner.settings());
            report = report.withNote(Report.render(result));
            for (final String service : state.services()) {
                // Only where the apply succeeded; marking every stopped service INSTALLED here would be premature.
                final String failure = failureFor(result, service);
                report = report.with(
                        failure == null
                                ? report.line(service).at(UpdateReport.State.INSTALLED)
                                : report.line(service).failed(failure));
            }
            progress.accept(report);
            return new Run.Done(report, result.hasFailures());
        };
    }

    /** Applies the schema with the servers down; a failure starts them again before anything is installed. */
    private static void migrate(final Runner runner, final UpdateRun.Stopped state) {
        try {
            eu.nordtal.s2.steward.schema.Schema.migrate(runner.database);
        } catch (final RuntimeException failure) {
            log.error("The migration failed; no jar was touched", failure);
            throw new Run.Abort(state.report().withNote("THE MIGRATION FAILED AND NOTHING WAS INSTALLED: " + failure));
        }
    }

    /** Places the newer steward if needed and hands it the run; nothing is stopped. */
    private static Outcome handOver(
            final Runner runner,
            final UpdatePlan plan,
            final UpdateReport planned,
            final Handover.HandOver handOver,
            final Consumer<UpdateReport> progress) {
        UpdateReport report = planned;
        if (handOver.install()) {
            final ApplyResult result =
                    Runs.apply(runner.config, plan.onlyServices(List.of(Topology.STEWARD)), runner.settings());
            if (result.hasFailures()) {
                return Outcome.failed(UpdateReports.toJson(report.withStage(UpdateReport.Stage.FAILED)
                        .withNote(Report.render(result))
                        .withNote("NOTHING WAS STOPPED. The newer steward could not be placed, and this"
                                + " run is not carried out by an older steward than the release it installs.")));
            }
            report = report.with(report.line(Topology.STEWARD).at(UpdateReport.State.INSTALLED));
        }
        report = report.withStage(UpdateReport.Stage.RESOLVING).withNote(Handover.note(handOver.version()));
        progress.accept(report);
        log.info("Handing the run to steward {}", handOver.version());
        return Outcome.handedOver(UpdateReports.toJson(report));
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

    /** Stops the Minecraft services in scope and starts them again, installing nothing. */
    static Planned restart(final Runner runner, final UpdateRequest request) {
        final List<String> holds = runner.held();
        final List<String> scope = runner.directory.scopeOf(request.id());
        UpdateReport planned = UpdateReport.at(UpdateReport.Stage.STOPPING);
        for (final String service : Runner.restarted(scope, holds)) {
            planned = planned.with(new UpdateReport.ServiceLine(
                    service,
                    UpdateReport.State.PLANNED,
                    List.of(new UpdateReport.Change("restart", null, "no change")),
                    null));
        }
        if (planned.services().isEmpty()) {
            return Planned.outcome(Outcome.done(UpdateReports.toJson(UpdateReport.at(UpdateReport.Stage.NOTHING_TO_DO)
                    .withNote(
                            scope.isEmpty()
                                    ? "Every Minecraft service is being held down, so there was nothing to"
                                            + " restart. Nothing was stopped."
                                    : "Nothing in " + String.join(", ", scope) + " is a Minecraft service"
                                            + " this run may restart - either it is not one, or it is being"
                                            + " held down. Nothing was stopped."))));
        }
        final List<String> untouched = Topology.SERVICES.stream()
                .map(Topology.Service::name)
                .filter(holds::contains)
                .filter(service -> scope.isEmpty() || scope.contains(service))
                .toList();
        if (!untouched.isEmpty()) {
            planned = planned.withNote(String.join(", ", untouched) + " is being held down and was not"
                    + " restarted. It stays down until somebody starts it.");
        }
        return Planned.plan(Run.Plan.of(
                planned,
                Run.Payload.NONE,
                null,
                refusal -> "NOTHING WAS RESTARTED. " + refusal + ". Every service is still running exactly as it was.",
                "this restart",
                "it was started again on the same world",
                Runner.Doubt.IS_ONLY_SAID));
    }

    /**
     * Dumps the database with everything running, then stops what compose.yml labels, saves the volumes and prunes.
     *
     * compose.yml says what a backup saves and stops: the agent's mounts and the label {@code eu.nordtal.backup}.
     */
    static Planned backup(final Runner runner, final Consumer<UpdateReport> progress) {
        final AgentWire.Topology topology;
        try {
            topology = runner.containers.topology();
        } catch (final RuntimeException unread) {
            return Planned.outcome(Outcome.failed(UpdateReports.toJson(UpdateReport.at(UpdateReport.Stage.FAILED)
                    .withNote("steward-agent did not say what a backup saves, so nothing was stopped and"
                            + " nothing was saved: " + unread.getMessage()))));
        }
        if (topology.backupVolumes().isEmpty()) {
            // Not a quiet success: a compose.yml with no backup mounts must not take the network down for nothing.
            return Planned.outcome(Outcome.failed(UpdateReports.toJson(UpdateReport.at(UpdateReport.Stage.FAILED)
                    .withNote("compose.yml mounts no volume for steward-agent to back up, so there is nothing to"
                            + " save and nothing was stopped."))));
        }

        // The database first, with everything running: pg_dump's MVCC snapshot needs nothing stopped.
        final SnapshotResult dumped = runner.backups.saveDatabase();
        UpdateReport planned = UpdateReport.at(UpdateReport.Stage.STOPPING)
                .with(new UpdateReport.ServiceLine(
                        Snapshots.DATABASE,
                        dumped.ok() ? UpdateReport.State.SAVED : UpdateReport.State.FAILED,
                        List.of(new UpdateReport.Change("backup", null, dumped.message())),
                        dumped.ok() ? null : dumped.message()));
        progress.accept(planned);
        for (final String service : topology.stoppedForBackup()) {
            planned = planned.with(new UpdateReport.ServiceLine(
                    service,
                    UpdateReport.State.PLANNED,
                    List.of(new UpdateReport.Change("backup", null, "stopped while saving")),
                    null));
        }

        final List<String> volumes = topology.backupVolumes();
        final Run.Payload save = (steps, stopped) -> {
            final UpdateReport saved = steps.save(stopped.report(), volumes);
            // While the servers are still down: quick, and it frees disk before the next run.
            return new Run.Done(prune(runner, saved), false);
        };
        return Planned.plan(Run.Plan.of(
                planned,
                save,
                "NOTHING WAS SAVED: a snapshot of a running server is one that fails when somebody tries to"
                        + " restore it.",
                refusal -> "NOTHING WAS STOPPED AND NOTHING WAS SAVED. " + refusal
                        + ". The database dump above was taken with everything running and is real; the volumes"
                        + " were not touched.",
                "this backup",
                "the archives were taken - they were kept, and each one has a .unverified file beside it saying"
                        + " so, which `deploy/restore.sh --list` prints",
                Runner.Doubt.FAILS_THE_RUN));
    }

    /** What was kept and what was removed, put into the report so a wrong retention shows. */
    private static UpdateReport prune(final Runner runner, final UpdateReport saved) {
        final BackupSpec.RetentionSpec keep = runner.config.backup().retention();
        final Retention policy = new Retention(keep.daily(), keep.weekly(), keep.monthly(), keep.collapseAfterDays());
        final List<String> pruned = runner.backups.prune(policy);
        return pruned.isEmpty()
                ? saved
                : saved.withNote("kept " + policy.daily() + " daily, " + policy.weekly() + " weekly and "
                        + policy.monthly() + " monthly of each series, and removed " + pruned.size() + ": "
                        + String.join(", ", pruned));
    }

    /**
     * Stops the named services and leaves them stopped, recorded in {@code service_hold} so no run restarts them.
     *
     * An empty scope is refused rather than read as the whole network.
     */
    static Planned down(final Runner runner, final UpdateRequest request) {
        final List<String> scope = runner.directory.scopeOf(request.id());
        if (scope.isEmpty()) {
            return Planned.outcome(Outcome.failed(UpdateReports.toJson(UpdateReport.at(UpdateReport.Stage.FAILED)
                    .withNote("This request asks to put services down without naming any. Nothing"
                            + " was stopped: an unnamed scope means the whole network, and taking"
                            + " the whole network down until somebody presses Start is not"
                            + " something anybody asks for by leaving a field empty."))));
        }
        final List<String> refused = scope.stream().filter(NEVER_DOWN::contains).toList();
        if (!refused.isEmpty()) {
            return Planned.outcome(Outcome.failed(UpdateReports.toJson(UpdateReport.at(UpdateReport.Stage.FAILED)
                    .withNote("Nothing was stopped: " + String.join(", ", refused) + " cannot be"
                            + " put down from here. This sequence runs inside steward and"
                            + " writes its report through postgres, so a run that stopped either"
                            + " one could not say what it had done."))));
        }
        UpdateReport planned = UpdateReport.at(UpdateReport.Stage.STOPPING);
        for (final String service : scope) {
            planned = planned.with(new UpdateReport.ServiceLine(
                    service,
                    UpdateReport.State.PLANNED,
                    List.of(new UpdateReport.Change("down", null, "stays down")),
                    null));
        }
        final Run.Payload hold = (steps, stopped) -> {
            for (final String service : stopped.services()) {
                runner.directory.hold(service, request.actor(), request.id());
            }
            // Worded so that one service and four read the same.
            return new Run.Done(
                    stopped.services().isEmpty()
                            ? stopped.report()
                            : stopped.report()
                                    .withNote("Held down: " + String.join(", ", stopped.services())
                                            + ". Nothing starts a held service again on its own: not a later update"
                                            + " run, not a restart, and not steward coming back."),
                    false);
        };
        return Planned.plan(Run.Plan.of(
                        planned,
                        hold,
                        null,
                        refusal -> "NOTHING WAS STOPPED. " + refusal + ".",
                        "this take-down",
                        "it was put down on purpose",
                        Runner.Doubt.IS_ONLY_SAID)
                // Only when somebody could be standing on one of them, since the countdown warns players.
                .announced(scope.stream().anyMatch(Runner::isMinecraft))
                .leavingThemDown());
    }

    /**
     * Takes the hold off the services and starts them again, without a countdown and without stopping anything.
     *
     * An empty scope here means every held service, unlike {@link #down}.
     */
    static Planned start(final Runner runner, final UpdateRequest request) {
        final List<String> asked = runner.directory.scopeOf(request.id());
        final List<String> services = asked.isEmpty() ? runner.held() : asked;
        if (services.isEmpty()) {
            return Planned.outcome(Outcome.done(UpdateReports.toJson(UpdateReport.at(UpdateReport.Stage.NOTHING_TO_DO)
                    .withNote("No service is being held down, so there was nothing to start."))));
        }
        UpdateReport planned = UpdateReport.at(UpdateReport.Stage.STARTING);
        for (final String service : services) {
            planned = planned.with(new UpdateReport.ServiceLine(
                    service,
                    UpdateReport.State.STOPPED,
                    List.of(new UpdateReport.Change("down", "stays down", "starting")),
                    null));
        }
        final Run.Payload release = (steps, stopped) -> {
            // Comes off before the start, so a start that never returns leaves no row claiming a hold.
            for (final String service : services) {
                runner.directory.release(service);
            }
            return new Run.Done(stopped.report(), false);
        };
        return Planned.plan(Run.Plan.of(
                        planned,
                        release,
                        null,
                        refusal -> refusal,
                        "this start",
                        "it was started again",
                        Runner.Doubt.IS_ONLY_SAID)
                .stoppingNothing()
                .alsoStarting(services));
    }
}
