package eu.nordtal.s2.steward.worker.serve;

import eu.nordtal.jcore.persistence.sql.Database;
import eu.nordtal.s2.common.update.ServiceHold;
import eu.nordtal.s2.common.update.UpdateDirectory;
import eu.nordtal.s2.common.update.UpdateReport;
import eu.nordtal.s2.common.update.UpdateReports;
import eu.nordtal.s2.common.update.UpdateRequest;
import eu.nordtal.s2.common.update.UpdateStatus;
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
 * Carries out one claimed {@link UpdateRequest} and says what happened.
 *
 * It never throws: Every path here ends in an {@link Outcome}, including the ones that go wrong. The caller has a
 * row marked {@code RUNNING} that somebody in Discord or in game is watching, and an exception escaping this class
 * would leave that row open forever. So the exception is caught, turned into the text of the answer, and logged with
 * its stack trace where a stack trace belongs.
 *
 * The kinds are different amounts of damage: {@code REPORT} writes nothing at all. {@code UPDATE} takes the advisory
 * lock, stops the servers whose jars change, migrates, swaps and starts them again. {@code RESTART} is the same
 * sequence with nothing installed, and {@code BACKUP} is that sequence with a volume snapshot in the gap.
 * {@code APPLY} is retired and refused - see {@code UpdateKind}.
 *
 * Every answer is a report, and the report is JSON: an answer is an {@code UpdateReport} rather than a paragraph, so
 * that Discord can draw a field per service and a console can print {@code render()} of the same object.
 * Steward-worker decides everything; it is not the only thing that draws.
 *
 * The countdown lives here rather than with whoever asked: a surface that set its own
 * {@code not_before = now() + 30s} and left this class forbidden to act before it would send the warning out before
 * anybody knew whether there was anything to warn about - counting thirty seconds down to every player on the
 * network and then answering "everything is already current". A warning that is usually wrong is one people learn
 * to ignore, which is the warning that will be standing there on the day it is true.
 *
 * So the order is: claim, read the container runtime, resolve, plan - and only then, if the plan has work in it,
 * {@code startCountdown} on this run's own row and wait it out.
 */
@Slf4j
public final class Runner implements RequestRunner {

    /**
     * How often the row is re-read while the countdown runs.
     *
     * One indexed lookup by primary key, thirty times per run. It is what makes a cancel end the wait rather than being
     * noticed when it is already over - {@link UpdateDirectory#commitCountdown} would catch it either way, but a run
     * that sits silently for the rest of the countdown after somebody pressed "Stop" looks exactly like one that
     * ignored them.
     */
    private static final Duration COUNTDOWN_TICK = Duration.ofSeconds(1);

    final StewardSpec config;
    final Database database;
    final ContainerOps containers;
    final Backups backups;
    final UpdateDirectory directory;
    final UpdateRun.Waiting waiting;

    /**
     * The plugins an admin added from the interface, handed to every resolve this class performs.
     *
     * Defaulted to {@code PluginDirectory#NONE} by the constructors that do not name one, which is what every existing
     * test takes: a run then resolves the fixed topology, exactly as it did before the table existed.
     */
    final eu.nordtal.s2.common.plugin.PluginDirectory plugins;

    /**
     * How many players are on a service, for the wait before a stop.
     *
     * Built on first use rather than in the constructor: a {@code REPORT} run never asks, and a constructor that opened
     * a pool would make every construction of this class need a database that answers. One run at a time holds the
     * advisory lock, so there is nothing to race.
     */
    private volatile @Nullable Occupancy occupancy;

    Occupancy occupancy() {
        if (occupancy == null) {
            occupancy = Occupancy.over(database.dataSource());
        }
        return occupancy;
    }

    /** {@code message()} is set whenever the runtime could not be reached, which is the only case this asks for it. */
    static String unreachableMessage(final RuntimeResult runtime) {
        return Objects.requireNonNull(runtime.message(), "unreachable result carries no message");
    }

    public Runner(
            final StewardSpec config,
            final Database database,
            final ContainerOps containers,
            final Backups backups,
            final UpdateDirectory directory) {
        this(config, database, containers, backups, directory, UpdateRun.Waiting.real());
    }

    public Runner(
            final StewardSpec config,
            final Database database,
            final ContainerOps containers,
            final Backups backups,
            final UpdateDirectory directory,
            final eu.nordtal.s2.common.plugin.PluginDirectory plugins) {
        this(config, database, containers, backups, directory, UpdateRun.Waiting.real(), plugins);
    }

    /** Package-visible so a test can drive a thirty-second countdown without waiting for one. */
    Runner(
            final StewardSpec config,
            final Database database,
            final ContainerOps containers,
            final Backups backups,
            final UpdateDirectory directory,
            final UpdateRun.Waiting waiting) {
        this(
                config,
                database,
                containers,
                backups,
                directory,
                waiting,
                eu.nordtal.s2.common.plugin.PluginDirectory.NONE);
    }

    Runner(
            final StewardSpec config,
            final Database database,
            final ContainerOps containers,
            final Backups backups,
            final UpdateDirectory directory,
            final UpdateRun.Waiting waiting,
            final eu.nordtal.s2.common.plugin.PluginDirectory plugins) {
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
     * The order is the design and every step of it is load-bearing. The container runtime is read first, before a
     * version is resolved or a byte is downloaded, because a run that cannot stop a server must not move a jar -
     * continuing anyway would replace a running JVM's jar underneath it. The migration runs with every
     * affected server stopped, which is stronger than the old rule (it ran before the jars moved, but with the servers
     * up), so a plugin can no longer see a schema half a version away from itself.
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
            // Before anything else moves: a newer worker migrates and installs this release, not this process.
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
                // A third answer: nothing to check, no work, no failure, and nothing was counted down to reach it.
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
     * Counts down on this run's own row, and says whether the run may go ahead.
     *
     * Why the instant comes back out of the database: {@code startCountdown} writes {@code now() + 30s} on the
     * database's clock and hands the row back. Waiting against that instant rather than against this JVM's own
     * arithmetic is what keeps this process and the proxy - which counts the same column down to every player - from
     * disagreeing by however far the two containers' clocks have drifted.
     *
     * @return {@code true} when the countdown ran out and this run holds the right to proceed; {@code false} when
     *     somebody cancelled, in which case nothing may be stopped
     */
    boolean countDown(final long id, final UpdateReport planned, final Consumer<UpdateReport> progress) {
        final Optional<UpdateRequest> counting = directory.startCountdown(id, UpdateDirectory.UPDATE_COUNTDOWN);
        if (counting.isEmpty()) {
            // Not RUNNING any more between the claim and here. Cancelled, in practice.
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
            // Ends the wait here, not at the bottom, so a cancel is not silently ignored for the rest of the countdown.
            final boolean stillRunning = directory
                    .find(id)
                    .map(row -> row.status() == UpdateStatus.RUNNING)
                    .orElse(false);
            if (!stillRunning) {
                log.info("Request {} was cancelled during its countdown; nothing was stopped", id);
                return false;
            }
        }
        // The race at zero: a cancel arriving this millisecond either takes the row or is refused by SKIP LOCKED.
        if (!directory.commitCountdown(id)) {
            log.info("Request {} was cancelled as its countdown ran out; nothing was stopped", id);
            return false;
        }
        return true;
    }

    /**
     * What an unverified stop costs on the path being settled.
     *
     * Two answers rather than one, and the difference is whether this run left something behind that somebody later has
     * to decide whether to trust.
     */
    enum Doubt {

        /**
         * The run wrote something while those servers were down.
         *
         * An archive, or jars in a {@code plugins/} directory. The run settles {@code FAILED}, so that nothing
         * downstream counts what it left behind as trustworthy: an archive taken over a server that had not
         * finished writing is not a backup, and jars moved into a directory a JVM may not have let go of is worse
         * still.
         */
        FAILS_THE_RUN,

        /**
         * The run wrote nothing in between, so there is no artefact to distrust.
         *
         * But the server may have been killed mid-save and started again on that same world, and a restart is what
         * somebody does when a server is already misbehaving, which is when this is most likely.
         *
         * So it is said, at run level, and nothing is blocked over it.
         */
        IS_ONLY_SAID
    }

    /**
     * Settles a run that stopped servers, including the one rule that is not about a failed line.
     *
     * A stop whose ending nobody could read is never silent: Every line can be green - the service stopped, the volume
     * saved, the jars moved, the servers came back - and the one thing missing is the evidence that the server had
     * finished writing when the next step touched its files. {@link Doubt} says what that costs on this path.
     *
     * The note is why this is a method rather than four lines in each caller. A rule that can fail a run has to be
     * readable in one place and drivable by a test without a database behind it; {@link #servicesThatRefused} is
     * package-private for exactly that reason and this sits beside it. A {@code FAILED} without the note attached would
     * be the worst of the outcomes: a failure with no reason on it.
     *
     * @param verified the report after {@code verify}, with its stage not yet settled
     * @param unverified {@link UpdateRun#unverifiedStops()}
     * @param whatIsAtRisk what the run did while those servers were down, as the middle of a sentence - "the archives
     *     were taken", "the jars were moved"
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
     * Only the {@code PLANNED} lines count, and that is the whole of it. The report also carries the database dump -
     * {@link DatabaseDump#NAME}, written before anything is stopped - and that line is not a container. Comparing every
     * line against {@code stopped} therefore found "database" missing on every single run, and every backup aborted
     * before saving a volume with "NOTHING WAS SAVED. database could not be stopped". The nightly backup did not fail
     * loudly; it failed politely, every night.
     *
     * Steward-worker is never stopped and must never be counted as refusing to: it is the process running this.
     * {@code UpdateRun#stop} leaves it out of {@code stopped}, so an operator who put "steward-worker" into
     * {@code backup.stop-services} would otherwise get a run that saves nothing and blames a service for not doing
     * something nobody asked it to do.
     */
    static List<String> servicesThatRefused(final UpdateReport planned, final Collection<String> stopped) {
        return planned.services().stream()
                .filter(line -> line.state() == UpdateReport.State.PLANNED)
                .map(UpdateReport.ServiceLine::service)
                .filter(service -> !Topology.STEWARD_WORKER.equals(service))
                .filter(service -> !stopped.contains(service))
                .toList();
    }

    /**
     * The answer to a run somebody stopped.
     *
     * Written and then thrown away, in the ordinary case: the row is already {@code CANCELLED}, so
     * {@code finish(...)} matches nothing and the cancellation's own reason - naming who stopped it - is what stays
     * in the row. It exists because this method has to return an {@link Outcome}, and because the one path that
     * would keep it is a row settled by something other than a cancel, where "stopped before anything moved" is
     * still the true sentence.
     */
    static Outcome cancelled() {
        return Outcome.done(UpdateReports.toJson(UpdateReport.at(UpdateReport.Stage.CANCELLED)
                .withNote("Stopped during the countdown. Nothing was stopped and nothing was" + " installed.")));
    }

    /**
     * Count down, stop the servers, snapshot the volumes, start the servers, wait for them.
     *
     * Why the stopping is here and belongs nowhere else: Anything that could stop these containers on a schedule of its
     * own would take the world away from whoever is standing in it with no countdown - and the countdown is the entire
     * reason this network has a request row rather than a cron job. Snapshotting without stopping is the other half of
     * the same trap: a world Paper is writing to tars cleanly and fails at restore, months later, on the day it is
     * needed. Both halves are why the schedule, the stopping and the tar are one sequence in one process, and this one.
     *
     * It always has work: Unlike an update there is nothing to resolve and no "everything is already current", so the
     * countdown is unconditional - the same shape as a restart, which is why both build their planned report the same
     * way.
     *
     * A failed snapshot never leaves the network down: Every path from the stop onwards ends in
     * {@link UpdateRun#start} and {@link UpdateRun#verify}. A backup that fails is bad news; a backup that fails and
     * leaves four servers stopped is an outage caused by a safety measure, which is worse than no backup at all.
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

        // The same lock an update takes: overlapping it with one moving jars would replace a running server's jar.
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

    /**
     * The same sequence with nothing installed: stop the servers, start them, wait for them.
     *
     * It used to be one redeploy of the whole project asked for over HTTP, whose successful outcome was usually that it
     * never returned - the redeploy took this container down mid-call and the next start read a {@code RESTART} left
     * {@code RUNNING} as "it happened". That is gone, and with it the reason nobody could ever be told whether the
     * network came back: cycling the four Minecraft services one at a time leaves steward-worker running, so it can
     * watch and say.
     */
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
     * Static and taking both lists rather than reading them, so the one thing that went wrong can be asserted without a
     * database, a docker socket or a network: a scope that was written, stored and then not read.
     *
     * @param scope what the request names, empty for the whole network - which is what an empty scope means in every
     *     other kind and is the button that exists today
     * @param holds what somebody is deliberately keeping down; never restarted, scope or no scope
     * @return the services to stop and start again, in {@link Topology} 's own order
     */
    static List<String> restarted(final List<String> scope, final List<String> holds) {
        return Topology.SERVICES.stream()
                .map(Topology.Service::name)
                .filter(service -> !holds.contains(service))
                .filter(service -> scope.isEmpty() || scope.contains(service))
                .toList();
    }

    /** @return the services somebody is deliberately holding down, in no particular order */
    List<String> held() {
        return directory.holds().stream().map(ServiceHold::service).toList();
    }

    /** Stop the named services and leave them stopped; see {@link HoldSequence#down}. */
    private Outcome down(final UpdateRequest request, final Consumer<UpdateReport> progress) {
        return HoldSequence.down(this, request, progress);
    }

    /** Take the hold off named (or every held) service and start it again; see {@link HoldSequence#startHeld}. */
    private Outcome startHeld(final UpdateRequest request, final Consumer<UpdateReport> progress) {
        return HoldSequence.startHeld(this, request, progress);
    }

    /** @return whether that service is one of the four somebody can be standing on */
    static boolean isMinecraft(final String service) {
        return Topology.SERVICES.stream().anyMatch(one -> one.name().equals(service));
    }

    /**
     * The services a report says this run is going to stop.
     *
     * The same two exemptions {@code UpdateRun#stop} makes, and for the same reasons: steward-worker is never stopped
     * because this sequence is running inside it, and {@code database} is a backup's dump line rather than a compose
     * service at all. Getting either of them into this list would ask for a standby of something that has none and then
     * abort the run over it.
     */
    static List<String> movingServices(final UpdateReport report) {
        return report.services().stream()
                .filter(UpdateReport.ServiceLine::isMoving)
                .map(UpdateReport.ServiceLine::service)
                .filter(service -> !Topology.STEWARD_WORKER.equals(service))
                .filter(service -> !DatabaseDump.NAME.equals(service))
                .toList();
    }

    /**
     * Puts what {@link Choreography#close()} said into the report, or hands it back untouched.
     *
     * A note per standby rather than a service line, and that is a decision rather than laziness: a line would be read
     * by {@code Evacuation} as a service this run is moving, and the proxy would then try to evacuate players off the
     * very standby they were just parked on. A standby is scenery, not a cast member.
     */
    static UpdateReport noteStandbys(final UpdateReport report, final List<String> said) {
        UpdateReport told = report;
        for (final String sentence : said) {
            told = told.withNote(sentence);
        }
        return told;
    }
}
