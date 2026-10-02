package eu.nordtal.s2.stewardagent.run;

import eu.nordtal.jcore.persistence.sql.Database;
import eu.nordtal.s2.common.time.Waiting;
import eu.nordtal.s2.database.setting.SettingStore;
import eu.nordtal.s2.database.update.ServiceHold;
import eu.nordtal.s2.database.update.UpdateDirectory;
import eu.nordtal.s2.database.update.UpdateReport;
import eu.nordtal.s2.database.update.UpdateReports;
import eu.nordtal.s2.database.update.UpdateRequest;
import eu.nordtal.s2.database.update.UpdateStatus;
import eu.nordtal.s2.internalapi.agent.RuntimeResult;
import eu.nordtal.s2.internalapi.agent.Topology;
import eu.nordtal.s2.stewardagent.config.RunSpec;
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

    final RunSpec config;
    final Database database;
    final ContainerOps containers;
    final Snapshots backups;
    final UpdateDirectory directory;
    final Waiting waiting;

    /** The plugins an admin added, handed to every resolve; {@code PluginDirectory#NONE} by default. */
    final eu.nordtal.s2.stewardagent.plugin.PluginDirectory plugins;

    /** How a plugin removal deletes what it removes; {@link PluginRemoval#NONE} by default. */
    final PluginRemoval removal;

    /** Whether this is the one-shot a run was handed to, which renews the long-running agent last. */
    final boolean oneShot;

    /** Where the proxy's pack is set, built on first use like the occupancy below. */
    private volatile @Nullable SettingStore settings;

    SettingStore settings() {
        if (settings == null) {
            settings = SettingStore.using(database.dataSource());
        }
        return settings;
    }

    /** How many players are on a service, built on first use since a run that stops nothing never asks. */
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
                eu.nordtal.s2.stewardagent.plugin.PluginDirectory.NONE,
                PluginRemoval.NONE);
    }

    /** The same, with a removal a test can watch. */
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
                eu.nordtal.s2.stewardagent.plugin.PluginDirectory.NONE,
                removal);
    }

    public Runner(
            final RunSpec config,
            final Database database,
            final ContainerOps containers,
            final Snapshots backups,
            final UpdateDirectory directory,
            final Waiting waiting,
            final eu.nordtal.s2.stewardagent.plugin.PluginDirectory plugins,
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
            final eu.nordtal.s2.stewardagent.plugin.PluginDirectory plugins,
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

    /** The same runner as the one-shot a run is handed to. */
    public Runner asOneShot() {
        return new Runner(config, database, containers, backups, directory, waiting, plugins, removal, true);
    }

    @Override
    public Outcome run(final UpdateRequest request, final Consumer<UpdateReport> progress) {
        try {
            final UpdateRun steps = new UpdateRun(containers, backups, progress);
            // Read before anything is planned, so a run that cannot stop a server never moves a jar.
            final RuntimeResult runtime = steps.check();
            if (!runtime.reached()) {
                return Outcome.failed(UpdateReports.toJson(
                        UpdateReport.at(UpdateReport.Stage.FAILED).withNote(unreachableMessage(runtime))));
            }
            // The row is the lock: the inbox holds one open run at a time, whoever wrote it.
            final Kinds.Planned planned = plan(request, progress);
            return planned.outcome() != null
                    ? planned.outcome()
                    : new Run(this, steps, runtime, progress).carryOut(request, Objects.requireNonNull(planned.plan()));
        } catch (final RuntimeException failure) {
            log.error("Request {} ({}) failed", request.id(), request.kind(), failure);
            return Outcome.failed("This request failed: " + failure + "\nsteward-agent's log has the stack trace.");
        }
    }

    /** What the request's kind plans; the sequence after it is the same for every kind. */
    private Kinds.Planned plan(final UpdateRequest request, final Consumer<UpdateReport> progress) {
        return switch (request.kind()) {
            case UPDATE -> Kinds.update(this, request, progress);
            case RESTART -> Kinds.restart(this, request);
            case BACKUP -> Kinds.backup(this, progress);
            case DOWN -> Kinds.down(this, request);
            case START -> Kinds.start(this, request);
            case RECREATE -> Kinds.remake(this, request, false);
            case DEPLOY -> Kinds.remake(this, request, true);
            case REMOVE_PLUGIN -> Kinds.removePlugin(this, request);
            case RESTORE -> Kinds.restore(this, request);
        };
    }

    /**
     * Counts down on this run's own row to the database's instant, so the proxy's countdown agrees with it.
     *
     * @return {@code true} when the countdown ran out and the run may proceed; {@code false} when cancelled
     */
    boolean countDown(
            final long id,
            final UpdateReport planned,
            final List<String> moving,
            final Consumer<UpdateReport> progress) {
        final Optional<UpdateRequest> counting = directory.startCountdown(id, UpdateDirectory.UPDATE_COUNTDOWN, moving);
        if (counting.isEmpty()) {
            // No longer RUNNING between the claim and here, which in practice means cancelled.
            log.info("Request {} is no longer running, so no countdown was started", id);
            return false;
        }
        progress.accept(planned.withStage(UpdateReport.Stage.COUNTDOWN));

        final Instant due = counting.get().due();
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
     * Only {@code PLANNED} lines count, so the database dump is never blamed.
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
                .withNote("Stopped during the countdown. Nothing was stopped and nothing was" + " installed.")));
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

    /** Whether that service is one of the four somebody can be standing on. */
    static boolean isMinecraft(final String service) {
        return Topology.SERVICES.stream().anyMatch(one -> one.name().equals(service));
    }

    /** The services a report says this run is going to stop, without the database dump line. */
    static List<String> movingServices(final UpdateReport report) {
        return report.services().stream()
                .filter(UpdateReport.ServiceLine::isMoving)
                .map(UpdateReport.ServiceLine::service)
                .filter(service -> !Snapshots.DATABASE.equals(service))
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
