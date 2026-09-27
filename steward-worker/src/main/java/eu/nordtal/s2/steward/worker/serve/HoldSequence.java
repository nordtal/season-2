package eu.nordtal.s2.steward.worker.serve;

import eu.nordtal.s2.common.update.UpdateReport;
import eu.nordtal.s2.common.update.UpdateReports;
import eu.nordtal.s2.common.update.UpdateRequest;
import eu.nordtal.s2.steward.worker.ops.RuntimeResult;
import eu.nordtal.s2.steward.worker.plan.Topology;
import eu.nordtal.s2.steward.worker.schema.RunLock;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/** The two halves of holding a service down and letting it go, on request rather than as part of any other run. */
final class HoldSequence {

    /**
     * The two services a run may never put down, because the run is standing on them.
     *
     * {@code steward-worker} is the process performing the sequence and {@code postgres} holds the row it writes its
     * report into. A DOWN naming either would be a request that cannot report what it did - and in the worker's case
     * could not even release its own lock. Refused by name, before anything is stopped, rather than discovered
     * halfway through.
     */
    private static final List<String> NEVER_DOWN = List.of(Topology.STEWARD_WORKER, "postgres");

    private HoldSequence() {}

    /**
     * Stop the named services and leave them stopped.
     *
     * The whole ordinary procedure, and then one step less: Countdown, park the players, stop, report - the same
     * sequence a restart runs, with the starting half removed and a row in {@code service_hold} in its place. The
     * row is what makes this survive a restart of this process, and what every later run reads so that nothing
     * brings back a service somebody stopped in order to work on it.
     *
     * A DOWN has to name its services: An empty scope means "the whole network" everywhere else in this mechanism,
     * and here that would be a button that stops everything with no way back except another button. It is refused
     * rather than interpreted: the interface never offers it, and a row written by hand that forgets the scope is
     * far more likely to be a mistake than a request to take the network down indefinitely.
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

        // Only when somebody could be standing on one of them - the countdown is the players' warning, not ceremony.
        if (scope.stream().anyMatch(Runner::isMinecraft) && !runner.countDown(request.id(), planned, progress)) {
            return Runner.cancelled();
        }

        final UpdateRun.Stopped stopped = run.stop(planned, runtime);
        for (final String service : stopped.services()) {
            runner.directory.hold(service, request.requestedBy(), request.id());
        }

        UpdateReport report = stopped.report();
        if (!stopped.services().isEmpty()) {
            // Worded so that one service and four read the same, since a report is read far more often than written.
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
     * The other half: take the hold off and start the services again.
     *
     * No countdown. Nothing goes down, so there is nothing to warn anybody about, and thirty seconds of "the network
     * is about to be interrupted" before a server comes back would be a warning about good news.
     *
     * An empty scope here is every held service, and that asymmetry with {@link #down} is deliberate: the dangerous
     * direction is the one that stops things. Starting everything that somebody stopped is the recovery an operator
     * wants after a restart of this process, and it can do no harm that was not already asked for.
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
            // Comes off BEFORE the start: a start that never returns must not leave the row claiming a hold.
            for (final String service : services) {
                runner.directory.release(service);
            }
            final UpdateReport started = run.start(new UpdateRun.Stopped(planned, services, runtime));
            final UpdateReport verified = run.verify(started, services, UpdateRun.Waiting.real());
            final UpdateReport finished =
                    Runner.settle(verified, List.of(), "it was started again", false, Runner.Doubt.IS_ONLY_SAID);
            return finished.stage() == UpdateReport.Stage.FAILED
                    ? Outcome.failed(UpdateReports.toJson(finished))
                    : Outcome.done(UpdateReports.toJson(finished));
        }
    }
}
