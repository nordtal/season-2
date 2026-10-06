package eu.nordtal.season.stewardagent.run;

import static eu.nordtal.season.database.AdminTexts.TEXTS;

import eu.nordtal.season.database.inbox.StewardRequest;
import eu.nordtal.season.database.update.ByteSize;
import eu.nordtal.season.database.update.UpdateKind;
import eu.nordtal.season.database.update.UpdateReport;
import eu.nordtal.season.database.update.UpdateReports;
import eu.nordtal.season.database.update.UpdateRequest;
import eu.nordtal.season.internalapi.agent.AgentWire;
import eu.nordtal.season.internalapi.agent.ImageResult;
import eu.nordtal.season.internalapi.agent.RedeployResult;
import eu.nordtal.season.internalapi.agent.Retention;
import eu.nordtal.season.internalapi.agent.SnapshotResult;
import eu.nordtal.season.internalapi.agent.Topology;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.stewardagent.apply.ApplyResult;
import eu.nordtal.season.stewardagent.config.RunSpec.BackupSpec;
import eu.nordtal.season.stewardagent.plan.PlanReport;
import eu.nordtal.season.stewardagent.plan.UpdatePlan;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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

    /** Resolves, installs what is new and recreates what is out of date; refuses nothing that stops nobody. */
    static Planned update(final Runner runner, final UpdateRequest request, final Consumer<UpdateReport> progress) {
        progress.accept(UpdateReport.at(UpdateReport.Stage.RESOLVING));
        // After the runtime check, before anything is resolved: it belongs in the plan a person confirms.
        final ImageResult images = runner.containers.images();
        final List<String> scope = runner.directory.scopeOf(request.id());
        // Held services are removed first, since starting one to verify it is what the hold forbids.
        final List<String> holds = runner.held();
        final AgentWire.Topology topology = runner.topology();
        final UpdatePlan plan = Runs.resolve(runner.config, topology, runner.plugins, runner.settings())
                .onlyServices(scope)
                .withoutServices(holds);
        UpdateReport planned = ForeignImages.withImages(PlanReport.of(plan), images, topology, scope)
                .withoutLines(holds);
        final List<String> skipped = holds.stream()
                .filter(service -> scope.isEmpty() || scope.contains(service))
                .toList();
        if (!skipped.isEmpty()) {
            planned = planned.withNote(TEXTS.report().heldLeftOut(skipped));
        }
        final List<String> foreign = ForeignImages.staleForeign(topology, images).stream()
                // A scoped run renews a foreign image only when the scope names it.
                .filter(service -> scope.isEmpty() || scope.contains(service))
                // A held service is not renewed either: recreating its container is starting it.
                .filter(service -> !holds.contains(service))
                .toList();

        // Before anything is handed over or stopped: a build made here goes only when whoever asked said so.
        final Release.Standing standing = Release.of(plan.seasonTag(), Release.ownVersion());
        final List<String> replacedLocal =
                LocalBuilds.replaced(images, plan, planned, foreign, standing == Release.Standing.NEWER, scope, holds);
        if (standing != Release.Standing.OLDER && !replacedLocal.isEmpty() && !replacesLocal(runner, request)) {
            return refused(planned, TEXTS.report().localBuildsKept(replacedLocal, replacedLocal.size()));
        }

        // First: an agent carries out runs of its own release only, and never recreates itself.
        final Planned elsewhere = elsewhere(runner, request, plan, planned, images, scope, progress);
        if (elsewhere != null) {
            return elsewhere;
        }
        if (!planned.isWork() && foreign.isEmpty()) {
            // Nothing to check, no work, no failure, no countdown; a one-shot still renews the agent.
            return runner.oneShot
                    ? Planned.plan(onlyTheAgent(planned))
                    : Planned.outcome(Outcome.done(UpdateReports.toJson(planned.withStage(
                            plan.hasFailures() ? UpdateReport.Stage.FAILED : UpdateReport.Stage.NOTHING_TO_DO))));
        }

        final UpdateReport stopped = planned;
        return Planned.plan(Run.Plan.of(
                        stopped,
                        pruningImages(
                                runner.containers,
                                stopped.isWork() ? install(runner, plan, progress) : Run.Payload.NONE),
                        UpdateReport.Undertaking.INSTALL,
                        true,
                        Runner.Doubt.FAILS_THE_RUN)
                .renewing(images, foreign, plan.hasFailures()));
    }

    /** Whether the request confirmed that builds made on this host are replaced. */
    private static boolean replacesLocal(final Runner runner, final UpdateRequest request) {
        return runner.directory
                .requestOf(request.id())
                .map(asked -> asked instanceof StewardRequest.Update update && update.replacesLocal())
                .orElse(false);
    }

    /** The payload, and once everything is back, the images nothing uses any more removed as a note. */
    static Run.Payload pruningImages(final ContainerOps containers, final Run.Payload payload) {
        return (steps, stopped) -> {
            final Run.Done done = payload.carryOut(steps, stopped);
            return new Run.Done(done.report(), done.failed(), back -> {
                final UpdateReport after = done.afterwards().apply(back);
                final ContainerOps.Pruned pruned = containers.pruneImages();
                return after.withNote(
                        pruned.failure() == null
                                ? TEXTS.report()
                                        .imagesPruned(
                                                pruned.images(),
                                                ByteSize.of(pruned.freedBytes()).message())
                                : TEXTS.report().imagesNotPruned(pruned.failure()));
            });
        };
    }

    /**
     * Refuses a release no agent may install here, or hands the run to a one-shot; {@code null} to go ahead here.
     *
     * A newer release goes to a one-shot at it, and so does an out-of-date steward-agent, at this release.
     */
    private static @Nullable Planned elsewhere(
            final Runner runner,
            final UpdateRequest request,
            final UpdatePlan plan,
            final UpdateReport planned,
            final ImageResult images,
            final List<String> scope,
            final Consumer<UpdateReport> progress) {
        final String own = Release.ownVersion();
        final String tag = plan.seasonTag();
        switch (Release.of(tag, own)) {
            case OWN -> {}
            case NEWER -> {
                return runner.oneShot
                        ? refused(planned, TEXTS.report().releasedMeanwhile(String.valueOf(tag), String.valueOf(own)))
                        : handOver(runner, request, planned, Release.version(Objects.requireNonNull(tag)), progress);
            }
            case OLDER -> {
                return refused(planned, TEXTS.report().olderRelease(String.valueOf(tag), String.valueOf(own)));
            }
        }
        if (!runner.oneShot
                && own != null
                && images.isOutdated(AgentWire.SERVICE)
                && (scope.isEmpty() || scope.contains(AgentWire.SERVICE))) {
            return handOver(runner, request, planned, own, progress);
        }
        return null;
    }

    /** A one-shot's run with only the agent left behind, which it renews last; nobody is moved for it. */
    private static Run.Plan onlyTheAgent(final UpdateReport planned) {
        return Run.Plan.of(
                        planned,
                        Run.Payload.NONE,
                        UpdateReport.Undertaking.RENEW_AGENT,
                        false,
                        Runner.Doubt.IS_ONLY_SAID)
                .stoppingNothing();
    }

    /** A run that ends before anything moved, with the reason as the report's note. */
    private static Planned refused(final UpdateReport planned, final MessageRef why) {
        return Planned.outcome(Outcome.failed(UpdateReports.toJson(
                planned.withStage(UpdateReport.Stage.FAILED).withNote(why))));
    }

    /**
     * Hands the claimed run to a one-shot steward-agent at {@code release}, writing its name on the row first.
     *
     * Nothing has stopped: the one-shot plans again, counts down and carries the whole run out at its own release.
     */
    private static Planned handOver(
            final Runner runner,
            final UpdateRequest request,
            final UpdateReport planned,
            final String release,
            final Consumer<UpdateReport> progress) {
        if (!runner.directory.handOver(request.id(), runner.containers.oneShot())) {
            // No longer RUNNING since the claim, which in practice means cancelled.
            return Planned.outcome(Runner.cancelled());
        }
        final UpdateReport handed = planned.withNote(TEXTS.report().handed(release));
        progress.accept(handed);
        final RedeployResult started = runner.containers.handOver(request.id(), release);
        if (!started.triggered()) {
            return Planned.outcome(Outcome.failed(UpdateReports.toJson(handed.withStage(UpdateReport.Stage.FAILED)
                    .withNote(TEXTS.report().oneShotNotStarted(release, started.message())))));
        }
        log.info("Request {} is handed to {} at release {}", request.id(), runner.containers.oneShot(), release);
        return Planned.outcome(Outcome.handedOver(UpdateReports.toJson(handed)));
    }

    /**
     * Migrates, then moves the plan's files into place, marking each stopped service by how its apply went.
     *
     * The migration runs here, with the servers stopped, so none of them meets a schema it was not built for.
     */
    private static Run.Payload install(
            final Runner runner, final UpdatePlan plan, final Consumer<UpdateReport> progress) {
        return (steps, state) -> {
            UpdateReport report = state.report().withStage(UpdateReport.Stage.INSTALLING);
            progress.accept(report);
            final RedeployResult migrated = runner.containers.migrate();
            if (!migrated.triggered()) {
                throw new Run.Abort(report.withNote(TEXTS.report().notMigrated(migrated.message())));
            }
            final ApplyResult result =
                    Runs.apply(runner.config, runner.topology(), plan, runner.settings(), runner.plugins);
            for (final ApplyResult.Outcome outcome : result.outcomes()) {
                // A file of no service, the resource pack, has no line to fail on.
                if (outcome.service() == null && didNotGoIn(outcome)) {
                    report = report.withNote(notInstalled(outcome));
                }
            }
            for (final String service : state.services()) {
                // Only where the apply succeeded; marking every stopped service INSTALLED here would be premature.
                final MessageRef failure = failureFor(result, service);
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
    private static @Nullable MessageRef failureFor(final ApplyResult result, final String service) {
        return result.outcomes().stream()
                .filter(outcome -> service.equals(outcome.service()))
                .filter(Kinds::didNotGoIn)
                .findFirst()
                .map(Kinds::notInstalled)
                .orElse(null);
    }

    private static boolean didNotGoIn(final ApplyResult.Outcome outcome) {
        return outcome.status() == ApplyResult.Status.FAILED || outcome.status() == ApplyResult.Status.SKIPPED;
    }

    /** An artefact that did not go in, with the applier's reason or its status word when it gave none. */
    private static MessageRef notInstalled(final ApplyResult.Outcome outcome) {
        return TEXTS.report()
                .notInstalled(
                        outcome.artifact(),
                        outcome.detail() == null
                                ? outcome.status().name().toLowerCase(java.util.Locale.ROOT)
                                : outcome.detail());
    }

    /** Stops the Minecraft services in scope and starts them again, installing nothing. */
    static Planned restart(final Runner runner, final UpdateRequest request) {
        final List<String> holds = runner.held();
        final List<String> scope = runner.directory.scopeOf(request.id());
        UpdateReport planned = UpdateReport.at(UpdateReport.Stage.STOPPING);
        final List<String> servers = runner.servers();
        for (final String service : Runner.restarted(servers, scope, holds)) {
            planned = planned.with(new UpdateReport.ServiceLine(
                    service,
                    UpdateReport.State.PLANNED,
                    List.of(UpdateReport.Change.told("restart", TEXTS.report().nothingChanges())),
                    null));
        }
        if (planned.services().isEmpty()) {
            return Planned.outcome(Outcome.done(UpdateReports.toJson(UpdateReport.at(UpdateReport.Stage.NOTHING_TO_DO)
                    .withNote(
                            scope.isEmpty()
                                    ? TEXTS.report().restartAllHeld()
                                    : TEXTS.report().restartNoneInScope(scope)))));
        }
        final List<String> untouched = servers.stream()
                .filter(holds::contains)
                .filter(service -> scope.isEmpty() || scope.contains(service))
                .toList();
        if (!untouched.isEmpty()) {
            planned = planned.withNote(TEXTS.report().heldNotRestarted(untouched));
        }
        return Planned.plan(Run.Plan.of(
                planned, Run.Payload.NONE, UpdateReport.Undertaking.RESTART, false, Runner.Doubt.IS_ONLY_SAID));
    }

    /**
     * Dumps the database, saves the volumes, prunes, and copies the newest archives off this host once all is back.
     *
     * compose.yml says what a backup saves and stops: the agent's mounts and the label {@code eu.nordtal.backup}.
     */
    static Planned backup(final Runner runner, final Consumer<UpdateReport> progress) {
        final AgentWire.Topology topology;
        try {
            topology = runner.containers.topology();
        } catch (final RuntimeException unread) {
            return Planned.outcome(Outcome.failed(UpdateReports.toJson(UpdateReport.at(UpdateReport.Stage.FAILED)
                    .withNote(TEXTS.report().backupUnread(String.valueOf(unread.getMessage()))))));
        }
        if (topology.backupVolumes().isEmpty()) {
            // Not a quiet success: a compose.yml with no backup mounts must not take the network down for nothing.
            return Planned.outcome(Outcome.failed(UpdateReports.toJson(UpdateReport.at(UpdateReport.Stage.FAILED)
                    .withNote(TEXTS.report().noBackupVolumes()))));
        }

        final List<String> volumes = topology.backupVolumes();
        // Swept first, so a disk the budget can clear does not refuse the backup.
        final UpdateReport swept = prune(runner, UpdateReport.at(UpdateReport.Stage.STOPPING), volumes);
        final Planned noRoom = refusedWithoutRoom(runner, swept, volumes);
        if (noRoom != null) {
            return noRoom;
        }

        // The database first, with everything running: pg_dump's MVCC snapshot needs nothing stopped.
        final SnapshotResult dumped = runner.backups.saveDatabase();
        UpdateReport planned = swept.with(new UpdateReport.ServiceLine(
                Snapshots.DATABASE,
                dumped.ok() ? UpdateReport.State.SAVED : UpdateReport.State.FAILED,
                List.of(UpdateRun.backedUp(dumped)),
                dumped.ok() ? null : TEXTS.report().words(String.valueOf(dumped.message()))));
        progress.accept(planned);
        // A server that is down already holds a whole world, and one held down stays down.
        for (final String service : running(runner, topology.stoppedForBackup())) {
            planned = planned.with(new UpdateReport.ServiceLine(
                    service,
                    UpdateReport.State.PLANNED,
                    List.of(UpdateReport.Change.told("backup", TEXTS.report().stoppedWhileSaving())),
                    null));
        }

        final Run.Payload save = (steps, stopped) -> {
            final UpdateReport saved = steps.save(stopped.report(), volumes);
            // While the servers are still down: quick, and it frees disk before the next run.
            return new Run.Done(prune(runner, saved, volumes), false, back -> offsite(runner, back, progress));
        };
        return Planned.plan(
                Run.Plan.of(planned, save, UpdateReport.Undertaking.BACKUP, true, Runner.Doubt.FAILS_THE_RUN));
    }

    /** The newest archives copied off this host once the servers are back, as a line of the report. */
    private static UpdateReport offsite(
            final Runner runner, final UpdateReport back, final Consumer<UpdateReport> progress) {
        final Optional<SnapshotResult> copied = runner.backups.copyOffsite(policyOf(runner));
        if (copied.isEmpty()) {
            return back.withNote(TEXTS.report().noOffsite());
        }
        final SnapshotResult result = copied.get();
        final UpdateReport reported = back.with(new UpdateReport.ServiceLine(
                Snapshots.OFFSITE,
                result.ok() ? UpdateReport.State.SAVED : UpdateReport.State.FAILED,
                List.of(UpdateRun.backedUp(result)),
                result.ok() ? null : TEXTS.report().words(String.valueOf(result.message()))));
        progress.accept(reported);
        return reported;
    }

    private static Retention policyOf(final Runner runner) {
        final BackupSpec.RetentionSpec keep = runner.config.backup().retention();
        return new Retention(keep.daily(), keep.weekly(), keep.monthly(), keep.collapseAfterDays());
    }

    /** What was kept and what was removed, put into the report so a wrong retention or budget shows. */
    private static UpdateReport prune(final Runner runner, final UpdateReport saved, final List<String> inBackup) {
        final Retention policy = policyOf(runner);
        final Snapshots.Pruned pruned = runner.backups.prune(policy, inBackup);
        UpdateReport report = saved;
        if (!pruned.expired().isEmpty()) {
            report = report.withNote(TEXTS.report()
                    .pruned(
                            policy.daily(),
                            policy.weekly(),
                            policy.monthly(),
                            pruned.expired().size(),
                            pruned.expired()));
        }
        if (!pruned.overBudget().isEmpty()) {
            report = report.withNote(TEXTS.report()
                    .prunedOverBudget(
                            runner.config.backup().budgetPercent(),
                            pruned.overBudget().size(),
                            pruned.overBudget()));
        }
        return report;
    }

    /** A failed run that stopped nothing when the backup would not leave the disk its free share; else null. */
    private static @Nullable Planned refusedWithoutRoom(
            final Runner runner, final UpdateReport report, final List<String> volumes) {
        final Snapshots.Room room = runner.backups.room(volumes);
        if (room.fits()) {
            return null;
        }
        return Planned.outcome(Outcome.failed(UpdateReports.toJson(report.withStage(UpdateReport.Stage.FAILED)
                .withNote(TEXTS.report()
                        .backupWontFit(
                                ByteSize.of(room.expectedBytes()).message(),
                                ByteSize.of(Math.max(0, room.usableBytes())).message(),
                                runner.config.backup().keepFreePercent(),
                                ByteSize.of(room.reserveBytes()).message())))));
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
                    .withNote(TEXTS.report().downUnnamed()))));
        }
        final List<String> refused = scope.stream().filter(NEVER_DOWN::contains).toList();
        if (!refused.isEmpty()) {
            return Planned.outcome(Outcome.failed(UpdateReports.toJson(UpdateReport.at(UpdateReport.Stage.FAILED)
                    .withNote(TEXTS.report().downRefused(refused)))));
        }
        UpdateReport planned = UpdateReport.at(UpdateReport.Stage.STOPPING);
        for (final String service : scope) {
            planned = planned.with(new UpdateReport.ServiceLine(
                    service,
                    UpdateReport.State.PLANNED,
                    List.of(UpdateReport.Change.told("down", TEXTS.report().staysDown())),
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
                            : stopped.report().withNote(TEXTS.report().heldDown(stopped.services())),
                    false);
        };
        return Planned.plan(
                Run.Plan.of(planned, hold, UpdateReport.Undertaking.TAKE_DOWN, false, Runner.Doubt.IS_ONLY_SAID)
                        // Only when somebody could be standing on one of them, since the countdown warns players.
                        .announced(scope.stream().anyMatch(runner::isMinecraft))
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
                    .withNote(TEXTS.report().nothingHeld()))));
        }
        UpdateReport planned = UpdateReport.at(UpdateReport.Stage.STARTING);
        for (final String service : services) {
            planned = planned.with(new UpdateReport.ServiceLine(
                    service,
                    UpdateReport.State.STOPPED,
                    List.of(UpdateReport.Change.told("down", TEXTS.report().startsAgain())),
                    null));
        }
        final Run.Payload release = (steps, stopped) -> {
            // Comes off before the start, so a start that never returns leaves no row claiming a hold.
            for (final String service : services) {
                runner.directory.release(service);
            }
            return new Run.Done(stopped.report(), false);
        };
        return Planned.plan(
                Run.Plan.of(planned, release, UpdateReport.Undertaking.START, false, Runner.Doubt.IS_ONLY_SAID)
                        .stoppingNothing()
                        .alsoStarting(services));
    }

    /**
     * Makes the named services' containers again, from the image on this host or from a pulled one.
     *
     * Ours are stopped and made again as they start; caddy, pack-host and postgres once the rest is back.
     */
    static Planned remake(final Runner runner, final UpdateRequest request, final boolean pull) {
        final List<String> scope = runner.directory.scopeOf(request.id());
        if (scope.isEmpty()) {
            return failed(TEXTS.report().remakeUnnamed(pull ? UpdateKind.DEPLOY : UpdateKind.RECREATE));
        }
        if (scope.contains(AgentWire.SERVICE)) {
            return failed(TEXTS.report().remakeAgent());
        }
        final AgentWire.Topology topology = runner.topology();
        final List<String> recreatable = topology.renewed(AgentWire.Renewal.RUN);
        final List<String> foreign = ForeignImages.foreign(topology);
        final List<String> unknown = scope.stream()
                .filter(service -> !recreatable.contains(service))
                .filter(service -> !foreign.contains(service))
                .toList();
        if (!unknown.isEmpty()) {
            return failed(TEXTS.report().remakeUnknown(unknown));
        }
        final List<String> holds = runner.held();
        final List<String> ours = scope.stream()
                .filter(recreatable::contains)
                .filter(service -> !holds.contains(service))
                .toList();
        // In renewal order, postgres last, since the report goes through it.
        final List<String> theirs = foreign.stream()
                .filter(scope::contains)
                .filter(service -> !holds.contains(service))
                .toList();
        UpdateReport planned = remadeLines(ours, pull);
        final List<String> skipped = scope.stream().filter(holds::contains).toList();
        if (!skipped.isEmpty()) {
            planned = planned.withNote(TEXTS.report().heldNotRemade(skipped));
        }
        if (ours.isEmpty() && theirs.isEmpty()) {
            return Planned.outcome(
                    Outcome.done(UpdateReports.toJson(planned.withStage(UpdateReport.Stage.NOTHING_TO_DO))));
        }
        return Planned.plan(Run.Plan.of(
                        planned,
                        Run.Payload.NONE,
                        pull ? UpdateReport.Undertaking.DEPLOY : UpdateReport.Undertaking.RECREATE,
                        true,
                        Runner.Doubt.IS_ONLY_SAID)
                // Postgres and Caddy carry every server's connections, so they are announced like a stop.
                .announced(ours.stream().anyMatch(runner::isMinecraft) || !theirs.isEmpty())
                .remaking(ours, theirs, pull));
    }

    /** A remake's plan before it starts: one planned line for each of our services it makes again. */
    private static UpdateReport remadeLines(final List<String> ours, final boolean pull) {
        UpdateReport planned = UpdateReport.at(UpdateReport.Stage.STOPPING);
        for (final String service : ours) {
            planned = planned.with(new UpdateReport.ServiceLine(
                    service,
                    UpdateReport.State.PLANNED,
                    List.of(UpdateReport.Change.told("container", TEXTS.report().madeAgain(pull))),
                    null));
        }
        return planned;
    }

    /** Stops the one server, deletes an added plugin's jar and data folder, and starts it again. */
    static Planned removePlugin(final Runner runner, final UpdateRequest request) {
        final StewardRequest asked = runner.directory.requestOf(request.id()).orElse(null);
        if (!(asked instanceof StewardRequest.RemovePlugin removal)) {
            return failed(TEXTS.report().removalUnnamed());
        }
        final String service = removal.services().getFirst();
        final String artifact = removal.artifact();
        if (!runner.removal.has(service, artifact)) {
            return failed(TEXTS.report().removalUnknown(service, artifact));
        }
        final boolean held = runner.held().contains(service);
        final UpdateReport planned = UpdateReport.at(UpdateReport.Stage.STOPPING)
                .with(new UpdateReport.ServiceLine(
                        service,
                        // A held server is already down, so the run neither stops nor starts it.
                        held ? UpdateReport.State.STOPPED : UpdateReport.State.PLANNED,
                        List.of(UpdateReport.Change.told(
                                artifact, TEXTS.report().pluginRemoved())),
                        null));
        final Run.Payload remove = (steps, stopped) -> {
            try {
                final List<String> deleted = runner.removal.remove(service, artifact);
                return new Run.Done(
                        stopped.report()
                                .withNote(
                                        deleted.isEmpty()
                                                ? TEXTS.report().removalEmpty(artifact)
                                                : TEXTS.report().removed(deleted)),
                        false);
            } catch (final RuntimeException refused) {
                return new Run.Done(
                        stopped.report().withNote(TEXTS.report().removalFailed(String.valueOf(refused.getMessage()))),
                        true);
            }
        };
        final Run.Plan plan =
                Run.Plan.of(planned, remove, UpdateReport.Undertaking.REMOVE_PLUGIN, true, Runner.Doubt.IS_ONLY_SAID);
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
            return failed(TEXTS.report().restoreUnnamed());
        }
        final String archive = restore.archive();
        final String series = runner.backups.seriesOf(archive).orElse(null);
        if (series == null) {
            return failed(TEXTS.report().restoreUnknown(archive));
        }
        return Snapshots.DATABASE.equals(series)
                ? restoreDatabase(runner, request, archive)
                : restoreVolume(runner, request, archive, series);
    }

    private static Planned restoreVolume(
            final Runner runner, final UpdateRequest request, final String archive, final String volume) {
        final AgentWire.Topology topology = runner.containers.topology();
        if (!topology.backupVolumes().contains(volume)) {
            return failed(TEXTS.report().restoreNotAVolume(volume, archive));
        }
        final Planned noRoom =
                refusedWithoutRoom(runner, UpdateReport.at(UpdateReport.Stage.STOPPING), List.of(volume));
        if (noRoom != null) {
            return noRoom;
        }
        final List<String> users = running(runner, topology.usersOf(volume));
        final Run.Payload put = (steps, stopped) -> {
            // The volume as it is now, so a restore of the wrong archive is itself undone by a restore.
            final UpdateReport saved = steps.save(stopped.report(), List.of(volume));
            if (saved.line(volume).state() != UpdateReport.State.SAVED) {
                throw new Run.Abort(saved.withNote(TEXTS.report().restoreUnsaved(volume)));
            }
            final Snapshots.Restored restored = runner.backups.restore(archive);
            if (restored.result().ok() || !restored.touched()) {
                return putBack(saved, restored.result(), archive);
            }
            // Started on a half-emptied volume, a server writes defaults over the gaps or makes a new world.
            final List<String> mounting = topology.usersOf(volume);
            for (final String service : mounting) {
                runner.directory.hold(service, request.actor(), request.id());
            }
            final String backup = steps.archiveOf(volume).orElse(volume);
            final Run.Done failed = putBack(
                    saved, restored.result(), archive, TEXTS.report().restoreLeftDown(volume, mounting, backup));
            return new Run.Done(failed.report(), true, java.util.function.UnaryOperator.identity(), mounting);
        };
        return Planned.plan(Run.Plan.of(
                        stopping(users, archive),
                        put,
                        UpdateReport.Undertaking.RESTORE_VOLUME,
                        true,
                        Runner.Doubt.FAILS_THE_RUN)
                .announced(users.stream().anyMatch(runner::isMinecraft)));
    }

    private static Planned restoreDatabase(final Runner runner, final UpdateRequest request, final String dump) {
        // With everything running, as every backup takes it, and before anything is stopped.
        final SnapshotResult dumped = runner.backups.saveDatabase();
        if (!dumped.ok()) {
            return failed(TEXTS.report().restoreDatabaseUnsaved(String.valueOf(dumped.message())));
        }
        final List<String> users = running(runner, runner.topology().renewed(AgentWire.Renewal.RUN));
        final UpdateReport planned = stopping(users, dump)
                .with(new UpdateReport.ServiceLine(
                        Snapshots.DATABASE, UpdateReport.State.SAVED, List.of(UpdateRun.backedUp(dumped)), null));
        final Run.Payload replace = (steps, stopped) -> {
            // The dump has this run's row as it was then, or not at all; it is carried across and put back.
            final String row = runner.directory.carry(request.id()).orElse(null);
            final SnapshotResult restored = runner.backups.restoreDatabase(dump);
            if (restored.ok() && row != null) {
                runner.directory.putBack(row, TEXTS.report().restoredOver(dump));
            }
            return putBack(stopped.report(), restored, dump);
        };
        return Planned.plan(Run.Plan.of(
                planned, replace, UpdateReport.Undertaking.RESTORE_DATABASE, true, Runner.Doubt.FAILS_THE_RUN));
    }

    /** The archive's own line on the report, and a failed run when it did not go back. */
    private static Run.Done putBack(final UpdateReport report, final SnapshotResult result, final String archive) {
        return putBack(report, result, archive, TEXTS.report().restoreFailed());
    }

    /** The same, with the note a failure carries. */
    private static Run.Done putBack(
            final UpdateReport report, final SnapshotResult result, final String archive, final MessageRef failure) {
        final UpdateReport.ServiceLine line = new UpdateReport.ServiceLine(
                archive,
                result.ok() ? UpdateReport.State.INSTALLED : UpdateReport.State.FAILED,
                List.of(UpdateReport.Change.told(
                        "restore",
                        result.ok()
                                ? TEXTS.report()
                                        .restored(ByteSize.of(result.bytes()).message())
                                : TEXTS.report().notRestored())),
                result.ok() ? null : TEXTS.report().words(String.valueOf(result.message())));
        return new Run.Done(result.ok() ? report.with(line) : report.with(line).withNote(failure), !result.ok());
    }

    /** Of these services, the ones that run now and are not held, which a restore stops and starts again. */
    private static List<String> running(final Runner runner, final List<String> services) {
        final List<String> holds = runner.held();
        final eu.nordtal.season.internalapi.agent.RuntimeResult runtime = runner.containers.runtime();
        return Stream.concat(runner.servers().stream(), services.stream().sorted())
                .distinct()
                .filter(services::contains)
                .filter(service -> !holds.contains(service))
                .filter(service -> runtime.service(service)
                        .map(state -> "running".equalsIgnoreCase(state.status()))
                        .orElse(false))
                .toList();
    }

    /** A report with one planned line per service, each naming the archive it is stopped for. */
    private static UpdateReport stopping(final List<String> services, final String archive) {
        UpdateReport planned = UpdateReport.at(UpdateReport.Stage.STOPPING);
        for (final String service : services) {
            planned = planned.with(new UpdateReport.ServiceLine(
                    service,
                    UpdateReport.State.PLANNED,
                    List.of(UpdateReport.Change.told("restore", TEXTS.report().stoppedForRestore(archive))),
                    null));
        }
        return planned;
    }

    private static Planned failed(final MessageRef note) {
        return Planned.outcome(Outcome.failed(
                UpdateReports.toJson(UpdateReport.at(UpdateReport.Stage.FAILED).withNote(note))));
    }
}
