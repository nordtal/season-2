package eu.nordtal.season.stewardagent.run;

import eu.nordtal.season.common.season.SeasonMode;
import eu.nordtal.season.database.inbox.StewardRequest;
import eu.nordtal.season.database.update.RunStage;
import eu.nordtal.season.database.update.RunStep;
import eu.nordtal.season.database.update.UpdateKind;
import eu.nordtal.season.database.update.UpdateRequest;
import eu.nordtal.season.database.update.UpdateStatus;
import eu.nordtal.season.internalapi.agent.RuntimeResult;
import eu.nordtal.season.internalapi.agent.Topology;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.function.Consumer;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;

/**
 * Switches the game mode: everyone to the waiting room, the old server down, the mode, the target up.
 *
 * The mode is written before the target starts, so a failed start leaves it on the target for a retry.
 */
@Slf4j
final class ModeSwitch {

    /** How long the players get to reach the waiting room before the switch fails. */
    static final Duration MOVED_WITHIN = Duration.ofMinutes(2);

    /** How long the switch waits for the proxy to let the players out of the waiting room. */
    static final Duration BACK_WITHIN = Duration.ofMinutes(2);

    private static final Duration POLL = Duration.ofSeconds(1);

    /** How far back the switch before this one is looked for. */
    private static final int EARLIER_RUNS = 500;

    private final Runner runner;
    private final UpdateRun steps;
    private final RuntimeResult runtime;
    private final Consumer<RunStage> progress;

    ModeSwitch(
            final Runner runner,
            final UpdateRun steps,
            final RuntimeResult runtime,
            final Consumer<RunStage> progress) {
        this.runner = runner;
        this.steps = steps;
        this.runtime = runtime;
        this.progress = progress;
    }

    /** Carries the switch out and answers with the stage it ended in. A failure keeps the players waiting. */
    Outcome carryOut(final UpdateRequest request) {
        final Steps record = steps.record;
        final StewardRequest asked = runner.directory.requestOf(request.id()).orElse(null);
        if (!(asked instanceof StewardRequest.SwitchMode wanted)) {
            record.ended(RunStep.Kind.SCOPE, null, RunStep.Outcome.FAILED, RunStep.Reason.UNNAMED);
            return Outcome.failed();
        }
        final SeasonMode target = wanted.target();
        final SeasonMode from = runner.season().state().mode();
        // Only a retry switches to the mode already set: it stops nothing and brings the target up.
        final boolean again = from == target;
        if (again && !followsFailedSwitch(request.id())) {
            record.ended(RunStep.Kind.SCOPE, target.server(), RunStep.Outcome.SKIPPED, null);
            return Outcome.at(RunStage.NOTHING_TO_DO);
        }

        // Admins too: the proxy holds everyone in the waiting room from the end of this countdown.
        final List<String> moving = Stream.of(SeasonMode.values())
                .map(SeasonMode::server)
                .filter(server -> ModeServers.isRunning(runtime, server))
                .toList();
        final Steps.Step players = runner.countDown(request.id(), Duration.ZERO, moving, progress, record);
        if (players == null) {
            return Runner.cancelled();
        }
        if (!emptied(moving, players)) {
            return Outcome.failed();
        }

        if (!again && moving.contains(from.server())) {
            final UpdateRun.Stopped stopped = steps.stop(List.of(from.server()), runtime);
            if (!stopped.services().contains(from.server())) {
                // The mode stays, so a retry switches again; two servers never run as the active one.
                return Outcome.failed();
            }
        }
        if (!again) {
            runner.season().setMode(target, request.actor(), wanted.reason());
            log.info("Request {}: the game mode is {}", request.id(), target);
        }
        bringUp(target.server());
        if (record.failed()) {
            return Outcome.failed();
        }
        back(request.id());
        return Outcome.at(record.failed() ? RunStage.FAILED : RunStage.DONE);
    }

    /** Returns whether the switch before this one failed, which a retry follows. */
    private boolean followsFailedSwitch(final long id) {
        return runner.directory.recent(EARLIER_RUNS).stream()
                .filter(run -> run.kind() == UpdateKind.SWITCH_MODE && run.id() < id)
                .findFirst()
                .map(run -> run.status() == UpdateStatus.FAILED)
                .orElse(false);
    }

    /**
     * Waits until no player is counted on the moving servers, failing the switch if one stays past the bound.
     *
     * No count at all only warns: without the proxy's count nobody can be on a server.
     */
    private boolean emptied(final List<String> moving, final Steps.Step players) {
        final Instant started = runner.waiting.now();
        final Instant deadline = started.plus(MOVED_WITHIN);
        final Occupancy occupancy = runner.occupancy();
        while (true) {
            final Instant now = runner.waiting.now();
            final List<String> occupied = new ArrayList<>();
            final List<String> unknown = new ArrayList<>();
            for (final String server : moving) {
                // A row from before the countdown ended predates the moves.
                final OptionalInt count = occupancy.on(server, now, started);
                if (count.isEmpty()) {
                    unknown.add(server);
                } else if (count.getAsInt() > 0) {
                    occupied.add(server);
                }
            }
            if (occupied.isEmpty() && unknown.isEmpty()) {
                players.done();
                return true;
            }
            if (!now.isBefore(deadline)) {
                if (!occupied.isEmpty()) {
                    log.warn("Players stayed on {}, so the mode is not switched", occupied);
                    players.failed(RunStep.Reason.PLAYERS_STAYED);
                    return false;
                }
                players.warned(RunStep.Reason.PLAYERS_UNKNOWN);
                return true;
            }
            if (!runner.waiting.sleep(POLL)) {
                players.failed(RunStep.Reason.INTERRUPTED);
                return false;
            }
        }
    }

    /**
     * Releases a take-down of the target, then starts and verifies it.
     *
     * A target that runs already, started by hand or by a failed switch, is only verified.
     */
    private void bringUp(final String target) {
        if (runner.directory.isHeld(target)) {
            final Steps.Step letting = steps.record.begin(RunStep.Kind.UNHOLD, target);
            runner.directory.release(target);
            letting.done();
        }
        final RuntimeResult now = runner.containers.runtime();
        if (!ModeServers.isRunning(now, target)) {
            steps.start(new UpdateRun.Stopped(List.of(target), now));
            if (steps.record.failed()) {
                return;
            }
        }
        // Its VERIFY step done is the proxy's signal to let the players out.
        steps.verify(List.of(target), runner.waiting);
    }

    /**
     * Waits until the proxy has let every moved player back or the waiting room is empty, for a bounded time.
     *
     * With maintenance on, those who are not admins stay by design, so the bound then ends the step done.
     */
    private void back(final long id) {
        final Steps.Step returning = steps.record.begin(RunStep.Kind.PLAYERS_BACK);
        final Instant started = runner.waiting.now();
        final Instant deadline = started.plus(BACK_WITHIN);
        while (true) {
            final Instant now = runner.waiting.now();
            if (allReturned(id) || waitingRoomEmpty(now, started)) {
                returning.done();
                return;
            }
            if (!now.isBefore(deadline)) {
                if (runner.season().state().maintenance()) {
                    returning.done();
                } else {
                    returning.warned(RunStep.Reason.PLAYERS_STAYED);
                }
                return;
            }
            if (!runner.waiting.sleep(POLL)) {
                returning.warned(RunStep.Reason.INTERRUPTED);
                return;
            }
        }
    }

    private boolean allReturned(final long id) {
        int moved = 0;
        int returned = 0;
        for (final RunStep.Row step : runner.directory.recordOf(id).steps()) {
            for (final RunStep.Move move : step.players()) {
                moved += move.moved();
                returned += move.returned();
            }
        }
        return returned >= moved;
    }

    private boolean waitingRoomEmpty(final Instant now, final Instant since) {
        final OptionalInt waiting = runner.occupancy().on(Topology.LIMBO, now, since);
        return waiting.isPresent() && waiting.getAsInt() == 0;
    }
}
