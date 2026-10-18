package eu.nordtal.season.stewardagent.run;

import static eu.nordtal.season.database.AdminTexts.TEXTS;

import eu.nordtal.season.common.time.Waiting;
import eu.nordtal.season.database.setting.SettingStore;
import eu.nordtal.season.database.update.RunStep;
import eu.nordtal.season.database.update.ServiceHold;
import eu.nordtal.season.database.update.UpdateDirectory;
import eu.nordtal.season.database.update.UpdateReport;
import eu.nordtal.season.database.update.UpdateReport.Note;
import eu.nordtal.season.database.update.UpdateReport.Step;
import eu.nordtal.season.database.update.UpdateReports;
import eu.nordtal.season.database.update.UpdateRequest;
import eu.nordtal.season.database.update.UpdateStatus;
import eu.nordtal.season.internalapi.agent.AgentWire;
import eu.nordtal.season.internalapi.agent.RuntimeResult;
import eu.nordtal.season.internalapi.agent.Topology;
import eu.nordtal.season.settings.Database;
import eu.nordtal.season.stewardagent.config.RunSpec;
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

    /** The interval at which the countdown re-reads the row, so a cancel ends the wait. */
    private static final Duration COUNTDOWN_TICK = Duration.ofSeconds(1);

    final RunSpec config;
    final Database database;
    final ContainerOps containers;
    final Snapshots backups;
    final UpdateDirectory directory;
    final Waiting waiting;

    /** The plugins an admin added, passed to every resolve. */
    final eu.nordtal.season.stewardagent.plugin.PluginDirectory plugins;

    final PluginRemoval removal;

    /**
     * Whether this is the one-shot a run was handed to, which recreates the long-running agent last.
     */
    final boolean oneShot;

    /** Built on first use. */
    private volatile @Nullable SettingStore settings;

    SettingStore settings() {
        if (settings == null) {
            settings = SettingStore.using(database.dataSource());
        }
        return settings;
    }

    /** Built on first use, because a run that stops nothing never asks. */
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
            final RunSpec config,
            final Database database,
            final ContainerOps containers,
            final Snapshots backups,
            final UpdateDirectory directory,
            final Waiting waiting) {
        this(
                config,
                database,
                containers,
                backups,
                directory,
                waiting,
                eu.nordtal.season.stewardagent.plugin.PluginDirectory.NONE,
                PluginRemoval.NONE);
    }

    Runner(
            final RunSpec config,
            final Database database,
            final ContainerOps containers,
            final Snapshots backups,
            final UpdateDirectory directory,
            final Waiting waiting,
            final PluginRemoval removal) {
        this(
                config,
                database,
                containers,
                backups,
                directory,
                waiting,
                eu.nordtal.season.stewardagent.plugin.PluginDirectory.NONE,
                removal);
    }

    public Runner(
            final RunSpec config,
            final Database database,
            final ContainerOps containers,
            final Snapshots backups,
            final UpdateDirectory directory,
            final Waiting waiting,
            final eu.nordtal.season.stewardagent.plugin.PluginDirectory plugins,
            final PluginRemoval removal) {
        this(config, database, containers, backups, directory, waiting, plugins, removal, false);
    }

    private Runner(
            final RunSpec config,
            final Database database,
            final ContainerOps containers,
            final Snapshots backups,
            final UpdateDirectory directory,
            final Waiting waiting,
            final eu.nordtal.season.stewardagent.plugin.PluginDirectory plugins,
            final PluginRemoval removal,
            final boolean oneShot) {
        this.oneShot = oneShot;
        this.plugins = plugins;
        this.removal = removal;
        this.config = config;
        this.database = database;
        this.containers = containers;
        this.backups = backups;
        this.directory = directory;
        this.waiting = waiting;
    }

    public Runner asOneShot() {
        return new Runner(config, database, containers, backups, directory, waiting, plugins, removal, true);
    }

    @Override
    public Outcome run(final UpdateRequest request, final Consumer<UpdateReport> progress) {
        final Steps record = new Steps(directory.recorder(), request.id());
        try {
            final UpdateRun steps = new UpdateRun(request.id(), containers, backups, progress, record);
            // Read before anything is planned, so a run that cannot stop a server never moves a jar.
            final RuntimeResult runtime = steps.check();
            if (!runtime.reached()) {
                record.ended(RunStep.Kind.RUNTIME, null, RunStep.Outcome.FAILED, RunStep.Reason.UNREACHABLE);
                return Outcome.failed(UpdateReports.toJson(UpdateReport.at(UpdateReport.Stage.FAILED)
                        .withNote(Note.failed(Step.RUN, TEXTS.report().words(unreachableMessage(runtime))))));
            }
            // The row is the lock: the inbox holds one open run at a time.
            final Kinds.Planned planned = plan(request, progress, record);
            return planned.outcome() != null
                    ? planned.outcome()
                    : new Run(this, steps, runtime, progress).carryOut(request, Objects.requireNonNull(planned.plan()));
        } catch (final RuntimeException failure) {
            log.error("Request {} ({}) failed", request.id(), request.kind(), failure);
            record.endOpen(RunStep.Outcome.FAILED, RunStep.Reason.UNEXPECTED);
            return Outcome.failed(UpdateReports.toJson(UpdateReport.at(UpdateReport.Stage.FAILED)
                    .withNote(Note.failed(Step.RUN, TEXTS.report().failedUnexpectedly(failure.toString())))));
        }
    }

    private Kinds.Planned plan(final UpdateRequest request, final Consumer<UpdateReport> progress, final Steps record) {
        return switch (request.kind()) {
            case UPDATE -> Kinds.update(this, request, progress, record);
            case RESTART -> Kinds.restart(this, request, record);
            case BACKUP -> Kinds.backup(this, progress, record);
            case DOWN -> Kinds.down(this, request, record);
            case START -> Kinds.start(this, request, record);
            case RECREATE -> Kinds.remake(this, request, false, record);
            case DEPLOY -> Kinds.remake(this, request, true, record);
            case REMOVE_PLUGIN -> Kinds.removePlugin(this, request, record);
            case RESTORE -> Restores.restore(this, request, record);
            case INSTALL -> throw new IllegalStateException("a start-up install is written settled and never claimed");
        };
    }

    /**
     * Counts down on this run's own row to the database's instant, so the proxy's countdown agrees with it.
     *
     * @return the step the players are moved in, begun as the countdown ran out; {@code null} when cancelled
     */
    Steps.@Nullable Step countDown(
            final long id,
            final UpdateReport planned,
            final List<String> moving,
            final Consumer<UpdateReport> progress,
            final Steps record) {
        final Steps.Step countdown = record.begin(RunStep.Kind.COUNTDOWN);
        final Optional<UpdateRequest> counting = directory.startCountdown(id, UpdateDirectory.UPDATE_COUNTDOWN, moving);
        if (counting.isEmpty()) {
            // A run that is no longer RUNNING after the claim was cancelled.
            log.info("Request {} is no longer running, so no countdown was started", id);
            countdown.skipped(RunStep.Reason.CANCELLED);
            return null;
        }
        progress.accept(planned.withStage(UpdateReport.Stage.COUNTDOWN));

        final Instant due = counting.get().due();
        while (waiting.now().isBefore(due)) {
            final Duration left = Duration.between(waiting.now(), due);
            if (!waiting.sleep(left.compareTo(COUNTDOWN_TICK) < 0 ? left : COUNTDOWN_TICK)) {
                log.warn("The countdown for request {} was interrupted; nothing was stopped", id);
                countdown.skipped(RunStep.Reason.INTERRUPTED);
                return null;
            }
            // A cancel ends the wait.
            final boolean stillRunning = directory
                    .find(id)
                    .map(row -> row.status() == UpdateStatus.RUNNING)
                    .orElse(false);
            if (!stillRunning) {
                log.info("Request {} was cancelled during its countdown; nothing was stopped", id);
                countdown.skipped(RunStep.Reason.CANCELLED);
                return null;
            }
        }
        countdown.done();
        // Before the commit, which is the proxy's signal: the proxy counts what it moves against this step.
        final Steps.Step players = record.begin(RunStep.Kind.PLAYERS_MOVED);
        // A cancel at zero either takes the row or is refused by SKIP LOCKED.
        if (!directory.commitCountdown(id)) {
            log.info("Request {} was cancelled as its countdown ran out; nothing was stopped", id);
            players.skipped(RunStep.Reason.CANCELLED);
            return null;
        }
        return players;
    }

    /** What an unverified stop costs when the run settles. */
    enum Doubt {

        /**
         * The run wrote an archive or jars while those servers were down, so it settles as FAILED.
         */
        FAILS_THE_RUN,

        /** The run wrote nothing meanwhile, so the run only notes the doubt. */
        IS_ONLY_SAID
    }

    /**
     * Settles a run that stopped servers, noting any stop whose ending nobody could read.
     *
     * @param verified the report after {@code verify}, with its stage not yet settled
     * @param unverified {@link UpdateRun#unverifiedStops()}
     * @param alreadyFailed whether something else has already failed this run
     * @param doubt what an unverified stop costs here
     */
    static UpdateReport settle(
            final UpdateReport verified,
            final List<String> unverified,
            final boolean alreadyFailed,
            final Doubt doubt) {
        final UpdateReport told = unverified.isEmpty()
                ? verified
                : verified.withNotes(new Note(
                                Step.STOP,
                                doubt == Doubt.FAILS_THE_RUN
                                        ? UpdateReport.Outcome.FAILED
                                        : UpdateReport.Outcome.WARNING,
                                null,
                                TEXTS.report().unverifiedStop())
                        .each(unverified));
        final boolean failed = (doubt == Doubt.FAILS_THE_RUN && !unverified.isEmpty())
                || alreadyFailed
                || told.services().stream().anyMatch(line -> line.state() == UpdateReport.State.FAILED);
        return told.withStage(failed ? UpdateReport.Stage.FAILED : UpdateReport.Stage.DONE);
    }

    /**
     * Returns the services this run asked to stop that did not stop.
     *
     * Only {@code PLANNED} lines count, so the dump is never blamed.
     */
    static List<String> servicesThatRefused(final UpdateReport planned, final Collection<String> stopped) {
        return planned.services().stream()
                .filter(line -> line.state() == UpdateReport.State.PLANNED)
                .map(UpdateReport.ServiceLine::service)
                .filter(service -> !stopped.contains(service))
                .toList();
    }

    /** The answer to a run somebody stopped, usually discarded since the row is already {@code CANCELLED}. */
    static Outcome cancelled() {
        return Outcome.done(UpdateReports.toJson(UpdateReport.at(UpdateReport.Stage.CANCELLED)
                .withNote(Note.skipped(Step.RUN, TEXTS.report().cancelled()))));
    }

    /**
     * Which Minecraft services a restart takes round.
     *
     * @param servers the Minecraft servers, in compose.yml's order
     * @param scope what the request names, empty for the whole network
     * @param holds the services held down, which a restart skips
     */
    static List<String> restarted(final List<String> servers, final List<String> scope, final List<String> holds) {
        return servers.stream()
                .filter(service -> !holds.contains(service))
                .filter(service -> scope.isEmpty() || scope.contains(service))
                .toList();
    }

    List<String> held() {
        return directory.holds().stream().map(ServiceHold::service).toList();
    }

    AgentWire.Topology topology() {
        return containers.topology();
    }

    /** The Minecraft servers' names, in compose.yml's order: the proxy first. */
    List<String> servers() {
        return topology().servers().stream().map(Topology.Service::name).toList();
    }

    boolean isMinecraft(final String service) {
        return topology().hasPlugins(service);
    }

    static List<String> movingServices(final UpdateReport report) {
        return report.services().stream()
                .filter(UpdateReport.ServiceLine::isMoving)
                .map(UpdateReport.ServiceLine::service)
                .filter(service -> !Snapshots.DATABASE.equals(service))
                .toList();
    }
}
