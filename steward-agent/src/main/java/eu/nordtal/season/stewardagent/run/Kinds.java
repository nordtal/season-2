package eu.nordtal.season.stewardagent.run;

import eu.nordtal.season.database.inbox.StewardRequest;
import eu.nordtal.season.database.update.RunStage;
import eu.nordtal.season.database.update.RunStep;
import eu.nordtal.season.database.update.UpdateRequest;
import eu.nordtal.season.internalapi.agent.AgentWire;
import eu.nordtal.season.internalapi.agent.ImageResult;
import eu.nordtal.season.internalapi.agent.RedeployResult;
import eu.nordtal.season.internalapi.agent.Retention;
import eu.nordtal.season.internalapi.agent.SnapshotResult;
import eu.nordtal.season.internalapi.agent.Topology;
import eu.nordtal.season.stewardagent.apply.ApplyResult;
import eu.nordtal.season.stewardagent.config.RunSpec.BackupSpec;
import eu.nordtal.season.stewardagent.plan.UpdatePlan;
import eu.nordtal.season.stewardagent.plan.Work;
import eu.nordtal.season.stewardagent.schema.Schema;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/**
 * The planner of each kind of run. {@link Run} carries every plan out the same way.
 *
 * A planner returns a {@link Run.Plan}, or an {@link Outcome} when there is nothing to stop or it refuses.
 */
@Slf4j
final class Kinds {

    /**
     * The services a take-down never names: Steward is where Start is pressed, and postgres holds the run.
     */
    private static final List<String> NEVER_DOWN = List.of(Topology.STEWARD, "postgres");

    private static final String RELEASE = "release";

    private static final String SCHEMA = "schema";

    private Kinds() {}

    record Planned(Run.@Nullable Plan plan, @Nullable Outcome outcome) {

        static Planned plan(final Run.Plan plan) {
            return new Planned(plan, null);
        }

        static Planned outcome(final Outcome outcome) {
            return new Planned(null, outcome);
        }
    }

    static Planned update(
            final Runner runner, final UpdateRequest request, final Consumer<RunStage> progress, final Steps record) {
        final Steps.Step resolving = record.begin(RunStep.Kind.RESOLVE);
        // The image check belongs in the plan that a person confirms.
        final ImageResult images = runner.containers.images();
        final List<String> scope = runner.directory.scopeOf(request.id());
        // Held services are removed first, since starting one to verify it is what the hold forbids.
        final List<String> holds = runner.held();
        final AgentWire.Topology topology = runner.topology();
        final UpdatePlan plan = Runs.resolve(runner.config, topology, runner.plugins, runner.settings())
                .onlyServices(scope)
                .withoutServices(holds);
        final Work planned =
                ForeignImages.withImages(Work.of(plan), images, topology, scope).without(holds);
        skipped(
                record,
                holds.stream()
                        .filter(service -> scope.isEmpty() || scope.contains(service))
                        .toList(),
                RunStep.Reason.HELD);
        Resolved.services(record, planned);
        Release.changed(plan.seasonTag(), Release.ownVersion())
                .ifPresent(release -> resolving.change(RELEASE, Release.ownVersion(), release, null));
        final List<String> foreign = renewed(ForeignImages.staleForeign(topology, images), scope, holds);

        // A build made on this host is replaced only when the request said so.
        final Release.Standing standing = Release.of(plan.seasonTag(), Release.ownVersion());
        final List<String> replacedLocal =
                LocalBuilds.replaced(images, plan, planned, foreign, standing == Release.Standing.NEWER, scope, holds);
        if (standing != Release.Standing.OLDER && !replacedLocal.isEmpty() && !replacesLocal(runner, request)) {
            log.info("Request {} would replace the local builds of {}, so it is refused", request.id(), replacedLocal);
            resolving.failed(RunStep.Reason.REFUSED_LOCAL_BUILD);
            return failed();
        }

        // An agent carries out only runs of its own release and never recreates itself.
        final Planned elsewhere = elsewhere(runner, request, plan, planned, images, scope, record, resolving);
        if (elsewhere != null) {
            return elsewhere;
        }
        Resolved.run(planned).on(resolving);
        if (!planned.isWork() && foreign.isEmpty()) {
            // A one-shot with no work still recreates the agent.
            return runner.oneShot
                    ? Planned.plan(onlyTheAgent())
                    : Planned.outcome(Outcome.at(plan.hasFailures() ? RunStage.FAILED : RunStage.NOTHING_TO_DO));
        }

        return Planned.plan(Run.Plan.of(
                        planned.moving(),
                        pruningImages(
                                runner.containers,
                                planned.isWork() ? install(runner, plan, planned, progress, record) : Run.Payload.NONE,
                                record),
                        true,
                        Runner.Doubt.FAILS_THE_RUN)
                .renewing(planned.services(), images, foreign, plan.hasFailures()));
    }

    private static List<String> renewed(final List<String> stale, final List<String> scope, final List<String> holds) {
        return stale.stream()
                .filter(service -> scope.isEmpty() || scope.contains(service))
                // Recreating a held service's container is starting it.
                .filter(service -> !holds.contains(service))
                .toList();
    }

    private static boolean replacesLocal(final Runner runner, final UpdateRequest request) {
        return runner.directory
                .requestOf(request.id())
                .map(asked -> asked instanceof StewardRequest.Update update && update.replacesLocal())
                .orElse(false);
    }

    static Run.Payload pruningImages(final ContainerOps containers, final Run.Payload payload, final Steps record) {
        return (steps, stopped) -> {
            final Run.Done done = payload.carryOut(steps, stopped);
            return new Run.Done(
                    done.failed(),
                    () -> {
                        done.afterwards().run();
                        final Steps.Step cleanup = record.begin(RunStep.Kind.CLEANUP);
                        final ContainerOps.Pruned pruned = containers.pruneImages();
                        pruned.removed().forEach(cleanup::imageRemoved);
                        if (pruned.failure() == null) {
                            cleanup.cacheFreed(pruned.cacheBytes());
                            cleanup.done();
                        } else {
                            log.warn("Could not remove the unused images: {}", pruned.failure());
                            cleanup.warned(RunStep.Reason.REFUSED);
                        }
                    },
                    done.leftDown());
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
            final Work planned,
            final ImageResult images,
            final List<String> scope,
            final Steps record,
            final Steps.Step resolving) {
        final String own = Release.ownVersion();
        final String tag = plan.seasonTag();
        switch (Release.of(tag, own)) {
            case OWN -> {}
            case NEWER -> {
                if (runner.oneShot) {
                    resolving.failed(RunStep.Reason.RELEASED_MEANWHILE);
                } else {
                    Resolved.run(planned).on(resolving);
                }
                return runner.oneShot
                        ? failed()
                        : handOver(runner, request, Release.version(Objects.requireNonNull(tag)), record);
            }
            case OLDER -> {
                resolving.failed(RunStep.Reason.OLDER_RELEASE);
                return failed();
            }
        }
        if (!runner.oneShot
                && own != null
                && images.isOutdated(AgentWire.SERVICE)
                && (scope.isEmpty() || scope.contains(AgentWire.SERVICE))) {
            Resolved.run(planned).on(resolving);
            return handOver(runner, request, own, record);
        }
        return null;
    }

    private static Run.Plan onlyTheAgent() {
        return Run.Plan.of(List.of(), Run.Payload.NONE, false, Runner.Doubt.IS_ONLY_SAID)
                .stoppingNothing();
    }

    /**
     * Hands the claimed run to a one-shot steward-agent at {@code release}, writing its name on the row first.
     *
     * Nothing has stopped. The one-shot plans again and carries out the whole run.
     */
    private static Planned handOver(
            final Runner runner, final UpdateRequest request, final String release, final Steps record) {
        final Steps.Step handing = record.begin(RunStep.Kind.HAND_OVER).change(RELEASE, null, release, null);
        if (!runner.directory.handOver(request.id(), runner.containers.oneShot())) {
            // No longer RUNNING since the claim, which in practice means cancelled.
            handing.skipped(RunStep.Reason.CANCELLED);
            return Planned.outcome(Runner.cancelled());
        }
        final RedeployResult started = runner.containers.handOver(request.id(), release);
        UpdateRun.endBy(handing, started);
        if (!started.triggered()) {
            log.warn(
                    "Request {}: the one-shot at release {} did not start: {}",
                    request.id(),
                    release,
                    started.message());
            return failed();
        }
        log.info("Request {} is handed to {} at release {}", request.id(), runner.containers.oneShot(), release);
        return Planned.outcome(Outcome.handedOver());
    }

    /**
     * Migrates, then moves the plan's files into place, marking each stopped service by how its apply went.
     *
     * The migration runs while the servers are stopped.
     */
    private static Run.Payload install(
            final Runner runner,
            final UpdatePlan plan,
            final Work planned,
            final Consumer<RunStage> progress,
            final Steps record) {
        return (steps, state) -> {
            progress.accept(RunStage.INSTALLING);
            final Steps.Step migrating = record.begin(RunStep.Kind.MIGRATE);
            final String schema = Schema.version(runner.database);
            final RedeployResult migrated = runner.containers.migrate();
            migrating.change(SCHEMA, schema, Schema.version(runner.database), null);
            UpdateRun.endBy(migrating, migrated);
            if (!migrated.triggered()) {
                log.warn("The migration did not run: {}", migrated.message());
                throw new Run.Abort();
            }
            final ApplyResult result =
                    Runs.apply(runner.config, runner.topology(), plan, runner.settings(), runner.plugins);
            Installed.record(record, planned, result, state.services());
            return new Run.Done(result.hasFailures());
        };
    }

    static Planned restart(final Runner runner, final UpdateRequest request, final Steps record) {
        final List<String> holds = runner.held();
        final List<String> scope = runner.directory.scopeOf(request.id());
        final List<String> servers = runner.servers();
        final List<String> restarted = Runner.restarted(servers, scope, holds);
        if (restarted.isEmpty()) {
            if (scope.isEmpty()) {
                record.ended(RunStep.Kind.SCOPE, null, RunStep.Outcome.SKIPPED, RunStep.Reason.HELD);
            } else {
                skipped(record, scope, RunStep.Reason.NOT_IN_SCOPE);
            }
            return Planned.outcome(Outcome.at(RunStage.NOTHING_TO_DO));
        }
        skipped(
                record,
                servers.stream()
                        .filter(holds::contains)
                        .filter(service -> scope.isEmpty() || scope.contains(service))
                        .toList(),
                RunStep.Reason.HELD);
        return Planned.plan(Run.Plan.of(restarted, Run.Payload.NONE, false, Runner.Doubt.IS_ONLY_SAID));
    }

    /**
     * Dumps the database, saves the volumes, prunes, and copies the newest archives off this host.
     *
     * compose.yml says what a backup saves and stops: the agent's mounts and the label {@code eu.nordtal.backup}.
     */
    static Planned backup(final Runner runner, final Steps record) {
        final AgentWire.Topology topology;
        try {
            topology = runner.containers.topology();
        } catch (final RuntimeException unread) {
            log.warn("The backup could not read what it saves", unread);
            record.ended(RunStep.Kind.SCOPE, null, RunStep.Outcome.FAILED, RunStep.Reason.UNEXPECTED);
            return failed();
        }
        if (topology.backupVolumes().isEmpty()) {
            // A compose.yml without backup mounts fails the run.
            record.ended(RunStep.Kind.SCOPE, null, RunStep.Outcome.FAILED, RunStep.Reason.NO_VOLUMES);
            return failed();
        }

        final List<String> volumes = topology.backupVolumes();
        // Swept first, so a disk the budget can clear does not refuse the backup.
        prune(runner, volumes, record);
        final Planned noRoom = refusedWithoutRoom(runner, volumes, record);
        if (noRoom != null) {
            return noRoom;
        }

        // pg_dump takes an MVCC snapshot, so nothing stops for it.
        dump(runner, record);
        final Run.Payload save = (steps, stopped) -> {
            steps.save(volumes);
            // It frees disk before the next run.
            prune(runner, volumes, record);
            return new Run.Done(false, () -> offsite(runner, record));
        };
        // A server that is down already holds a whole world, and one held down stays down.
        return Planned.plan(
                Run.Plan.of(running(runner, topology.stoppedForBackup()), save, true, Runner.Doubt.FAILS_THE_RUN));
    }

    private static void offsite(final Runner runner, final Steps record) {
        final Steps.Step copying = record.begin(RunStep.Kind.OFFSITE_COPY);
        final Optional<SnapshotResult> copied = runner.backups.copyOffsite(policyOf(runner));
        if (copied.isEmpty()) {
            copying.skipped(RunStep.Reason.NO_REPOSITORY);
            return;
        }
        final SnapshotResult result = copied.get();
        if (result.ok()) {
            copying.offsite(String.valueOf(result.file()), result.bytes());
        }
        copying.ended(result);
    }

    private static Retention policyOf(final Runner runner) {
        final BackupSpec.RetentionSpec keep = runner.config.backup().retention();
        return new Retention(keep.daily(), keep.weekly(), keep.monthly(), keep.collapseAfterDays());
    }

    private static void prune(final Runner runner, final List<String> inBackup, final Steps record) {
        final Steps.Step pruning = record.begin(RunStep.Kind.PRUNE);
        final Snapshots.Pruned pruned = runner.backups.prune(policyOf(runner), inBackup);
        pruned.expired().forEach(file -> pruning.pruned(file, RunStep.PruneRule.RETENTION));
        pruned.overBudget().forEach(file -> pruning.pruned(file, RunStep.PruneRule.OVER_BUDGET));
        pruning.done();
    }

    /**
     * Returns a failed run when the disk would not keep its free share after the backup, else {@code null}.
     */
    static @Nullable Planned refusedWithoutRoom(final Runner runner, final List<String> volumes, final Steps record) {
        final Steps.Step checking = record.begin(RunStep.Kind.ROOM_CHECK);
        final Snapshots.Room room = runner.backups.room(volumes);
        checking.room(room.expectedBytes() + room.reserveBytes(), Math.max(0, room.usableBytes()));
        if (room.fits()) {
            checking.done();
            return null;
        }
        checking.failed(RunStep.Reason.NO_ROOM);
        return failed();
    }

    /**
     * Stops the named services and records them in {@code service_hold}, so no run starts them.
     *
     * An empty scope is refused.
     */
    static Planned down(final Runner runner, final UpdateRequest request, final Steps record) {
        final List<String> scope = runner.directory.scopeOf(request.id());
        if (scope.isEmpty()) {
            record.ended(RunStep.Kind.SCOPE, null, RunStep.Outcome.FAILED, RunStep.Reason.UNNAMED);
            return failed();
        }
        final List<String> refused = scope.stream().filter(NEVER_DOWN::contains).toList();
        if (!refused.isEmpty()) {
            refused.forEach(service ->
                    record.ended(RunStep.Kind.SCOPE, service, RunStep.Outcome.FAILED, RunStep.Reason.NEVER_DOWN));
            return failed();
        }
        final Run.Payload hold = (steps, stopped) -> {
            for (final String service : stopped.services()) {
                final Steps.Step holding = record.begin(RunStep.Kind.HOLD, service);
                runner.directory.hold(service, request.actor(), request.id());
                holding.done();
            }
            return new Run.Done(false);
        };
        return Planned.plan(Run.Plan.of(scope, hold, false, Runner.Doubt.IS_ONLY_SAID)
                // Only when somebody could be standing on one of them, since the countdown warns players.
                .announced(scope.stream().anyMatch(runner::isMinecraft))
                .leavingThemDown());
    }

    /**
     * Deletes the hold of the services and starts them, without a countdown.
     *
     * An empty scope means every held service.
     */
    static Planned start(final Runner runner, final UpdateRequest request, final Steps record) {
        final List<String> asked = runner.directory.scopeOf(request.id());
        final List<String> services = asked.isEmpty() ? runner.held() : asked;
        if (services.isEmpty()) {
            record.ended(RunStep.Kind.SCOPE, null, RunStep.Outcome.SKIPPED, RunStep.Reason.NOTHING_HELD);
            return Planned.outcome(Outcome.at(RunStage.NOTHING_TO_DO));
        }
        final Run.Payload release = (steps, stopped) -> {
            // The hold goes before the start, so a start that never returns leaves no row.
            for (final String service : services) {
                final Steps.Step letting = record.begin(RunStep.Kind.UNHOLD, service);
                runner.directory.release(service);
                letting.done();
            }
            return new Run.Done(false);
        };
        return Planned.plan(Run.Plan.of(List.of(), release, false, Runner.Doubt.IS_ONLY_SAID)
                .stoppingNothing()
                .alsoStarting(services));
    }

    /**
     * Creates the containers of the named services again, from the local image or a pulled one.
     *
     * Ours are stopped and made again as they start; caddy, pack-host and postgres once the rest is back.
     */
    static Planned remake(final Runner runner, final UpdateRequest request, final boolean pull, final Steps record) {
        final List<String> scope = runner.directory.scopeOf(request.id());
        if (scope.isEmpty()) {
            record.ended(RunStep.Kind.SCOPE, null, RunStep.Outcome.FAILED, RunStep.Reason.UNNAMED);
            return failed();
        }
        if (scope.contains(AgentWire.SERVICE)) {
            record.ended(RunStep.Kind.SCOPE, AgentWire.SERVICE, RunStep.Outcome.FAILED, RunStep.Reason.SELF);
            return failed();
        }
        final AgentWire.Topology topology = runner.topology();
        final List<String> recreatable = topology.renewed(AgentWire.Renewal.RUN);
        final List<String> foreign = ForeignImages.foreign(topology);
        final List<String> unknown = scope.stream()
                .filter(service -> !recreatable.contains(service))
                .filter(service -> !foreign.contains(service))
                .toList();
        if (!unknown.isEmpty()) {
            unknown.forEach(service ->
                    record.ended(RunStep.Kind.SCOPE, service, RunStep.Outcome.FAILED, RunStep.Reason.UNKNOWN));
            return failed();
        }
        final List<String> holds = runner.held();
        final List<String> ours = scope.stream()
                .filter(recreatable::contains)
                .filter(service -> !holds.contains(service))
                .toList();
        final List<String> theirs = foreign.stream()
                .filter(scope::contains)
                .filter(service -> !holds.contains(service))
                .toList();
        skipped(record, scope.stream().filter(holds::contains).toList(), RunStep.Reason.HELD);
        if (ours.isEmpty() && theirs.isEmpty()) {
            return Planned.outcome(Outcome.at(RunStage.NOTHING_TO_DO));
        }
        return Planned.plan(Run.Plan.of(ours, Run.Payload.NONE, true, Runner.Doubt.IS_ONLY_SAID)
                // Postgres and Caddy carry every server's connections, so they are announced like a stop.
                .announced(ours.stream().anyMatch(runner::isMinecraft) || !theirs.isEmpty())
                .remaking(ours, theirs, pull));
    }

    static Planned removePlugin(final Runner runner, final UpdateRequest request, final Steps record) {
        final StewardRequest asked = runner.directory.requestOf(request.id()).orElse(null);
        final List<String> scope = runner.directory.scopeOf(request.id());
        if (!(asked instanceof StewardRequest.RemovePlugin removal) || scope.isEmpty()) {
            record.ended(RunStep.Kind.SCOPE, null, RunStep.Outcome.FAILED, RunStep.Reason.UNNAMED);
            return failed();
        }
        final String service = scope.getFirst();
        final String artifact = removal.artifact();
        if (!runner.removal.has(service, artifact)) {
            record.ended(RunStep.Kind.SCOPE, service, RunStep.Outcome.FAILED, RunStep.Reason.UNKNOWN);
            return failed();
        }
        // A held server is already down, so the run neither stops nor starts it.
        final boolean held = runner.held().contains(service);
        final Run.Payload remove = (steps, stopped) -> {
            final Steps.Step removing =
                    record.begin(RunStep.Kind.INSTALL, service).change(artifact, null, null, null);
            try {
                if (runner.removal.remove(service, artifact).isEmpty()) {
                    removing.skipped(RunStep.Reason.NOTHING_TO_REMOVE);
                } else {
                    removing.done();
                }
            } catch (final RuntimeException refused) {
                log.warn("Could not remove {} from {}", artifact, service, refused);
                removing.failed(RunStep.Reason.REFUSED);
                return new Run.Done(true);
            }
            return new Run.Done(false);
        };
        final Run.Plan plan = Run.Plan.of(held ? List.of() : List.of(service), remove, true, Runner.Doubt.IS_ONLY_SAID);
        return Planned.plan(held ? plan.stoppingNothing().leavingThemDown() : plan);
    }

    static SnapshotResult dump(final Runner runner, final Steps record) {
        final Steps.Step dumping = record.begin(RunStep.Kind.DUMP_DATABASE);
        final SnapshotResult dumped = runner.backups.saveDatabase();
        // pg_dump reads a snapshot, so no stop stands behind a dump.
        dumping.archive(dumped, true);
        dumping.ended(dumped);
        return dumped;
    }

    private static void skipped(final Steps record, final List<String> services, final RunStep.Reason why) {
        services.forEach(service -> record.ended(RunStep.Kind.SCOPE, service, RunStep.Outcome.SKIPPED, why));
    }

    static List<String> running(final Runner runner, final List<String> services) {
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

    /** Ends a run before anything stopped. Its steps say why. */
    static Planned failed() {
        return Planned.outcome(Outcome.failed());
    }
}
