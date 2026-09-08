package eu.nordtal.s2.updater.serve;

import eu.nordtal.jcore.persistence.sql.Database;
import eu.nordtal.s2.common.update.UpdateReport;
import eu.nordtal.s2.common.update.UpdateReports;
import eu.nordtal.s2.common.update.UpdateRequest;
import eu.nordtal.s2.updater.apply.ApplyResult;
import eu.nordtal.s2.updater.arcane.Arcane;
import eu.nordtal.s2.updater.config.UpdaterSpec;
import eu.nordtal.s2.updater.arcane.RuntimeResult;
import eu.nordtal.s2.updater.plan.PlanReport;
import eu.nordtal.s2.updater.plan.Report;
import eu.nordtal.s2.updater.plan.Topology;
import eu.nordtal.s2.updater.plan.UpdatePlan;
import eu.nordtal.s2.updater.run.Runs;
import eu.nordtal.s2.updater.schema.RunLock;

import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import java.sql.SQLException;
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
 * with nothing installed. {@code APPLY} is retired and refused - see {@code UpdateKind}.
 *
 * <h2>Every answer is a report, and the report is JSON</h2>
 * Since 2026-09-07 an answer is an {@code UpdateReport} rather than a paragraph, so that Discord
 * can draw a field per service and a console can print {@code render()} of the same object. The
 * updater is still the only thing that decides anything; it is no longer the only thing that draws.
 */
@Slf4j
public final class Runner implements RequestRunner {

    private final UpdaterSpec config;
    private final Database database;
    private final Arcane arcane;

    public Runner(final @NotNull UpdaterSpec config, final @NotNull Database database,
                  final @NotNull Arcane arcane) {
        this.config = config;
        this.database = database;
        this.arcane = arcane;
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
                case UPDATE -> update(progress);
                case RESTART -> restart(progress);
            };
        } catch (final RuntimeException failure) {
            log.error("Request {} ({}) failed", request.id(), request.kind(), failure);
            return Outcome.failed("This request failed: " + failure
                    + "\nThe updater's log has the stack trace.");
        }
    }

    // ---------------------------------------------------------------- report

    private Outcome report() {
        final UpdatePlan plan = Runs.resolve(config);
        final UpdateReport report = PlanReport.of(plan);
        // A plan full of rows that could not be checked is still a report, and the report says so
        // in the service lines. Marking the request FAILED would make "GitHub was briefly
        // unreachable" look like a broken updater.
        return Outcome.done(UpdateReports.toJson(report.withStage(report.isWork()
                ? UpdateReport.Stage.PLANNED : UpdateReport.Stage.NOTHING_TO_DO)));
    }

    // ---------------------------------------------------------------- the update

    /**
     * The whole sequence: check, resolve, count down (already spent), stop, migrate, swap, start,
     * verify.
     *
     * <p>The order is the design and every step of it is load-bearing. Arcane is read <b>first</b>,
     * before a version is resolved or a byte is downloaded, because a run that cannot stop a server
     * must not move a jar - that is finding 147, and continuing anyway would be the defect
     * performed as a fallback. The migration runs with every affected server <b>stopped</b>, which
     * is stronger than the old rule (it ran before the jars moved, but with the servers up), so a
     * plugin can no longer see a schema half a version away from itself.</p>
     */
    private Outcome update(final Consumer<UpdateReport> progress) {
        final UpdateRun run = new UpdateRun(arcane, progress);

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
                    .withNote("Could not reach the database to take the updater lock: " + failure)));
        }
        if (lock.isEmpty()) {
            return Outcome.failed(UpdateReports.toJson(UpdateReport.at(UpdateReport.Stage.FAILED)
                    .withNote("Another updater run is in progress - nothing was done. That is"
                            + " either another update or a `docker compose run --rm updater"
                            + " bootstrap` somebody started on the host. Wait for it to finish and"
                            + " ask again.")));
        }

        try (RunLock held = lock.get()) {
            final UpdatePlan plan = Runs.resolve(config);
            final UpdateReport planned = PlanReport.of(plan);

            if (!planned.isWork()) {
                // A third answer, not a quiet kind of "fine": a run where nothing could be checked
                // did no work AND had no failure, and closing it with "nothing needed doing" is how
                // somebody reads it as "the network is current".
                return Outcome.done(UpdateReports.toJson(planned.withStage(plan.hasFailures()
                        ? UpdateReport.Stage.FAILED : UpdateReport.Stage.NOTHING_TO_DO)));
            }

            final UpdateRun.Stopped stopped = run.stop(planned, runtime);

            // A service that has work and did not stop is still RUNNING, and Runs.apply would move
            // its jars anyway - which is finding 147 exactly, reached through the one path that was
            // supposed to end it. Nothing is migrated and nothing is installed; whatever DID stop is
            // started again, because leaving half a network down over a refused stop turns a
            // cancelled update into an outage.
            final List<String> notStopped = planned.services().stream()
                    .filter(line -> !line.changes().isEmpty())
                    .map(UpdateReport.ServiceLine::service)
                    .filter(service -> !Topology.UPDATER.equals(service))
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
                eu.nordtal.s2.updater.schema.Schema.migrate(database);
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
                    new UpdateRun.Stopped(report, stopped.services(), runtime));
            final UpdateReport verified = run.verify(started, stopped.services(),
                    UpdateRun.Waiting.real());

            final boolean failed = result.hasFailures() || verified.services().stream()
                    .anyMatch(line -> line.state() == UpdateReport.State.FAILED);
            final UpdateReport finished = verified.withStage(failed
                    ? UpdateReport.Stage.FAILED : UpdateReport.Stage.DONE);
            return failed
                    ? Outcome.failed(UpdateReports.toJson(finished))
                    : Outcome.done(UpdateReports.toJson(finished));
        }
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

    // ---------------------------------------------------------------- restart

    /**
     * The same sequence with nothing installed: stop the servers, start them, wait for them.
     *
     * <p>It used to be one Arcane redeploy of the whole project, whose successful outcome was
     * usually that it never returned - the redeploy took this container down mid-call and the next
     * start read a {@code RESTART} left {@code RUNNING} as "it happened". That is gone, and with it
     * the reason nobody could ever be told whether the network came back: cycling the four
     * Minecraft services one at a time leaves the updater running, so it can watch and say.</p>
     */
    private Outcome restart(final Consumer<UpdateReport> progress) {
        final UpdateRun run = new UpdateRun(arcane, progress);

        final RuntimeResult runtime = run.check();
        if (!runtime.reached()) {
            return Outcome.failed(UpdateReports.toJson(UpdateReport.at(UpdateReport.Stage.FAILED)
                    .withNote(runtime.message())));
        }

        // The same lock an update takes, and for a reason a restart makes worse rather than
        // better: `updater bootstrap` on the host holds it while it moves jars into empty slots,
        // and a restart that cycles the servers underneath that would be exactly the swap-under-a-
        // running-JVM this whole design exists to end - arriving from the other direction.
        final Optional<RunLock> lock;
        try {
            lock = RunLock.tryAcquire(database.dataSource());
        } catch (final SQLException failure) {
            return Outcome.failed(UpdateReports.toJson(UpdateReport.at(UpdateReport.Stage.FAILED)
                    .withNote("Could not reach the database to take the updater lock: " + failure)));
        }
        if (lock.isEmpty()) {
            return Outcome.failed(UpdateReports.toJson(UpdateReport.at(UpdateReport.Stage.FAILED)
                    .withNote("Another updater run is in progress - nothing was restarted. That is"
                            + " either an update or a `docker compose run --rm updater bootstrap`"
                            + " somebody started on the host. Wait for it and ask again.")));
        }
        try (RunLock held = lock.get()) {
            return restartUnderLock(run, runtime);
        }
    }

    private Outcome restartUnderLock(final UpdateRun run, final RuntimeResult runtime) {

        // A restart has no plan, so every Minecraft service is named as work with no changes
        // against it - which is what makes stop() take them and the report show a line each.
        UpdateReport planned = UpdateReport.at(UpdateReport.Stage.STOPPING);
        for (final String service : Topology.SERVICES.stream().map(Topology.Service::name).toList()) {
            planned = planned.with(new UpdateReport.ServiceLine(service,
                    UpdateReport.State.PLANNED,
                    List.of(new UpdateReport.Change("restart", null, "no change")), null));
        }

        final UpdateRun.Stopped stopped = run.stop(planned, runtime);
        final UpdateReport started = run.start(stopped);
        final UpdateReport verified = run.verify(started, stopped.services(),
                UpdateRun.Waiting.real());

        final boolean failed = verified.services().stream()
                .anyMatch(line -> line.state() == UpdateReport.State.FAILED);
        final UpdateReport finished = verified.withStage(failed
                ? UpdateReport.Stage.FAILED : UpdateReport.Stage.DONE);
        return failed
                ? Outcome.failed(UpdateReports.toJson(finished))
                : Outcome.done(UpdateReports.toJson(finished));
    }
}
