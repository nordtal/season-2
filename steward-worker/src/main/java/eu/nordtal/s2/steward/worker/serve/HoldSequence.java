package eu.nordtal.s2.steward.worker.serve;

import eu.nordtal.s2.database.update.UpdateReport;
import eu.nordtal.s2.database.update.UpdateReports;
import eu.nordtal.s2.database.update.UpdateRequest;
import eu.nordtal.s2.steward.worker.ops.RuntimeResult;
import eu.nordtal.s2.steward.worker.plan.Topology;
import eu.nordtal.s2.steward.worker.schema.RunLock;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/** The two halves of holding a service down and letting it go, on request rather than as part of any other run. */
final class HoldSequence {

    /** The two services a DOWN may never name, because the run reports through them. */
    private static final List<String> NEVER_DOWN = List.of(Topology.STEWARD_WORKER, "postgres");

    private HoldSequence() {}

    /**
     * Stops the named services and leaves them stopped, recorded in {@code service_hold} so no run restarts them.
     *
     * An empty scope is refused rather than read as the whole network.
     */
    static Outcome down(final Runner runner, final UpdateRequest request, final Consumer<UpdateReport> progress) {
        final List<String> scope = runner.directory.scopeOf(request.id());
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

        final UpdateRun run = new UpdateRun(runner.containers, runner.backups.volumes(), progress);
        final RuntimeResult runtime = run.check();
        if (!runtime.reached()) {
            return Outcome.failed(UpdateReports.toJson(
                    UpdateReport.at(UpdateReport.Stage.FAILED).withNote(Runner.unreachableMessage(runtime))));
        }

        final Optional<RunLock> lock;
        try {
            lock = RunLock.tryAcquire(runner.database.dataSource());
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
            return downUnderLock(runner, request, scope, run, runtime, progress);
        }
    }

    private static Outcome downUnderLock(
            final Runner runner,
            final UpdateRequest request,
            final List<String> scope,
            final UpdateRun run,
            final RuntimeResult runtime,
            final Consumer<UpdateReport> progress) {
        UpdateReport planned = UpdateReport.at(UpdateReport.Stage.STOPPING);
        for (final String service : scope) {
            planned = planned.with(new UpdateReport.ServiceLine(
                    service,
                    UpdateReport.State.PLANNED,
                    List.of(new UpdateReport.Change("down", null, "stays down")),
                    null));
        }

        // Only when somebody could be standing on one of them, since the countdown warns players.
        if (scope.stream().anyMatch(Runner::isMinecraft) && !runner.countDown(request.id(), planned, progress)) {
            return Runner.cancelled();
        }

        final UpdateRun.Stopped stopped = run.stop(planned, runtime);
        for (final String service : stopped.services()) {
            runner.directory.hold(service, request.requestedBy(), request.id());
        }

        UpdateReport report = stopped.report();
        if (!stopped.services().isEmpty()) {
            // Worded so that one service and four read the same.
            report = report.withNote("Held down: " + String.join(", ", stopped.services())
                    + ". Nothing starts a held service again on its own: not a later update run,"
                    + " not a restart, and not this worker coming back.");
        }
        final UpdateReport finished = Runner.settle(
                report, run.unverifiedStops(), "it was put down on purpose", false, Runner.Doubt.IS_ONLY_SAID);
        return finished.stage() == UpdateReport.Stage.FAILED
                ? Outcome.failed(UpdateReports.toJson(finished))
                : Outcome.done(UpdateReports.toJson(finished));
    }

    /**
     * Takes the hold off and starts the services again, without a countdown.
     *
     * An empty scope here means every held service, unlike {@link #down}.
     */
    static Outcome startHeld(final Runner runner, final UpdateRequest request, final Consumer<UpdateReport> progress) {
        final List<String> asked = runner.directory.scopeOf(request.id());
        final List<String> holds = runner.held();
        final List<String> services = asked.isEmpty() ? holds : asked;
        if (services.isEmpty()) {
            return Outcome.done(UpdateReports.toJson(UpdateReport.at(UpdateReport.Stage.NOTHING_TO_DO)
                    .withNote("No service is being held down, so there was nothing to start.")));
        }

        final UpdateRun run = new UpdateRun(runner.containers, runner.backups.volumes(), progress);
        final RuntimeResult runtime = run.check();
        if (!runtime.reached()) {
            return Outcome.failed(UpdateReports.toJson(
                    UpdateReport.at(UpdateReport.Stage.FAILED).withNote(Runner.unreachableMessage(runtime))));
        }

        final Optional<RunLock> lock;
        try {
            lock = RunLock.tryAcquire(runner.database.dataSource());
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
                planned = planned.with(new UpdateReport.ServiceLine(
                        service,
                        UpdateReport.State.STOPPED,
                        List.of(new UpdateReport.Change("down", "stays down", "starting")),
                        null));
            }
            // Comes off before the start, so a start that never returns leaves no row claiming a hold.
            for (final String service : services) {
                runner.directory.release(service);
            }
            final UpdateReport started = run.start(new UpdateRun.Stopped(planned, services, runtime));
            final UpdateReport verified = run.verify(started, services, runner.waiting);
            final UpdateReport finished =
                    Runner.settle(verified, List.of(), "it was started again", false, Runner.Doubt.IS_ONLY_SAID);
            return finished.stage() == UpdateReport.Stage.FAILED
                    ? Outcome.failed(UpdateReports.toJson(finished))
                    : Outcome.done(UpdateReports.toJson(finished));
        }
    }
}
