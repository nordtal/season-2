package eu.nordtal.season.stewardagent.run;

import eu.nordtal.season.common.season.SeasonMode;
import eu.nordtal.season.database.inbox.StewardRequest;
import eu.nordtal.season.database.update.RunStage;
import eu.nordtal.season.database.update.RunStep;
import eu.nordtal.season.database.update.UpdateKind;
import eu.nordtal.season.database.update.UpdateRequest;
import eu.nordtal.season.database.update.UpdateStatus;
import eu.nordtal.season.internalapi.agent.RuntimeResult;
import java.time.Duration;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * Plans the switch of the game mode: everyone to the waiting room, the old server down, the mode, the target up.
 *
 * The mode is written before the target starts, so a failed start leaves it on the target for a retry.
 */
@Slf4j
final class ModeSwitch {

    /** How far back the switch before this one is looked for. */
    private static final int EARLIER_RUNS = 500;

    private ModeSwitch() {}

    /**
     * Plans the switch the request names, as a run whose payload between the stop and the start writes the mode.
     *
     * A switch to the mode already set is a retry, valid only after a failed switch: it stops nothing.
     */
    static Kinds.Planned plan(final Runner runner, final UpdateRequest request, final Steps record) {
        final StewardRequest asked = runner.directory.requestOf(request.id()).orElse(null);
        if (!(asked instanceof StewardRequest.SwitchMode wanted)) {
            record.ended(RunStep.Kind.SCOPE, null, RunStep.Outcome.FAILED, RunStep.Reason.UNNAMED);
            return Kinds.Planned.outcome(Outcome.failed());
        }
        final SeasonMode target = wanted.target();
        final SeasonMode from = runner.season().state().mode();
        final boolean again = from == target;
        if (again && !followsFailedSwitch(runner, request.id())) {
            record.ended(RunStep.Kind.SCOPE, target.server(), RunStep.Outcome.SKIPPED, null);
            return Kinds.Planned.outcome(Outcome.at(RunStage.NOTHING_TO_DO));
        }

        final RuntimeResult runtime = runner.containers.runtime();
        final List<String> stopping =
                !again && !ModeServers.isStopped(runtime, from.server()) ? List.of(from.server()) : List.of();
        // A target started by hand or left by a failed switch runs already and is only verified.
        final boolean targetRuns = ModeServers.isRunning(runtime, target.server());
        final Run.Payload turn = (steps, stopped) -> {
            if (runner.directory.isHeld(target.server())) {
                final Steps.Step letting = record.begin(RunStep.Kind.UNHOLD, target.server());
                runner.directory.release(target.server());
                letting.done();
            }
            if (!again) {
                write(runner, request, wanted);
            }
            if (targetRuns) {
                // Its VERIFY step done is the proxy's signal to let the players out.
                steps.verify(List.of(target.server()), runner.waiting);
            }
            // The old server rests, so only the target starts.
            return new Run.Done(false, () -> {}, stopping);
        };
        // Admins too: the proxy holds everyone in the waiting room from the end of this countdown.
        final Run.Plan planned = Run.Plan.of(stopping, turn, true, Runner.Doubt.IS_ONLY_SAID)
                .countingDown(Duration.ZERO)
                .alsoStarting(targetRuns ? List.of() : List.of(target.server()));
        // A resting target's container keeps its old image; it is remade from the image here, so a local build stays.
        final boolean renews = !targetRuns && runner.containers.isOutdated(target.server());
        return Kinds.Planned.plan(renews ? planned.remaking(List.of(target.server()), List.of(), false) : planned);
    }

    /** Writes the mode, or aborts the run so the old server starts again. */
    private static void write(
            final Runner runner, final UpdateRequest request, final StewardRequest.SwitchMode wanted) {
        try {
            runner.season().setMode(wanted.target(), request.actor(), wanted.reason());
        } catch (final RuntimeException failure) {
            log.error("game mode not written: request={}", request.id(), failure);
            throw new Run.Abort();
        }
        log.info("game mode written: request={} mode={}", request.id(), wanted.target());
    }

    /** Returns whether the switch before this one failed, which a retry follows. */
    private static boolean followsFailedSwitch(final Runner runner, final long id) {
        return runner.directory.recent(EARLIER_RUNS).stream()
                .filter(run -> run.kind() == UpdateKind.SWITCH_MODE && run.id() < id)
                .findFirst()
                .map(run -> run.status() == UpdateStatus.FAILED)
                .orElse(false);
    }
}
