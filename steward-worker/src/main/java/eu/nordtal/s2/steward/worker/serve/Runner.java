package eu.nordtal.s2.steward.worker.serve;

import eu.nordtal.jcore.persistence.sql.Database;
import eu.nordtal.s2.common.update.UpdateDirectory;
import eu.nordtal.s2.common.update.UpdateReport;
import eu.nordtal.s2.common.update.UpdateReports;
import eu.nordtal.s2.common.update.UpdateRequest;
import eu.nordtal.s2.common.update.UpdateStatus;
import eu.nordtal.s2.steward.worker.apply.ApplyResult;
import eu.nordtal.s2.steward.worker.backup.Backups;
import eu.nordtal.s2.steward.worker.backup.DatabaseDump;
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

    public Runner(final @NotNull StewardSpec config, final @NotNull Database database,
                  final @NotNull ContainerOps containers, final @NotNull Backups backups,
                  final @NotNull UpdateDirectory directory) {
        this(config, database, containers, backups, directory, UpdateRun.Waiting.real());
    }

    /** Package-visible so a test can drive a thirty-second countdown without waiting for one. */
    Runner(final @NotNull StewardSpec config, final @NotNull Database database,
           final @NotNull ContainerOps containers, final @NotNull Backups backups,
           final @NotNull UpdateDirectory directory, final @NotNull UpdateRun.Waiting waiting) {
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
            };
        } catch (final RuntimeException failure) {
            log.error("Request {} ({}) failed", request.id(), request.kind(), failure);
            return Outcome.failed("This request failed: " + failure
                    + "\nSteward-worker's log has the stack trace.");
        }
    }

    // ---------------------------------------------------------------- report

    private Outcome report() {
        final UpdatePlan plan = Runs.resolve(config);
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
            if (!RECREATABLE.contains(service)) {
                foreign.add(service);
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
    private static final java.util.Set<String> RECREATABLE = java.util.stream.Stream.concat(
                    Topology.SERVICES.stream().map(Topology.Service::name),
                    java.util.stream.Stream.of(Topology.DISCORD_BOT))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

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
            final UpdatePlan plan = Runs.resolve(config);
            final UpdateReport planned = withImages(PlanReport.of(plan), images);

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

            if (!countDown(request.id(), planned, progress)) {
                return cancelled();
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

            final UpdateReport finished = settle(verified, run.unverifiedStops(),
                    "the jars were moved into its plugins directory", result.hasFailures(),
                    Doubt.FAILS_THE_RUN);
            return finished.stage() == UpdateReport.Stage.FAILED
                    ? Outcome.failed(UpdateReports.toJson(finished))
                    : Outcome.done(UpdateReports.toJson(finished));
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
         * and the consequence is the one that was wanted, because a failed run authorises no farm
         * reset. Run 23's shape is a green report over an archive nobody should have trusted; 147's
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
                                ? " This run is reported as FAILED for that reason alone, which"
                                        + " also means it authorises no farm reset."
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

        if (!countDown(request.id(), planned, progress)) {
            return cancelled();
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
        final List<String> pruned = backups.volumes().prune(config.backup().keep());
        final UpdateReport swept = pruned.isEmpty() ? saved
                : saved.withNote("kept the newest " + config.backup().keep() + " of each and"
                        + " removed " + pruned.size() + ": " + String.join(", ", pruned));

        final UpdateReport started = run.start(
                new UpdateRun.Stopped(swept, stopped.services(), runtime));
        final UpdateReport verified = run.verify(started, stopped.services(),
                UpdateRun.Waiting.real());

        final UpdateReport finished = settle(verified, run.unverifiedStops(),
                "the archives were taken - they were kept, and each one has a .unverified file"
                        + " beside it saying so, which `deploy/restore.sh --list` prints",
                false, Doubt.FAILS_THE_RUN);
        return finished.stage() == UpdateReport.Stage.FAILED
                ? Outcome.failed(UpdateReports.toJson(finished))
                : Outcome.done(UpdateReports.toJson(finished));
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
        UpdateReport planned = UpdateReport.at(UpdateReport.Stage.STOPPING);
        for (final String service : Topology.SERVICES.stream().map(Topology.Service::name).toList()) {
            planned = planned.with(new UpdateReport.ServiceLine(service,
                    UpdateReport.State.PLANNED,
                    List.of(new UpdateReport.Change("restart", null, "no change")), null));
        }

        // A restart has no plan to resolve, so unlike an update it always has work: the whole of
        // it is "take these four round once". The countdown is therefore unconditional here, and
        // it is the same countdown, cancelled by the same command.
        if (!countDown(request.id(), planned, progress)) {
            return cancelled();
        }

        final UpdateRun.Stopped stopped = run.stop(planned, runtime);
        final UpdateReport started = run.start(stopped);
        final UpdateReport verified = run.verify(started, stopped.services(),
                UpdateRun.Waiting.real());

        final UpdateReport finished = settle(verified, run.unverifiedStops(),
                "it was started again on the same world", false, Doubt.IS_ONLY_SAID);
        return finished.stage() == UpdateReport.Stage.FAILED
                ? Outcome.failed(UpdateReports.toJson(finished))
                : Outcome.done(UpdateReports.toJson(finished));
    }
}
