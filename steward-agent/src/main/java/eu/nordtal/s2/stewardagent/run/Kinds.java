package eu.nordtal.s2.stewardagent.run;

import eu.nordtal.s2.database.inbox.StewardRequest;
import eu.nordtal.s2.database.update.UpdateReport;
import eu.nordtal.s2.database.update.UpdateReports;
import eu.nordtal.s2.database.update.UpdateRequest;
import eu.nordtal.s2.internalapi.agent.AgentWire;
import eu.nordtal.s2.internalapi.agent.ImageResult;
import eu.nordtal.s2.internalapi.agent.Retention;
import eu.nordtal.s2.internalapi.agent.SnapshotResult;
import eu.nordtal.s2.internalapi.agent.Topology;
import eu.nordtal.s2.stewardagent.apply.ApplyResult;
import eu.nordtal.s2.stewardagent.config.RunSpec.BackupSpec;
import eu.nordtal.s2.stewardagent.plan.PlanReport;
import eu.nordtal.s2.stewardagent.plan.UpdatePlan;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/**
 * What each kind of run plans before anything moves; {@link Run} carries every plan out the same way.
 *
 * A kind answers either a {@link Run.Plan} or, when there is nothing to stop or it must refuse, an {@link Outcome}.
 */
@Slf4j
final class Kinds {

    /** The two services a take-down may never name: Steward is where Start is pressed, postgres holds the run. */
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

        // First: an agent installs only the release whose schema it carries, so a newer one renews the agent.
        final String newer = Release.refusal(plan, Release.ownVersion());
        if (newer != null) {
            return Planned.outcome(Outcome.failed(UpdateReports.toJson(
                    planned.withStage(UpdateReport.Stage.FAILED).withNote(newer))));
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
                            + " put down from here. Steward is where Start is pressed, and this"
                            + " run writes its report through postgres, so a run that stopped either"
                            + " one could not be undone or say what it had done."))));
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
                                            + " run, not a restart, and not steward-agent coming back."),
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

    /**
     * Makes the named services' containers again, from the image on this host or from a pulled one.
     *
     * Ours are stopped and made again as they start; caddy, pack-host and postgres once the rest is back.
     */
    static Planned remake(final Runner runner, final UpdateRequest request, final boolean pull) {
        final String verb = pull ? "deploy" : "recreate";
        final List<String> scope = runner.directory.scopeOf(request.id());
        if (scope.isEmpty()) {
            return failed("This " + verb + " names no service. Nothing was stopped: a container is made again"
                    + " one service at a time, by name.");
        }
        if (scope.contains(AgentWire.SERVICE)) {
            return failed("Nothing was stopped: steward-agent carries this run out, so it cannot make its own"
                    + " container again. `./nordtal.sh` on the host renews it.");
        }
        final List<String> unknown = scope.stream()
                .filter(service -> !ForeignImages.RECREATABLE.contains(service))
                .filter(service -> !ForeignImages.FOREIGN_IMAGES.contains(service))
                .toList();
        if (!unknown.isEmpty()) {
            return failed("Nothing was stopped: " + String.join(", ", unknown) + " is not a service a run may"
                    + " make again.");
        }
        final List<String> holds = runner.held();
        final List<String> ours = scope.stream()
                .filter(ForeignImages.RECREATABLE::contains)
                .filter(service -> !holds.contains(service))
                .toList();
        // In renewal order, postgres last, since the report goes through it.
        final List<String> theirs = ForeignImages.FOREIGN_IMAGES.stream()
                .filter(scope::contains)
                .filter(service -> !holds.contains(service))
                .toList();
        UpdateReport planned = UpdateReport.at(UpdateReport.Stage.STOPPING);
        for (final String service : ours) {
            planned = planned.with(new UpdateReport.ServiceLine(
                    service,
                    UpdateReport.State.PLANNED,
                    List.of(new UpdateReport.Change(
                            "container", null, pull ? "made again from a pulled image" : "made again")),
                    null));
        }
        final List<String> skipped = scope.stream().filter(holds::contains).toList();
        if (!skipped.isEmpty()) {
            planned = planned.withNote(String.join(", ", skipped) + " is being held down and was left out: making"
                    + " its container again would start it.");
        }
        if (ours.isEmpty() && theirs.isEmpty()) {
            return Planned.outcome(
                    Outcome.done(UpdateReports.toJson(planned.withStage(UpdateReport.Stage.NOTHING_TO_DO))));
        }
        return Planned.plan(Run.Plan.of(
                        planned,
                        Run.Payload.NONE,
                        "NOTHING WAS MADE AGAIN:",
                        refusal -> "NOTHING WAS STOPPED. " + refusal + ".",
                        "this " + verb,
                        "its container was made again",
                        Runner.Doubt.IS_ONLY_SAID)
                // Postgres and Caddy carry every server's connections, so they are announced like a stop.
                .announced(ours.stream().anyMatch(Runner::isMinecraft) || !theirs.isEmpty())
                .remaking(ours, theirs, pull));
    }

    /** Stops the one server, deletes an added plugin's jar and data folder, and starts it again. */
    static Planned removePlugin(final Runner runner, final UpdateRequest request) {
        final StewardRequest asked = runner.directory.requestOf(request.id()).orElse(null);
        if (!(asked instanceof StewardRequest.RemovePlugin removal)) {
            return failed("This row does not say which plugin to remove. Nothing was stopped.");
        }
        final String service = removal.services().getFirst();
        final String artifact = removal.artifact();
        if (!runner.removal.has(service, artifact)) {
            return failed("Nothing was stopped: " + service + " has no added plugin " + artifact + ". The"
                    + " plugins the network gives are not in that list and cannot be removed.");
        }
        final boolean held = runner.held().contains(service);
        final UpdateReport planned = UpdateReport.at(UpdateReport.Stage.STOPPING)
                .with(new UpdateReport.ServiceLine(
                        service,
                        // A held server is already down, so the run neither stops nor starts it.
                        held ? UpdateReport.State.STOPPED : UpdateReport.State.PLANNED,
                        List.of(new UpdateReport.Change("plugin", artifact, "removed")),
                        null));
        final Run.Payload remove = (steps, stopped) -> {
            try {
                final List<String> deleted = runner.removal.remove(service, artifact);
                return new Run.Done(
                        stopped.report()
                                .withNote(
                                        deleted.isEmpty()
                                                ? artifact + " had nothing installed; it is off the list."
                                                : "Removed " + String.join(", ", deleted) + "."),
                        false);
            } catch (final RuntimeException refused) {
                return new Run.Done(
                        stopped.report().withNote("The plugin was not removed: " + refused.getMessage()), true);
            }
        };
        final Run.Plan plan = Run.Plan.of(
                planned,
                remove,
                "NOTHING WAS REMOVED: a running server keeps a deleted plugin loaded and its folder open.",
                refusal -> "NOTHING WAS STOPPED AND NOTHING WAS REMOVED. " + refusal + ".",
                "this removal",
                "the plugin was deleted",
                Runner.Doubt.IS_ONLY_SAID);
        return Planned.plan(held ? plan.stoppingNothing().leavingThemDown() : plan);
    }

    /**
     * Puts one archive back, after a fresh backup of what it replaces, while everything that runs on it is down.
     *
     * A volume archive stops the services that mount the volume; a dump stops every service on the database.
     */
    static Planned restore(final Runner runner, final UpdateRequest request) {
        final StewardRequest asked = runner.directory.requestOf(request.id()).orElse(null);
        if (!(asked instanceof StewardRequest.Restore restore)) {
            return failed("This row does not say which archive to restore. Nothing was stopped.");
        }
        final String archive = restore.archive();
        final String series = runner.backups.seriesOf(archive).orElse(null);
        if (series == null) {
            return failed("Nothing was stopped: " + archive + " is not a finished archive in the backups.");
        }
        return Snapshots.DATABASE.equals(series)
                ? restoreDatabase(runner, request, archive)
                : restoreVolume(runner, archive, series);
    }

    private static Planned restoreVolume(final Runner runner, final String archive, final String volume) {
        final AgentWire.Topology topology = runner.containers.topology();
        if (!topology.backupVolumes().contains(volume)) {
            return failed("Nothing was stopped: " + volume + " is not a volume compose.yml lets steward-agent"
                    + " back up, so it cannot put " + archive + " back either.");
        }
        final List<String> users = running(runner, topology.usersOf(volume));
        final Run.Payload put = (steps, stopped) -> {
            // The volume as it is now, so a restore of the wrong archive is itself undone by a restore.
            final UpdateReport saved = steps.save(stopped.report(), List.of(volume));
            if (saved.line(volume).state() != UpdateReport.State.SAVED) {
                throw new Run.Abort(saved.withNote("NOTHING WAS RESTORED: " + volume + " could not be saved as it"
                        + " is first, and a restore without that backup could not be taken back."));
            }
            return putBack(saved, runner.backups.restore(archive), archive);
        };
        return Planned.plan(Run.Plan.of(
                        stopping(users, "restore", archive),
                        put,
                        "NOTHING WAS RESTORED: putting files back under a running server is what this run stops it"
                                + " for.",
                        refusal -> "NOTHING WAS STOPPED AND NOTHING WAS RESTORED. " + refusal + ".",
                        "this restore",
                        "the archive was put back",
                        Runner.Doubt.FAILS_THE_RUN)
                .announced(users.stream().anyMatch(Runner::isMinecraft)));
    }

    private static Planned restoreDatabase(final Runner runner, final UpdateRequest request, final String dump) {
        // With everything running, as every backup takes it, and before anything is stopped.
        final SnapshotResult dumped = runner.backups.saveDatabase();
        if (!dumped.ok()) {
            return failed("Nothing was stopped and the database was not touched: the database could not be"
                    + " saved as it is first. " + dumped.message());
        }
        final List<String> users = running(runner, List.copyOf(ForeignImages.RECREATABLE));
        final UpdateReport planned = stopping(users, "restore", dump)
                .with(new UpdateReport.ServiceLine(
                        Snapshots.DATABASE,
                        UpdateReport.State.SAVED,
                        List.of(new UpdateReport.Change("backup", null, dumped.message())),
                        null));
        final Run.Payload replace = (steps, stopped) -> {
            // The dump has this run's row as it was then, or not at all; it is carried across and put back.
            final String row = runner.directory.carry(request.id()).orElse(null);
            final SnapshotResult restored = runner.backups.restoreDatabase(dump);
            if (restored.ok() && row != null) {
                runner.directory.putBack(
                        row,
                        "The database was restored from " + dump + ", which held this"
                                + " run open; it was not carried out after the restore.");
            }
            return putBack(stopped.report(), restored, dump);
        };
        return Planned.plan(Run.Plan.of(
                planned,
                replace,
                "NOTHING WAS RESTORED: a service writing into the database while it is replaced writes into"
                        + " the one being thrown away.",
                refusal -> "NOTHING WAS STOPPED AND NOTHING WAS RESTORED. " + refusal + ".",
                "this restore",
                "the database was replaced",
                Runner.Doubt.FAILS_THE_RUN));
    }

    /** The archive's own line on the report, and a failed run when it did not go back. */
    private static Run.Done putBack(final UpdateReport report, final SnapshotResult result, final String archive) {
        final UpdateReport.ServiceLine line = new UpdateReport.ServiceLine(
                archive,
                result.ok() ? UpdateReport.State.INSTALLED : UpdateReport.State.FAILED,
                List.of(new UpdateReport.Change("restore", null, result.message())),
                result.ok() ? null : result.message());
        return new Run.Done(
                result.ok()
                        ? report.with(line)
                        : report.with(line)
                                .withNote("The restore failed and the backup taken just before it holds what was"
                                        + " there."),
                !result.ok());
    }

    /** Of these services, the ones that run now and are not held, which a restore stops and starts again. */
    private static List<String> running(final Runner runner, final List<String> services) {
        final List<String> holds = runner.held();
        final eu.nordtal.s2.internalapi.agent.RuntimeResult runtime = runner.containers.runtime();
        return Stream.concat(
                        Topology.SERVICES.stream().map(Topology.Service::name),
                        ForeignImages.RECREATABLE.stream().sorted())
                .distinct()
                .filter(services::contains)
                .filter(service -> !holds.contains(service))
                .filter(service -> runtime.service(service)
                        .map(state -> "running".equalsIgnoreCase(state.status()))
                        .orElse(false))
                .toList();
    }

    /** A report with one planned line per service, each naming what it is stopped for. */
    private static UpdateReport stopping(final List<String> services, final String why, final String archive) {
        UpdateReport planned = UpdateReport.at(UpdateReport.Stage.STOPPING);
        for (final String service : services) {
            planned = planned.with(new UpdateReport.ServiceLine(
                    service, UpdateReport.State.PLANNED, List.of(new UpdateReport.Change(why, null, archive)), null));
        }
        return planned;
    }

    private static Planned failed(final String note) {
        return Planned.outcome(Outcome.failed(
                UpdateReports.toJson(UpdateReport.at(UpdateReport.Stage.FAILED).withNote(note))));
    }
}
