package eu.nordtal.s2.steward.serve;

import eu.nordtal.s2.database.update.UpdateReport;
import eu.nordtal.s2.database.update.UpdateReports;
import eu.nordtal.s2.database.update.UpdateRequest;
import eu.nordtal.s2.steward.ops.RuntimeResult;
import eu.nordtal.s2.steward.plan.Topology;
import java.util.List;
import java.util.function.Consumer;

/** The choreography a restart runs once it holds the lock: stop the network, then start it, with no plan at all. */
final class RestartSequence {

    private RestartSequence() {}

    /** NOTHING_TO_DO, worded for "every service is held" or "the scope names no Minecraft service". */
    static Outcome nothingToRestart(final List<String> scope) {
        return Outcome.done(UpdateReports.toJson(UpdateReport.at(UpdateReport.Stage.NOTHING_TO_DO)
                .withNote(
                        scope.isEmpty()
                                ? "Every Minecraft service is being held down, so there was nothing to"
                                        + " restart. Nothing was stopped."
                                : "Nothing in " + String.join(", ", scope) + " is a Minecraft service"
                                        + " this run may restart - either it is not one, or it is being"
                                        + " held down. Nothing was stopped.")));
    }

    /** Every service a restart is for, PLANNED with no change and narrowed by holds and scope, or empty. */
    static UpdateReport prepareReport(final List<String> scope, final List<String> holds) {
        UpdateReport planned = UpdateReport.at(UpdateReport.Stage.STOPPING);
        for (final String service : Runner.restarted(scope, holds)) {
            planned = planned.with(new UpdateReport.ServiceLine(
                    service,
                    UpdateReport.State.PLANNED,
                    List.of(new UpdateReport.Change("restart", null, "no change")),
                    null));
        }
        return planned;
    }

    /** Stops every service the restart is for and starts it again, on the same choreography as an update. */
    static Outcome runUnderLock(
            final Runner runner,
            final UpdateRequest request,
            final UpdateRun run,
            final RuntimeResult runtime,
            final Consumer<UpdateReport> progress) {
        // A restart has no plan: every named service is work with no changes, narrowed by holds and by scope.
        final List<String> holds = runner.held();
        final List<String> scope = runner.directory.scopeOf(request.id());
        UpdateReport planned = prepareReport(scope, holds);
        if (planned.services().isEmpty()) {
            return nothingToRestart(scope);
        }
        final List<String> untouched = Topology.SERVICES.stream()
                .map(Topology.Service::name)
                .filter(holds::contains)
                .filter(service -> scope.isEmpty() || scope.contains(service))
                .toList();

        // The same choreography as an update; a restart stops the most, so it needs both standbys.
        final Choreography choreography = new Choreography(runner.containers, runner.occupancy(), runner.waiting);
        final Choreography.Window window = choreography.open(Runner.movingServices(planned));
        if (!window.opened()) {
            return Outcome.failed(UpdateReports.toJson(planned.withStage(UpdateReport.Stage.FAILED)
                    .withNote("NOTHING WAS RESTARTED. " + window.refusal()
                            + ". Every service is still running exactly as it was.")));
        }
        if (!window.isEmpty()) {
            planned = planned.withNote(String.join(", ", window.standbys())
                    + " started and healthy, so this restart has somewhere to put the players.");
            progress.accept(planned);
        }
        try {
            // Unlike an update, a restart always has work, so the countdown here is unconditional.
            if (!runner.countDown(request.id(), planned, progress)) {
                return Runner.cancelled();
            }

            final String stillOn = choreography.waitUntilEmpty(Runner.movingServices(planned));
            if (stillOn != null) {
                planned = planned.withNote(stillOn);
                progress.accept(planned);
            }

            final UpdateRun.Stopped stopped = run.stop(planned, runtime);
            final UpdateReport started = run.start(stopped);
            final UpdateReport verified = run.verify(started, stopped.services(), runner.waiting);

            final UpdateReport told = untouched.isEmpty()
                    ? verified
                    : verified.withNote(String.join(", ", untouched) + " is being held down and was not"
                            + " restarted. It stays down until somebody starts it.");
            final UpdateReport finished = Runner.settle(
                    Runner.noteStandbys(told, choreography.close()),
                    run.unverifiedStops(),
                    "it was started again on the same world",
                    false,
                    Runner.Doubt.IS_ONLY_SAID);
            return finished.stage() == UpdateReport.Stage.FAILED
                    ? Outcome.failed(UpdateReports.toJson(finished))
                    : Outcome.done(UpdateReports.toJson(finished));
        } finally {
            choreography.close();
        }
    }
}
