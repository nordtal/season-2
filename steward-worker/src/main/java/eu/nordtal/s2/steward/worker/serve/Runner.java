package eu.nordtal.s2.steward.worker.serve;

import eu.nordtal.jcore.persistence.sql.Database;
import eu.nordtal.s2.common.update.UpdateDirectory;
import eu.nordtal.s2.common.update.UpdateReport;
import eu.nordtal.s2.common.update.UpdateReports;
import eu.nordtal.s2.common.update.ServiceHold;
import eu.nordtal.s2.common.update.UpdateRequest;
import eu.nordtal.s2.common.update.UpdateStatus;
import eu.nordtal.s2.steward.worker.apply.ApplyResult;
import eu.nordtal.s2.steward.worker.backup.Backups;
import eu.nordtal.s2.steward.worker.backup.DatabaseDump;
import eu.nordtal.s2.steward.worker.backup.Retention;
import eu.nordtal.s2.steward.worker.backup.SnapshotResult;
import eu.nordtal.s2.steward.worker.ops.ContainerOps;
import eu.nordtal.s2.steward.worker.ops.ImageResult;
import eu.nordtal.s2.steward.worker.config.StewardSpec;
import eu.nordtal.s2.steward.worker.ops.RuntimeResult;
import eu.nordtal.s2.steward.worker.plan.PlanReport;
import eu.nordtal.s2.steward.worker.plan.Report;
import eu.nordtal.s2.steward.worker.plan.Topology;
import eu.nordtal.s2.steward.worker.plan.UpdatePlan;
import eu.nordtal.s2.steward.worker.run.Runs;
import eu.nordtal.s2.steward.worker.schema.RunLock;

import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Carries out one claimed {@link UpdateRequest} and says what happened.
 *
 * <h2>It never throws</h2>
 * Every path here ends in an {@link Outcome}, including the ones that go wrong. The caller has a
 * row marked {@code RUNNING} that somebody in Discord or in game is watching, and an exception
 * escaping this class would leave that row open forever. So the exception is caught, turned into
 * the text of the answer, and logged with its stack trace where a stack trace belongs.
 *
 * <h2>The kinds are different amounts of damage</h2>
 * {@code REPORT} writes nothing at all. {@code UPDATE} takes the advisory lock, stops the servers
 * whose jars change, migrates, swaps and starts them again. {@code RESTART} is the same sequence
 * with nothing installed, and {@code BACKUP} is that sequence with a volume snapshot in the gap.
 * {@code APPLY} is retired and refused - see {@code UpdateKind}.
 *
 * <h2>Every answer is a report, and the report is JSON</h2>
 * Since 2026-09-07 an answer is an {@code UpdateReport} rather than a paragraph, so that Discord
 * can draw a field per service and a console can print {@code render()} of the same object.
 * Steward-worker is still the only thing that decides anything; it is no longer the only thing
 * that draws.
 *
 * <h2>The countdown lives here now, and that is the point of 2026-09-08</h2>
 * It used to be written by whoever asked - every surface set {@code not_before = now() + 30s} and
 * this class was simply forbidden to act before it. So the warning went out <b>before</b> anybody
 * knew whether there was anything to warn about, and the ordinary run counted thirty seconds down
 * to every player on the network and then answered "everything is already current". A warning that
 * is usually wrong is one people learn to ignore, which is the warning that will be standing there
 * on the day it is true.
 *
 * <p>So the order is: claim, read the container runtime, resolve, plan - and only then, if the plan
 * has work in it, {@code startCountdown} on this run's own row and wait it out.</p>
 */
@Slf4j
public final class Runner implements RequestRunner {

    /**
     * How often the row is re-read while the countdown runs.
     *
     * <p>One indexed lookup by primary key, thirty times per run. It is what makes a cancel end the
     * wait rather than being noticed when it is already over - {@link UpdateDirectory#commitCountdown} would catch
     * it either way, but a run that sits silently for the rest of the countdown after somebody
     * pressed "Stop" looks exactly like one that ignored them.</p>
     */
    private static final Duration COUNTDOWN_TICK = Duration.ofSeconds(1);

    private final StewardSpec config;
    private final Database database;
    private final ContainerOps containers;
    private final Backups backups;
    private final UpdateDirectory directory;
    private final UpdateRun.Waiting waiting;

    /**
     * The plugins an admin added from the interface (season-2-ops/129), handed to every resolve
     * this class performs.
     *
     * <p>Defaulted to {@code PluginDirectory#NONE} by the constructors that do not name one, which
     * is what every existing test takes: a run then resolves the fixed topology, exactly as it did
     * before the table existed.</p>
     */
    private final eu.nordtal.s2.common.plugin.PluginDirectory plugins;

    /**
     * How many players are on a service, for the wait before a stop (season-2-ops/122).
     *
     * <p>Built on first use rather than in the constructor: a {@code REPORT} run never asks, and a
     * constructor that opened a pool would make every construction of this class need a database
     * that answers. One run at a time holds the advisory lock, so there is nothing to race.</p>
     */
    private volatile Occupancy occupancy;

    private Occupancy occupancy() {
        if (occupancy == null) {
            occupancy = Occupancy.over(database.dataSource());
        }
        return occupancy;
    }

    public Runner(final @NotNull StewardSpec config, final @NotNull Database database,
                  final @NotNull ContainerOps containers, final @NotNull Backups backups,
                  final @NotNull UpdateDirectory directory) {
        this(config, database, containers, backups, directory, UpdateRun.Waiting.real());
    }

    public Runner(final @NotNull StewardSpec config, final @NotNull Database database,
                  final @NotNull ContainerOps containers, final @NotNull Backups backups,
                  final @NotNull UpdateDirectory directory,
                  final @NotNull eu.nordtal.s2.common.plugin.PluginDirectory plugins) {
        this(config, database, containers, backups, directory, UpdateRun.Waiting.real(), plugins);
    }

    /** Package-visible so a test can drive a thirty-second countdown without waiting for one. */
    Runner(final @NotNull StewardSpec config, final @NotNull Database database,
           final @NotNull ContainerOps containers, final @NotNull Backups backups,
           final @NotNull UpdateDirectory directory, final @NotNull UpdateRun.Waiting waiting) {
        this(config, database, containers, backups, directory, waiting,
                eu.nordtal.s2.common.plugin.PluginDirectory.NONE);
    }

    Runner(final @NotNull StewardSpec config, final @NotNull Database database,
           final @NotNull ContainerOps containers, final @NotNull Backups backups,
           final @NotNull UpdateDirectory directory, final @NotNull UpdateRun.Waiting waiting,
           final @NotNull eu.nordtal.s2.common.plugin.PluginDirectory plugins) {
        this.plugins = plugins;
        this.config = config;
        this.database = database;
        this.containers = containers;
        this.backups = backups;
        this.directory = directory;
        this.waiting = waiting;
    }

    @Override
    public @NotNull Outcome run(final @NotNull UpdateRequest request,
                                final @NotNull Consumer<UpdateReport> progress) {
        try {
            return switch (request.kind()) {
                case REPORT -> report();
                // Retired 2026-09-07 and refused rather than run. The rows are still in the table
                // and still map (see UpdateKind.APPLY), but nothing may submit one, and the one
                // thing this must not do is quietly perform the sequence that caused finding 147.
                case APPLY -> Outcome.failed(UpdateReports.toJson(UpdateReport
                        .at(UpdateReport.Stage.FAILED)
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
            return Outcome.failed("This request failed: " + failure
                    + "\nSteward-worker's log has the stack trace.");
        }
    }

    // ---------------------------------------------------------------- report

    private Outcome report() {
        final UpdatePlan plan = Runs.resolve(config, plugins);
        // The images too, or the two surfaces disagree: a report saying "nothing to do" followed by
        // an update that stops four servers is the report being wrong, not the update.
        final UpdateReport report = withImages(PlanReport.of(plan), containers.images());
        // A plan full of rows that could not be checked is still a report, and the report says so
        // in the service lines. Marking the request FAILED would make "GitHub was briefly
        // unreachable" look like a broken steward-worker.
        return Outcome.done(UpdateReports.toJson(report.withStage(report.isWork()
                ? UpdateReport.Stage.PLANNED : UpdateReport.Stage.NOTHING_TO_DO)));
    }

    // ---------------------------------------------------------------- images

    /**
     * Puts what the registries say about the images into the plan, so that a stale image is work.
     *
     * <h2>Why it has to be in the plan and not in the starting step</h2>
     * {@code isWork()} is what decides whether anybody is counted down and whether a server is
     * stopped at all. A service whose jars are current and whose <em>image</em> is not would
     * otherwise fall out at {@code NOTHING_TO_DO} - every run reporting the network current while
     * {@code entrypoint.sh} and the JRE stayed on whatever was pulled at the last deploy, which is
     * exactly the state this was written to end. Adding the row here also means the change is in
     * the embed a person confirms, rather than appearing after they said yes to something else.
     *
     * <h2>What it will not claim</h2>
     * <ul>
     *   <li><b>Steward-worker's own image.</b> The recreate would take this process down mid-run.
     *       It is a note, and moving it needs a redeploy of the project from outside this process -
     *       the one thing in this deployment that still does.</li>
     *   <li><b>A service this worker does not own.</b> {@code postgres} and the backup sidecar are
     *       not in {@link Topology}, are never stopped by this sequence, and recreating one behind
     *       a report that does not mention it would be the worst kind of surprise. They are named
     *       in a note instead.</li>
     *   <li><b>Anything whose registry could not be asked.</b> {@link ImageResult} keeps "nobody
     *       has looked" apart from "up to date", and only the first of those is ever silent
     *       here.</li>
     * </ul>
     */
    private static UpdateReport withImages(final UpdateReport planned, final ImageResult images) {
        return withImages(planned, images, List.of());
    }

    /**
     * @param scope the services this run is for, empty for the whole network (season-2-ops/127). A
     *              service outside the scope is never given a line here: a line is what makes a
     *              server get stopped, and a run that says "smp" must not take the proxy down
     *              because its image moved.
     */
    private static UpdateReport withImages(final UpdateReport planned, final ImageResult images,
                                           final List<String> scope) {
        UpdateReport report = planned;

        // Named first and not returned on: a service whose image could not be compared is UNKNOWN
        // and therefore silent. A run that says nothing about it reads exactly like one that
        // checked it and found it current.
        final java.util.Optional<String> unverifiable = images.notCheckable();
        if (unverifiable.isPresent()) {
            report = report.withNote(unverifiable.get());
        }

        // steward/75: LOCAL is not work and never claims a line below, but it is the one thing this
        // report used to say nothing about at all - a service running unpublished code, silently
        // overwritten by the very next real update run.
        final java.util.Optional<String> local = images.localImages();
        if (local.isPresent()) {
            report = report.withNote(local.get());
        }

        final java.util.Optional<String> nothing = images.nothingChecked();
        if (nothing.isPresent()) {
            return report.withNote(nothing.get());
        }

        final List<String> foreign = new java.util.ArrayList<>();
        for (final java.util.Map.Entry<String, ImageResult.State> entry : images.services().entrySet()) {
            if (entry.getValue() != ImageResult.State.OUTDATED) {
                continue;
            }
            final String service = entry.getKey();
            if (Topology.STEWARD_WORKER.equals(service)) {
                report = report.withNote("Steward-worker's own image is out of date. Nothing here"
                        + " can renew it: the recreate would take this process down in the middle"
                        + " of its own run. Redeploy the project from the host when the network is"
                        + " quiet - that is the one thing in this deployment which still needs a"
                        + " hand.");
                continue;
            }
            if (FOREIGN_IMAGES.contains(service)) {
                // Renewed at the end of the run by #renewForeign, so it is neither a service line
                // here nor a note saying somebody should go and do it by hand. It gets no line at
                // this point on purpose: a line is what `stop` acts on, and stopping postgres in
                // the middle of the sequence that writes its own report into it is the one order
                // this must never take.
                continue;
            }
            if (!RECREATABLE.contains(service)) {
                foreign.add(service);
                continue;
            }
            if (!scope.isEmpty() && !scope.contains(service)) {
                continue;
            }
            report = report.with(report.line(service)
                    .with(new UpdateReport.Change("image", null, "newer image")));
        }

        if (!foreign.isEmpty()) {
            report = report.withNote("The registry has a newer image for "
                    + String.join(", ", foreign) + ", which steward-worker does not own and never"
                    + " stops. Renew " + (foreign.size() == 1 ? "it" : "them")
                    + " with a redeploy of the project from the host.");
        }
        return report;
    }

    /**
     * The services a run may pull an image for and recreate: everything it already stops, and
     * nothing else.
     *
     * <p>Steward-worker is absent for the reason {@link ContainerOps#recreate} gives, and so is
     * anything outside {@link Topology} - a sequence that recreates a container it never stopped
     * and never mentioned is one nobody can predict from the report they confirmed.</p>
     */
    /**
     * The three images in this project that nobody here builds, in the order they are renewed
     * (season-2-ops/127).
     *
     * <h2>They are in a run now, and they were not before</h2>
     * Until 2026-09-19 a stale {@code postgres}, {@code caddy} or {@code nginx} was a NOTE telling
     * somebody to go and redeploy the project by hand - which is a sentence nobody reads twice. The
     * owner's ask is the plain one: an update run updates everything, postgres and caddy included.
     * Renewing one is a {@code pull} and an {@code up -d --no-deps}, both of which
     * {@code steward-deployer} already does for every other service.
     *
     * <h2>Only within the major, and the tag is what guarantees it</h2>
     * {@code postgres:17-alpine} cannot become 18 while nobody edits the tag, and the same holds
     * for {@code caddy:2-alpine} and {@code nginx:alpine}. So the guarantee needs no code at all -
     * <b>and that is exactly why the run must not touch those tags.</b> The reason the boundary
     * matters is worth having next to the list rather than in a ticket: Postgres does not start on
     * a data directory written by the previous major. A major jump would not update the database,
     * it would stop it, and getting back out is a dump and a restore - a planned procedure, not
     * something a run discovers.
     *
     * <h2>The order, and why postgres is last</h2>
     * Ordered, not a set. {@code caddy} and {@code pack-host} cost a web page a second and no
     * player anything. {@code postgres} is this process's own lifeline: every progress write goes
     * through it, and the final report of the run is written after this class returns. So it is
     * renewed last, and {@link UpdateRun#verify} waits for it to be healthy again before anything
     * else happens - otherwise the report of a run that worked would be the thing that got lost.
     *
     * <p>{@code pack-host} is in the {@code devpack} profile and simply is not there on a
     * production selection; a service the daemon has no container for is never named by
     * {@link ImageResult} and therefore never renewed. Listing it costs nothing and keeps the list
     * the same on both kinds of host.</p>
     */
    private static final List<String> FOREIGN_IMAGES = List.of("caddy", "pack-host", "postgres");

    private static final java.util.Set<String> RECREATABLE = java.util.stream.Stream.concat(
                    Topology.SERVICES.stream().map(Topology.Service::name),
                    java.util.stream.Stream.of(Topology.DISCORD_BOT))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    /**
     * The foreign images that are actually behind, in {@link #FOREIGN_IMAGES}'s order.
     *
     * <p>Only {@code OUTDATED}. {@link ImageResult} keeps "nobody could look" apart from "up to
     * date", and a registry that did not answer is never a reason to recreate a container - the
     * same rule {@link #withImages} follows one method up.</p>
     */
    private static List<String> staleForeign(final ImageResult images) {
        return FOREIGN_IMAGES.stream().filter(images::isOutdated).toList();
    }

    /**
     * Pulls and recreates each of them, then waits for it to be healthy again.
     *
     * <h2>Last, after the Minecraft services are already back</h2>
     * Deliberately not folded into {@link UpdateRun#start}, which renews the image of a service it
     * has just stopped. None of these three is ever stopped by this sequence: recreating them is a
     * {@code compose up} that replaces the container by itself, and none of them has a player
     * standing on it. Putting them at the end is what keeps a player from waiting on Caddy.
     *
     * <h2>What a failure here does, and what it does not</h2>
     * The line is FAILED and the run is FAILED with it - {@link #settle} reads that, and an image
     * that could not be renewed is a run that did not do what it said. It does not roll anything
     * back: the old container is still running, which is the same outcome as never having asked.
     */
    private UpdateReport renewForeign(final UpdateRun run, final UpdateReport before,
                                      final List<String> services,
                                      final Consumer<UpdateReport> progress) {
        if (services.isEmpty()) {
            return before;
        }
        UpdateReport report = before;
        final List<String> asked = new java.util.ArrayList<>();
        for (final String service : services) {
            // Written before the call, not after, for the reason UpdateRun#start gives: a recreate
            // that never returns leaves this as the report's last word.
            report = report.with(new UpdateReport.ServiceLine(service,
                    UpdateReport.State.STARTING,
                    List.of(new UpdateReport.Change("image", null, "newer image")),
                    "pulling its image and recreating the container"));
            progress.accept(report);
            final eu.nordtal.s2.steward.worker.ops.RedeployResult result =
                    containers.deploy(service);
            if (result.triggered()) {
                asked.add(service);
                continue;
            }
            report = report.with(report.line(service).failed("its image is out of date and the"
                    + " container could not be recreated: " + result.message()
                    + ". It is still running the image it had."));
            progress.accept(report);
        }
        // The wait is not politeness. This process writes the run's final report through postgres
        // after returning from here, so returning while postgres is still coming up is how the
        // report of a successful run disappears.
        return asked.isEmpty() ? report : run.verify(report, asked, UpdateRun.Waiting.real());
    }

    // ---------------------------------------------------------------- the update

    /**
     * The whole sequence: check, resolve, count down (already spent), stop, migrate, swap, start,
     * verify.
     *
     * <p>The order is the design and every step of it is load-bearing. The container runtime is read
     * <b>first</b>, before a version is resolved or a byte is downloaded, because a run that cannot
     * stop a server must not move a jar - that is finding 147, and continuing anyway would be the defect
     * performed as a fallback. The migration runs with every affected server <b>stopped</b>, which
     * is stronger than the old rule (it ran before the jars moved, but with the servers up), so a
     * plugin can no longer see a schema half a version away from itself.</p>
     */
    private Outcome update(final UpdateRequest request, final Consumer<UpdateReport> progress) {
        final UpdateRun run = new UpdateRun(containers, backups.volumes(), progress);

        final RuntimeResult runtime = run.check();
        if (!runtime.reached()) {
            return Outcome.failed(UpdateReports.toJson(UpdateReport.at(UpdateReport.Stage.FAILED)
                    .withNote(runtime.message())));
        }

        progress.accept(UpdateReport.at(UpdateReport.Stage.RESOLVING));

        final Optional<RunLock> lock;
        try {
            lock = RunLock.tryAcquire(database.dataSource());
        } catch (final SQLException failure) {
            return Outcome.failed(UpdateReports.toJson(UpdateReport.at(UpdateReport.Stage.FAILED)
                    .withNote("Could not reach the database to take the steward-worker lock: "
                            + failure)));
        }
        if (lock.isEmpty()) {
            return Outcome.failed(UpdateReports.toJson(UpdateReport.at(UpdateReport.Stage.FAILED)
                    .withNote("Another steward-worker run is in progress - nothing was done. That"
                            + " is either another update or a `docker compose run --rm"
                            + " steward-worker bootstrap` somebody started on the host. Wait for it"
                            + " to finish and ask again.")));
        }

        // After the runtime check and before anything is resolved: an image update is a reason to
        // take a server down, so it belongs in the plan a person confirms rather than in a step
        // discovered halfway through a run that was counted down for something else.
        final ImageResult images = containers.images();

        try (RunLock held = lock.get()) {
            // season-2-ops/127: which services this run is for. Empty is the whole network, which
            // is what every run was until this existed and what a row written before the column
            // did says. Read once, here, and then used for everything below - the plan, the
            // report and the foreign images - so that a scoped run cannot narrow one of them and
            // not another.
            final List<String> scope = directory.scopeOf(request.id());
            // season-2-ops/125: a service somebody is holding down is taken out of the run before
            // anything is stopped. Installing into it would mean starting it again to verify, and
            // starting it again is precisely what the hold forbids.
            final List<String> holds = held();
            final UpdatePlan plan = Runs.resolve(config, plugins).onlyServices(scope).withoutServices(holds);
            UpdateReport planned = withImages(PlanReport.of(plan), images, scope).withoutLines(holds);
            final List<String> skipped = holds.stream()
                    .filter(service -> scope.isEmpty() || scope.contains(service))
                    .toList();
            if (!skipped.isEmpty()) {
                planned = planned.withNote(String.join(", ", skipped) + " is being held down and was"
                        + " left out of this run. Start it again and ask for the update once more.");
            }
            // Worked out here rather than inside the two branches below, because both of them need
            // it and the two answers must be the same one.
            final List<String> foreign = staleForeign(images).stream()
                    // A scoped run renews a foreign image only when the scope names it. "update
                    // smp" must not recreate postgres: the whole promise of a scope is that what
                    // it touches is what it says.
                    .filter(service -> scope.isEmpty() || scope.contains(service))
                    // And a held service is not renewed either, for the reason above: recreating
                    // its container is starting it.
                    .filter(service -> !holds.contains(service))
                    .toList();

            if (!planned.isWork() && !foreign.isEmpty()) {
                // A RUN WITH NO SERVER IN IT. Nothing this network plays on is stopped - caddy and
                // the pack host are not, and postgres is recreated rather than stopped - so there
                // is nobody to warn and nothing to count down. Counting down here would take a
                // minute of every player's evening to tell them that a web server was being
                // replaced.
                final UpdateReport renewed = renewForeign(run,
                        planned.withStage(UpdateReport.Stage.INSTALLING), foreign, progress);
                final UpdateReport settled = settle(renewed, run.unverifiedStops(),
                        "its image was renewed", plan.hasFailures(), Doubt.FAILS_THE_RUN);
                return settled.stage() == UpdateReport.Stage.FAILED
                        ? Outcome.failed(UpdateReports.toJson(settled))
                        : Outcome.done(UpdateReports.toJson(settled));
            }

            if (!planned.isWork()) {
                // A third answer, not a quiet kind of "fine": a run where nothing could be checked
                // did no work AND had no failure, and closing it with "nothing needed doing" is how
                // somebody reads it as "the network is current".
                //
                // And nothing was counted down to reach it, which is the whole of V13: this is the
                // ordinary outcome of /update now, and it used to cost every player on the network
                // thirty seconds of being told the servers were going down.
                return Outcome.done(UpdateReports.toJson(planned.withStage(plan.hasFailures()
                        ? UpdateReport.Stage.FAILED : UpdateReport.Stage.NOTHING_TO_DO)));
            }

            // season-2-ops/122: THE STANDBYS COME UP BEFORE ANYBODY IS WARNED. A run that stops
            // the proxy needs a second proxy to hold the players and a run that stops the waiting
            // room needs a second waiting room, and neither of them starts on its own - they sit in
            // a compose profile no ordinary selection carries. Every plugin-side half of this was
            // already built and none of it was ever driven, which is exactly Till's finding in
            // season-2-ops/123.
            //
            // Before the countdown, on purpose: a standby that will not come up then aborts a run
            // that has warned nobody and touched nothing, instead of one that has already told
            // every player on the network that the servers are going down.
            final Choreography choreography = new Choreography(containers, occupancy(), waiting);
            final Choreography.Window window = choreography.open(movingServices(planned));
            if (!window.opened()) {
                return Outcome.failed(UpdateReports.toJson(planned
                        .withStage(UpdateReport.Stage.FAILED)
                        .withNote("NOTHING WAS STOPPED AND NOTHING WAS INSTALLED. " + window.refusal()
                                + ". This run stops a service whose players have to go somewhere,"
                                + " and the somewhere is that standby - so a standby that does not"
                                + " come up is a run that would take the network down with nowhere"
                                + " to put anybody.")));
            }
            if (!window.isEmpty()) {
                planned = planned.withNote(String.join(", ", window.standbys())
                        + " started and healthy, so this run has somewhere to put the players.");
                progress.accept(planned);
            }
            try {
                if (!countDown(request.id(), planned, progress)) {
                    return cancelled();
                }

                // season-2-ops/122, Till 2026-09-20: after the countdown the run WAITS for the
                // players to be somewhere else rather than stopping on the tick. Nobody new can
                // arrive meanwhile - LimboHold already holds every login whose destination this run
                // is moving - and after ten seconds it stops regardless, because a run waiting on a
                // hung transfer would never end. What it never does is stop quietly: the sentence
                // this hands back is the only thing that says where to look next time.
                final String stillOn = choreography.waitUntilEmpty(movingServices(planned));
                if (stillOn != null) {
                    planned = planned.withNote(stillOn);
                    progress.accept(planned);
                }

                final UpdateRun.Stopped stopped = run.stop(planned, runtime);

                // A service that has work and did not stop is still RUNNING, and Runs.apply would move
                // its jars anyway - which is finding 147 exactly, reached through the one path that was
                // supposed to end it. Nothing is migrated and nothing is installed; whatever DID stop is
                // started again, because leaving half a network down over a refused stop turns a
                // cancelled update into an outage.
                final List<String> notStopped = planned.services().stream()
                        // isMoving, matching UpdateRun#stop exactly: a service listed only because an
                        // artefact has no build yet was never asked to stop, so it must not be counted
                        // as one that refused.
                        .filter(UpdateReport.ServiceLine::isMoving)
                        .map(UpdateReport.ServiceLine::service)
                        .filter(service -> !Topology.STEWARD_WORKER.equals(service))
                        // The same two exemptions, because "matching exactly" above is a claim and not
                        // a mechanism. An update's report carries no `database` line today - only a
                        // backup's does - so this filter changes nothing that runs. It is here so the
                        // sentence stays true if that ever stops being the case.
                        .filter(service -> !DatabaseDump.NAME.equals(service))
                        .filter(service -> !stopped.services().contains(service))
                        .toList();
                if (!notStopped.isEmpty()) {
                    final UpdateReport back = run.start(new UpdateRun.Stopped(
                            stopped.report().withNote("NOTHING WAS INSTALLED. " + String.join(", ",
                                    notStopped) + " could not be stopped, and installing into a server"
                                    + " that is still running is the failure this sequence exists to"
                                    + " prevent. Every service that did stop has been started again."),
                            stopped.services(), runtime));
                    return Outcome.failed(UpdateReports.toJson(run
                            .verify(back, stopped.services(), UpdateRun.Waiting.real())
                            .withStage(UpdateReport.Stage.FAILED)));
                }

                try {
                    eu.nordtal.s2.steward.worker.schema.Schema.migrate(database);
                } catch (final RuntimeException failure) {
                    log.error("The migration failed; no jar was touched", failure);
                    // The servers are down at this point, so they are started again before this is
                    // reported. Leaving a stopped network behind because a migration failed would turn
                    // a refused update into an outage.
                    final UpdateReport back = run.start(new UpdateRun.Stopped(
                            stopped.report().withNote("THE MIGRATION FAILED AND NOTHING WAS INSTALLED: "
                                    + failure), stopped.services(), runtime));
                    return Outcome.failed(UpdateReports.toJson(run
                            .verify(back, stopped.services(), UpdateRun.Waiting.real())
                            .withStage(UpdateReport.Stage.FAILED)));
                }

                UpdateReport report = stopped.report().withStage(UpdateReport.Stage.INSTALLING);
                progress.accept(report);
                final ApplyResult result = Runs.apply(config, plan);
                report = report.withNote(Report.render(result));
                for (final String service : stopped.services()) {
                    // Only where the apply actually succeeded. Marking every stopped service INSTALLED
                    // published a report claiming a failed download had installed - and it published it
                    // BEFORE start() and verify() could correct the line, so that claim is what an
                    // admin watching the embed read while the run was still going.
                    final String failure = failureFor(result, service);
                    report = report.with(failure == null
                            ? report.line(service).at(UpdateReport.State.INSTALLED)
                            : report.line(service).failed(failure));
                }
                progress.accept(report);

                final UpdateReport started = run.start(
                        new UpdateRun.Stopped(report, stopped.services(), runtime), images);
                final UpdateReport verified = run.verify(started, stopped.services(),
                        UpdateRun.Waiting.real());

                // season-2-ops/127: last, once the Minecraft services are healthy again. See
                // FOREIGN_IMAGES for why postgres is the last of the three and why it is waited for.
                final UpdateReport renewed = renewForeign(run, verified, foreign, progress);

                final UpdateReport told = noteStandbys(renewed, choreography.close());
                final UpdateReport finished = settle(told, run.unverifiedStops(),
                        "the jars were moved into its plugins directory", result.hasFailures(),
                        Doubt.FAILS_THE_RUN);
                return finished.stage() == UpdateReport.Stage.FAILED
                        ? Outcome.failed(UpdateReports.toJson(finished))
                        : Outcome.done(UpdateReports.toJson(finished));
            } finally {
                // Every exit, including the cancelled one and the two that abandon the run with the
                // servers already started again: a standby left standing is a second network
                // running all night. Idempotent, so the ordinary path having already closed it -
                // and written what it said into the report - costs nothing here.
                choreography.close();
            }
        }
    }

    // ---------------------------------------------------------------- the countdown

    /**
     * Counts down on this run's own row, and says whether the run may go ahead.
     *
     * <h2>Why the instant comes back out of the database</h2>
     * {@code startCountdown} writes {@code now() + 30s} on the <em>database's</em> clock and hands
     * the row back. Waiting against that instant rather than against this JVM's own arithmetic is
     * what keeps this process and the proxy - which counts the same column down to every player -
     * from disagreeing by however far the two containers' clocks have drifted.
     *
     * @return {@code true} when the countdown ran out and this run holds the right to proceed;
     *         {@code false} when somebody cancelled, in which case <b>nothing may be stopped</b>
     */
    private boolean countDown(final long id, final UpdateReport planned,
                              final Consumer<UpdateReport> progress) {
        final Optional<UpdateRequest> counting =
                directory.startCountdown(id, UpdateDirectory.UPDATE_COUNTDOWN);
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
            // A cancel ends the wait here rather than at the bottom. commitCountdown would refuse
            // it either way, but a run that sits silent for the rest of the countdown after
            // somebody pressed "Stop the countdown" reads as one that ignored them.
            final boolean stillRunning = directory.find(id)
                    .map(row -> row.status() == UpdateStatus.RUNNING)
                    .orElse(false);
            if (!stillRunning) {
                log.info("Request {} was cancelled during its countdown; nothing was stopped", id);
                return false;
            }
        }
        // The race at zero, decided by one statement rather than by the read above: a cancel
        // arriving in this millisecond either takes the row or is refused by SKIP LOCKED.
        if (!directory.commitCountdown(id)) {
            log.info("Request {} was cancelled as its countdown ran out; nothing was stopped", id);
            return false;
        }
        return true;
    }

    /**
     * The answer to a run somebody stopped.
     *
     * <p>Written and then thrown away, in the ordinary case: the row is already {@code CANCELLED},
     * so {@code finish(...)} matches nothing and the cancellation's own reason - naming who stopped
     * it - is what stays in the row. It exists because this method has to return an
     * {@link Outcome}, and because the one path that would keep it is a row settled by something
     * other than a cancel, where "stopped before anything moved" is still the true sentence.</p>
     */
    /**
     * What an unverified stop costs on the path being settled.
     *
     * <p>Two answers rather than one, and the difference is whether this run left something behind
     * that somebody later has to decide whether to trust.</p>
     */
    enum Doubt {

        /**
         * The run wrote something while those servers were down - an archive, or jars in a
         * {@code plugins/} directory. Owner's decision, 2026-09-13: the run settles {@code FAILED},
         * so that nothing downstream counts what it left behind as trustworthy. Run 23's shape is a
         * green report over an archive nobody should have trusted; 147's
         * is jars moved into a directory a JVM may not have let go of, and it is the worse of the
         * two.
         */
        FAILS_THE_RUN,

        /**
         * The run wrote nothing in between, so there is no artefact to distrust - but the server
         * may have been killed mid-save and started again on that same world, and a restart is
         * what somebody does when a server is <em>already</em> misbehaving, which is when this is
         * most likely. So it is said, at run level, and nothing is blocked over it. Owner's
         * decision, 2026-09-13.
         */
        IS_ONLY_SAID
    }

    /**
     * Settles a run that stopped servers, including the one rule that is not about a failed line.
     *
     * <h2>A stop whose ending nobody could read is never silent</h2>
     * Every line can be green - the service stopped, the volume saved, the jars moved, the servers
     * came back - and the one thing missing is the evidence that the server had finished writing
     * when the next step touched its files. {@link Doubt} says what that costs on this path.
     *
     * <p>The note is why this is a method rather than four lines in each caller. A rule that can
     * fail a run has to be readable in one place and drivable by a test without a database behind
     * it; {@link #servicesThatRefused} is package-private for exactly that reason and this sits
     * beside it. A {@code FAILED} without the note attached would be the worst of the outcomes: a
     * failure with no reason on it.</p>
     *
     * @param verified   the report after {@code verify}, with its stage not yet settled
     * @param unverified {@link UpdateRun#unverifiedStops()}
     * @param whatIsAtRisk what the run did while those servers were down, as the middle of a
     *                     sentence - "the archives were taken", "the jars were moved"
     * @param alreadyFailed whether something else has already failed this run
     * @param doubt      what an unverified stop costs here
     * @return the report with its stage set, and the note on it when there was one to make
     */
    static UpdateReport settle(final UpdateReport verified, final List<String> unverified,
                               final String whatIsAtRisk, final boolean alreadyFailed,
                               final Doubt doubt) {
        final UpdateReport told = unverified.isEmpty() ? verified
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
                || told.services().stream()
                        .anyMatch(line -> line.state() == UpdateReport.State.FAILED);
        return told.withStage(failed ? UpdateReport.Stage.FAILED : UpdateReport.Stage.DONE);
    }


    /**
     * Which of the services this run asked to stop are not among the ones that did.
     *
     * <p><b>Only the {@code PLANNED} lines count, and that is the whole of it.</b> The report also
     * carries the database dump - {@link DatabaseDump#NAME}, written before anything is stopped -
     * and that line is not a container. Comparing every line against {@code stopped} therefore
     * found "database" missing on every single run, and every backup aborted before saving a
     * volume with "NOTHING WAS SAVED. database could not be stopped". The nightly backup did not
     * fail loudly; it failed politely, every night.</p>
     *
     * <p>Steward-worker is never stopped and must never be counted as refusing to: it is the
     * process running this. {@code UpdateRun#stop} leaves it out of {@code stopped}, so an operator
     * who put "steward-worker" into {@code backup.stop-services} would otherwise get a run that
     * saves nothing and blames a service for not doing something nobody asked it to do.</p>
     */
    static List<String> servicesThatRefused(final UpdateReport planned,
                                            final Collection<String> stopped) {
        return planned.services().stream()
                .filter(line -> line.state() == UpdateReport.State.PLANNED)
                .map(UpdateReport.ServiceLine::service)
                .filter(service -> !Topology.STEWARD_WORKER.equals(service))
                .filter(service -> !stopped.contains(service))
                .toList();
    }

    private static Outcome cancelled() {
        return Outcome.done(UpdateReports.toJson(UpdateReport.at(UpdateReport.Stage.CANCELLED)
                .withNote("Stopped during the countdown. Nothing was stopped and nothing was"
                        + " installed.")));
    }

    /**
     * Why one service's install did not happen, or {@code null} when it did.
     *
     * <h2>{@code SKIPPED} counts as a failure here, and that is the point of it</h2>
     * {@link ApplyResult.Status#SKIPPED} means the whole of that server was deliberately left
     * alone because one of its artefacts could not be resolved - a server's plugins move together
     * or not at all. From the report's side that is not "installed": the server was stopped, the
     * jars are the old ones, and it is about to be started again on them. Saying "updated" there
     * would be the one line in the run that is simply untrue.
     */
    private static String failureFor(final ApplyResult result, final String service) {
        return result.outcomes().stream()
                .filter(outcome -> service.equals(outcome.service()))
                .filter(outcome -> outcome.status() == ApplyResult.Status.FAILED
                        || outcome.status() == ApplyResult.Status.SKIPPED)
                .findFirst()
                .map(outcome -> outcome.artifact() + ": " + (outcome.detail() == null
                        ? outcome.status().name().toLowerCase(java.util.Locale.ROOT)
                        : outcome.detail()))
                .orElse(null);
    }

    // ---------------------------------------------------------------- backup

    /**
     * Count down, stop the servers, snapshot the volumes, start the servers, wait for them.
     *
     * <h2>Why the stopping is here and belongs nowhere else</h2>
     * Anything that could stop these containers on a schedule of its own would take the world away
     * from whoever is standing in it with no countdown - and the countdown is the entire reason
     * this network has a request row rather than a cron job. Snapshotting without stopping is the
     * other half of the same trap: a world Paper is writing to tars cleanly and fails at
     * <em>restore</em>, months later, on the day it is needed. Both halves are why the schedule,
     * the stopping and the tar are one sequence in one process, and this one.
     *
     * <h2>It always has work</h2>
     * Unlike an update there is nothing to resolve and no "everything is already current", so the
     * countdown is unconditional - the same shape as a restart, which is why both build their
     * planned report the same way.
     *
     * <h2>A failed snapshot never leaves the network down</h2>
     * Every path from the stop onwards ends in {@link UpdateRun#start} and {@link UpdateRun#verify}.
     * A backup that fails is bad news; a backup that fails and leaves four servers stopped is an
     * outage caused by a safety measure, which is worse than having no backup at all.
     */
    private Outcome backup(final UpdateRequest request, final Consumer<UpdateReport> progress) {
        final UpdateRun run = new UpdateRun(containers, backups.volumes(), progress);

        final RuntimeResult runtime = run.check();
        if (!runtime.reached()) {
            return Outcome.failed(UpdateReports.toJson(UpdateReport.at(UpdateReport.Stage.FAILED)
                    .withNote(runtime.message())));
        }

        final List<String> volumes = config.backup().volumes().stream()
                .filter(volume -> volume != null && !volume.isBlank())
                .map(String::trim)
                .toList();
        if (volumes.isEmpty()) {
            // Nothing to save is not a quiet success. Taking the network down for a list somebody
            // emptied by accident would be an outage that reports as a backup.
            return Outcome.failed(UpdateReports.toJson(UpdateReport.at(UpdateReport.Stage.FAILED)
                    .withNote("backup.volumes in steward.yml is empty, so there is nothing to save"
                            + " and nothing was stopped.")));
        }

        // The same lock an update and a restart take. A backup stops servers, so overlapping it
        // with a run that is moving their jars is finding 147 arriving from a third direction.
        final Optional<RunLock> lock;
        try {
            lock = RunLock.tryAcquire(database.dataSource());
        } catch (final SQLException failure) {
            return Outcome.failed(UpdateReports.toJson(UpdateReport.at(UpdateReport.Stage.FAILED)
                    .withNote("Could not reach the database to take the steward-worker lock: "
                            + failure)));
        }
        if (lock.isEmpty()) {
            return Outcome.failed(UpdateReports.toJson(UpdateReport.at(UpdateReport.Stage.FAILED)
                    .withNote("Another steward-worker run is in progress - nothing was backed up."
                            + " That is either an update, a restart or a `docker compose run --rm"
                            + " steward-worker bootstrap` somebody started on the host. Wait for it"
                            + " and ask again.")));
        }
        try (RunLock held = lock.get()) {
            return backupUnderLock(request, run, runtime, volumes, progress);
        }
    }

    private Outcome backupUnderLock(final UpdateRequest request, final UpdateRun run,
                                    final RuntimeResult runtime, final List<String> volumes,
                                    final Consumer<UpdateReport> progress) {

        // THE DATABASE FIRST, WITH EVERYTHING STILL RUNNING.
        //
        // pg_dump takes an MVCC snapshot, so it is consistent as of the moment it starts and needs
        // nothing stopped. Taking it here rather than after the stop is minutes off the outage for
        // free - and it means that if the volume half goes wrong, the database dump of that night
        // still exists.
        final SnapshotResult dumped = backups.saveDatabase();
        UpdateReport planned = UpdateReport.at(UpdateReport.Stage.STOPPING)
                .with(new UpdateReport.ServiceLine(DatabaseDump.NAME,
                        dumped.ok() ? UpdateReport.State.SAVED : UpdateReport.State.FAILED,
                        List.of(new UpdateReport.Change("backup", null, dumped.message())),
                        dumped.ok() ? null : dumped.message()));
        progress.accept(planned);

        for (final String service : config.backup().stopServices()) {
            if (service == null || service.isBlank()) {
                continue;
            }
            planned = planned.with(new UpdateReport.ServiceLine(service.trim(),
                    UpdateReport.State.PLANNED,
                    List.of(new UpdateReport.Change("backup", null, "stopped while saving")), null));
        }

        // season-2-ops/122, and it is Till's own correction of 2026-09-20: A BACKUP RUNS THE SAME
        // CHOREOGRAPHY AS AN UPDATE. A service that stops for a snapshot throws people out exactly
        // as hard as one that stops for a new jar, and that these were two mechanisms was a story
        // about how they were written rather than a design.
        final Choreography choreography = new Choreography(containers, occupancy(), waiting);
        final Choreography.Window window = choreography.open(movingServices(planned));
        if (!window.opened()) {
            return Outcome.failed(UpdateReports.toJson(planned
                    .withStage(UpdateReport.Stage.FAILED)
                    .withNote("NOTHING WAS STOPPED AND NOTHING WAS SAVED. " + window.refusal()
                            + ". The database dump above was taken with everything running and is"
                            + " real; the volumes were not touched.")));
        }
        if (!window.isEmpty()) {
            planned = planned.withNote(String.join(", ", window.standbys())
                    + " started and healthy, so this backup has somewhere to put the players.");
            progress.accept(planned);
        }
        try {
            if (!countDown(request.id(), planned, progress)) {
                return cancelled();
            }

            // Wait for them to be gone, then stop anyway after the cap - see Choreography.
            final String stillOn = choreography.waitUntilEmpty(movingServices(planned));
            if (stillOn != null) {
                planned = planned.withNote(stillOn);
                progress.accept(planned);
            }

            final UpdateRun.Stopped stopped = run.stop(planned, runtime);

            // A service that refused to stop is still writing to a volume this run is about to
            // snapshot, and a torn snapshot fails at RESTORE rather than here - the one place a
            // failure is useless. So nothing is saved, and whatever did stop is started again.
            final List<String> notStopped = servicesThatRefused(planned, stopped.services());
            if (!notStopped.isEmpty()) {
                final UpdateReport back = run.start(new UpdateRun.Stopped(
                        stopped.report().withNote("NOTHING WAS SAVED. " + String.join(", ", notStopped)
                                + " could not be stopped, and a snapshot of a running server is one"
                                + " that fails when somebody tries to restore it. Every service that"
                                + " did stop has been started again."),
                        stopped.services(), runtime));
                return Outcome.failed(UpdateReports.toJson(run
                        .verify(back, stopped.services(), UpdateRun.Waiting.real())
                        .withStage(UpdateReport.Stage.FAILED)));
            }

            final UpdateReport saved = run.save(stopped.report(), volumes);

            // Retention runs while the servers are still down, and that is deliberate: deleting files
            // is quick, and doing it here means the disk has room before the next run rather than
            // after it. What was deleted goes into the report - a retention nobody sees is one that
            // has been deleting the wrong thing for months.
            final StewardSpec.BackupSpec.RetentionSpec keep = config.backup().retention();
            final Retention policy = new Retention(keep.daily(), keep.weekly(), keep.monthly(),
                    keep.collapseAfterDays());
            final List<String> pruned = backups.volumes().prune(policy);
            final UpdateReport swept = pruned.isEmpty() ? saved
                    : saved.withNote("kept " + policy.daily() + " daily, " + policy.weekly()
                            + " weekly and " + policy.monthly() + " monthly of each series, and removed "
                            + pruned.size() + ": " + String.join(", ", pruned));

            final UpdateReport started = run.start(
                    new UpdateRun.Stopped(swept, stopped.services(), runtime));
            final UpdateReport verified = run.verify(started, stopped.services(),
                    UpdateRun.Waiting.real());

            final UpdateReport told = noteStandbys(verified, choreography.close());
            final UpdateReport finished = settle(told, run.unverifiedStops(),
                    "the archives were taken - they were kept, and each one has a .unverified file"
                            + " beside it saying so, which `deploy/restore.sh --list` prints",
                    false, Doubt.FAILS_THE_RUN);
            return finished.stage() == UpdateReport.Stage.FAILED
                    ? Outcome.failed(UpdateReports.toJson(finished))
                    : Outcome.done(UpdateReports.toJson(finished));
        } finally {
            choreography.close();
        }
    }

    // ---------------------------------------------------------------- restart

    /**
     * The same sequence with nothing installed: stop the servers, start them, wait for them.
     *
     * <p>It used to be one redeploy of the whole project asked for over HTTP, whose successful
     * outcome was usually that it never returned - the redeploy took this container down mid-call
     * and the next start read a {@code RESTART} left {@code RUNNING} as "it happened". That is gone,
     * and with it the reason nobody could ever be told whether the network came back: cycling the four
     * Minecraft services one at a time leaves steward-worker running, so it can watch and say.</p>
     */
    private Outcome restart(final UpdateRequest request, final Consumer<UpdateReport> progress) {
        final UpdateRun run = new UpdateRun(containers, backups.volumes(), progress);

        final RuntimeResult runtime = run.check();
        if (!runtime.reached()) {
            return Outcome.failed(UpdateReports.toJson(UpdateReport.at(UpdateReport.Stage.FAILED)
                    .withNote(runtime.message())));
        }

        // The same lock an update takes, and for a reason a restart makes worse rather than
        // better: `steward-worker bootstrap` on the host holds it while it moves jars into empty
        // slots, and a restart that cycles the servers underneath that would be exactly the
        // swap-under-a-running-JVM this whole design exists to end - arriving from the other
        // direction.
        final Optional<RunLock> lock;
        try {
            lock = RunLock.tryAcquire(database.dataSource());
        } catch (final SQLException failure) {
            return Outcome.failed(UpdateReports.toJson(UpdateReport.at(UpdateReport.Stage.FAILED)
                    .withNote("Could not reach the database to take the steward-worker lock: "
                            + failure)));
        }
        if (lock.isEmpty()) {
            return Outcome.failed(UpdateReports.toJson(UpdateReport.at(UpdateReport.Stage.FAILED)
                    .withNote("Another steward-worker run is in progress - nothing was restarted."
                            + " That is either an update or a `docker compose run --rm"
                            + " steward-worker bootstrap` somebody started on the host. Wait for it"
                            + " and ask again.")));
        }
        try (RunLock held = lock.get()) {
            return restartUnderLock(request, run, runtime, progress);
        }
    }

    private Outcome restartUnderLock(final UpdateRequest request, final UpdateRun run,
                                     final RuntimeResult runtime,
                                     final Consumer<UpdateReport> progress) {

        // A restart has no plan, so every Minecraft service is named as work with no changes
        // against it - which is what makes stop() take them and the report show a line each.
        // season-2-ops/125: everything except what somebody is holding down. A restart that
        // started a service back up would undo a decision without anybody asking for it, and
        // "restart the network" is the most likely way for that to happen by accident.
        final List<String> holds = held();
        UpdateReport planned = UpdateReport.at(UpdateReport.Stage.STOPPING);
        for (final String service : Topology.SERVICES.stream().map(Topology.Service::name)
                .filter(service -> !holds.contains(service)).toList()) {
            planned = planned.with(new UpdateReport.ServiceLine(service,
                    UpdateReport.State.PLANNED,
                    List.of(new UpdateReport.Change("restart", null, "no change")), null));
        }
        if (planned.services().isEmpty()) {
            return Outcome.done(UpdateReports.toJson(UpdateReport
                    .at(UpdateReport.Stage.NOTHING_TO_DO)
                    .withNote("Every Minecraft service is being held down, so there was nothing to"
                            + " restart. Nothing was stopped.")));
        }
        final List<String> untouched = Topology.SERVICES.stream().map(Topology.Service::name)
                .filter(holds::contains).toList();

        // The same choreography as an update and a backup (season-2-ops/122). A restart is the run
        // that stops the MOST, so it is the one that needs both standbys - and it is also the one
        // where "the servers just went away" was hardest to distinguish from a crash.
        final Choreography choreography = new Choreography(containers, occupancy(), waiting);
        final Choreography.Window window = choreography.open(movingServices(planned));
        if (!window.opened()) {
            return Outcome.failed(UpdateReports.toJson(planned
                    .withStage(UpdateReport.Stage.FAILED)
                    .withNote("NOTHING WAS RESTARTED. " + window.refusal()
                            + ". Every service is still running exactly as it was.")));
        }
        if (!window.isEmpty()) {
            planned = planned.withNote(String.join(", ", window.standbys())
                    + " started and healthy, so this restart has somewhere to put the players.");
            progress.accept(planned);
        }
        try {
            // A restart has no plan to resolve, so unlike an update it always has work: the whole of
            // it is "take these four round once". The countdown is therefore unconditional here, and
            // it is the same countdown, cancelled by the same command.
            if (!countDown(request.id(), planned, progress)) {
                return cancelled();
            }

            final String stillOn = choreography.waitUntilEmpty(movingServices(planned));
            if (stillOn != null) {
                planned = planned.withNote(stillOn);
                progress.accept(planned);
            }

            final UpdateRun.Stopped stopped = run.stop(planned, runtime);
            final UpdateReport started = run.start(stopped);
            final UpdateReport verified = run.verify(started, stopped.services(),
                    UpdateRun.Waiting.real());

            final UpdateReport told = untouched.isEmpty() ? verified
                    : verified.withNote(String.join(", ", untouched) + " is being held down and was not"
                            + " restarted. It stays down until somebody starts it.");
            final UpdateReport finished = settle(noteStandbys(told, choreography.close()),
                    run.unverifiedStops(),
                    "it was started again on the same world", false, Doubt.IS_ONLY_SAID);
            return finished.stage() == UpdateReport.Stage.FAILED
                    ? Outcome.failed(UpdateReports.toJson(finished))
                    : Outcome.done(UpdateReports.toJson(finished));
        } finally {
            choreography.close();
        }
    }

    // ---------------------------------------------------------------- down, and up again

    /**
     * The two services a run may never put down, because the run is standing on them.
     *
     * <p>{@code steward-worker} is the process performing the sequence and {@code postgres} holds
     * the row it writes its report into. A DOWN naming either would be a request that cannot report
     * what it did - and in the worker's case could not even release its own lock. Refused by name,
     * before anything is stopped, rather than discovered halfway through.</p>
     */
    private static final List<String> NEVER_DOWN = List.of(Topology.STEWARD_WORKER, "postgres");

    /** @return the services somebody is deliberately holding down, in no particular order */
    private List<String> held() {
        return directory.holds().stream().map(ServiceHold::service).toList();
    }

    /**
     * Stop the named services and leave them stopped (season-2-ops/125).
     *
     * <h2>The whole ordinary procedure, and then one step less</h2>
     * Countdown, park the players, stop, report - the same sequence a restart runs, with the
     * starting half removed and a row in {@code service_hold} in its place. The row is what makes
     * this survive a restart of this process, and what every later run reads so that nothing brings
     * back a service somebody stopped in order to work on it.
     *
     * <h2>A DOWN has to name its services</h2>
     * An empty scope means "the whole network" everywhere else in this mechanism, and here that
     * would be a button that stops everything with no way back except another button. It is refused
     * rather than interpreted: the interface never offers it, and a row written by hand that
     * forgets the scope is far more likely to be a mistake than a request to take the network down
     * indefinitely.
     */
    private Outcome down(final UpdateRequest request, final Consumer<UpdateReport> progress) {
        final List<String> scope = directory.scopeOf(request.id());
        if (scope.isEmpty()) {
            return Outcome.failed(UpdateReports.toJson(UpdateReport.at(UpdateReport.Stage.FAILED)
                    .withNote("This request asks to put services down without naming any. Nothing"
                            + " was stopped: an unnamed scope means the whole network, and taking"
                            + " the whole network down until somebody presses Start is not"
                            + " something anybody asks for by leaving a field empty.")));
        }
        final List<String> refused = scope.stream().filter(NEVER_DOWN::contains).toList();
        if (!refused.isEmpty()) {
            return Outcome.failed(UpdateReports.toJson(UpdateReport.at(UpdateReport.Stage.FAILED)
                    .withNote("Nothing was stopped: " + String.join(", ", refused) + " cannot be"
                            + " put down from here. This sequence runs inside steward-worker and"
                            + " writes its report through postgres, so a run that stopped either"
                            + " one could not say what it had done.")));
        }

        final UpdateRun run = new UpdateRun(containers, backups.volumes(), progress);
        final RuntimeResult runtime = run.check();
        if (!runtime.reached()) {
            return Outcome.failed(UpdateReports.toJson(UpdateReport.at(UpdateReport.Stage.FAILED)
                    .withNote(runtime.message())));
        }

        final Optional<RunLock> lock;
        try {
            lock = RunLock.tryAcquire(database.dataSource());
        } catch (final SQLException failure) {
            return Outcome.failed(UpdateReports.toJson(UpdateReport.at(UpdateReport.Stage.FAILED)
                    .withNote("Could not reach the database to take the steward-worker lock, so"
                            + " nothing was stopped: " + failure)));
        }
        if (lock.isEmpty()) {
            return Outcome.failed(UpdateReports.toJson(UpdateReport.at(UpdateReport.Stage.FAILED)
                    .withNote("Another steward-worker run is in progress - nothing was stopped."
                            + " Wait for it and ask again.")));
        }
        try (RunLock held = lock.get()) {
            return downUnderLock(request, scope, run, runtime, progress);
        }
    }

    private Outcome downUnderLock(final UpdateRequest request, final List<String> scope,
                                  final UpdateRun run, final RuntimeResult runtime,
                                  final Consumer<UpdateReport> progress) {
        UpdateReport planned = UpdateReport.at(UpdateReport.Stage.STOPPING);
        for (final String service : scope) {
            planned = planned.with(new UpdateReport.ServiceLine(service,
                    UpdateReport.State.PLANNED,
                    List.of(new UpdateReport.Change("down", null, "stays down")), null));
        }

        // Only when somebody could be standing on one of them. Counting down thirty seconds before
        // stopping the pack host would be a warning about something no player can tell happened -
        // and the countdown is the players' warning, not the run's ceremony.
        if (scope.stream().anyMatch(Runner::isMinecraft)
                && !countDown(request.id(), planned, progress)) {
            return cancelled();
        }

        final UpdateRun.Stopped stopped = run.stop(planned, runtime);
        for (final String service : stopped.services()) {
            directory.hold(service, request.requestedBy(), request.id());
        }

        UpdateReport report = stopped.report();
        if (!stopped.services().isEmpty()) {
            // Worded so that one service and four read the same. "limbo will stay down until
            // somebody starts them again" is what naming the services inside the sentence gives,
            // and a report is read far more often than it is written.
            report = report.withNote("Held down: " + String.join(", ", stopped.services())
                    + ". Nothing starts a held service again on its own: not a later update run,"
                    + " not a restart, and not this worker coming back.");
        }
        final UpdateReport finished = settle(report, run.unverifiedStops(),
                "it was put down on purpose", false, Doubt.IS_ONLY_SAID);
        return finished.stage() == UpdateReport.Stage.FAILED
                ? Outcome.failed(UpdateReports.toJson(finished))
                : Outcome.done(UpdateReports.toJson(finished));
    }

    /**
     * The other half: take the hold off and start the services again.
     *
     * <p>No countdown. Nothing goes down, so there is nothing to warn anybody about, and thirty
     * seconds of "the network is about to be interrupted" before a server comes back would be a
     * warning about good news.</p>
     *
     * <p>An empty scope here is <b>every held service</b>, and that asymmetry with {@link #down} is
     * deliberate: the dangerous direction is the one that stops things. Starting everything that
     * somebody stopped is the recovery an operator wants after a restart of this process, and it
     * can do no harm that was not already asked for.</p>
     */
    private Outcome startHeld(final UpdateRequest request, final Consumer<UpdateReport> progress) {
        final List<String> asked = directory.scopeOf(request.id());
        final List<String> holds = held();
        final List<String> services = asked.isEmpty() ? holds : asked;
        if (services.isEmpty()) {
            return Outcome.done(UpdateReports.toJson(UpdateReport
                    .at(UpdateReport.Stage.NOTHING_TO_DO)
                    .withNote("No service is being held down, so there was nothing to start.")));
        }

        final UpdateRun run = new UpdateRun(containers, backups.volumes(), progress);
        final RuntimeResult runtime = run.check();
        if (!runtime.reached()) {
            return Outcome.failed(UpdateReports.toJson(UpdateReport.at(UpdateReport.Stage.FAILED)
                    .withNote(runtime.message())));
        }

        final Optional<RunLock> lock;
        try {
            lock = RunLock.tryAcquire(database.dataSource());
        } catch (final SQLException failure) {
            return Outcome.failed(UpdateReports.toJson(UpdateReport.at(UpdateReport.Stage.FAILED)
                    .withNote("Could not reach the database to take the steward-worker lock, so"
                            + " nothing was started: " + failure)));
        }
        if (lock.isEmpty()) {
            return Outcome.failed(UpdateReports.toJson(UpdateReport.at(UpdateReport.Stage.FAILED)
                    .withNote("Another steward-worker run is in progress - nothing was started."
                            + " Wait for it and ask again.")));
        }
        try (RunLock held = lock.get()) {
            UpdateReport planned = UpdateReport.at(UpdateReport.Stage.STARTING);
            for (final String service : services) {
                planned = planned.with(new UpdateReport.ServiceLine(service,
                        UpdateReport.State.STOPPED,
                        List.of(new UpdateReport.Change("down", "stays down", "starting")), null));
            }
            // The hold comes off BEFORE the start, not after: a start that never returns must not
            // leave a service running with a row still claiming somebody is holding it down. The
            // opposite order is recoverable by pressing the button again; this one is not.
            for (final String service : services) {
                directory.release(service);
            }
            final UpdateReport started = run.start(new UpdateRun.Stopped(planned, services, runtime));
            final UpdateReport verified = run.verify(started, services, UpdateRun.Waiting.real());
            final UpdateReport finished = settle(verified, List.of(),
                    "it was started again", false, Doubt.IS_ONLY_SAID);
            return finished.stage() == UpdateReport.Stage.FAILED
                    ? Outcome.failed(UpdateReports.toJson(finished))
                    : Outcome.done(UpdateReports.toJson(finished));
        }
    }

    /** @return whether that service is one of the four somebody can be standing on */
    private static boolean isMinecraft(final String service) {
        return Topology.SERVICES.stream().anyMatch(one -> one.name().equals(service));
    }

    /**
     * The services a report says this run is going to stop (season-2-ops/122).
     *
     * <p>The same two exemptions {@code UpdateRun#stop} makes, and for the same reasons:
     * steward-worker is never stopped because this sequence is running inside it, and
     * {@code database} is a backup's dump line rather than a compose service at all. Getting either
     * of them into this list would ask for a standby of something that has none and then abort the
     * run over it.</p>
     */
    private static List<String> movingServices(final UpdateReport report) {
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
     * <p>A note per standby rather than a service line, and that is a decision rather than
     * laziness: a line would be read by {@code Evacuation} as a service this run is moving, and the
     * proxy would then try to evacuate players <b>off</b> the very standby they were just parked
     * on. A standby is scenery, not a cast member.</p>
     */
    private static UpdateReport noteStandbys(final UpdateReport report, final List<String> said) {
        UpdateReport told = report;
        for (final String sentence : said) {
            told = told.withNote(sentence);
        }
        return told;
    }

}
