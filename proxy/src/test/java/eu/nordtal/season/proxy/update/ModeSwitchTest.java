package eu.nordtal.season.proxy.update;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.database.update.RunProgress;
import eu.nordtal.season.database.update.RunStep;
import eu.nordtal.season.database.update.UpdateKind;
import eu.nordtal.season.database.update.UpdateRequest;
import eu.nordtal.season.database.update.UpdateStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** When a mode switch holds every player in the waiting room, and when it lets them go. */
class ModeSwitchTest {

    private static final Instant COUNTDOWN_END = Instant.parse("2026-11-05T12:00:00Z");
    private static final Instant AFTER = COUNTDOWN_END.plus(Duration.ofSeconds(30));
    private static final String TARGET = "hunger-games";

    @Test
    void aRunningSwitchHoldsFromTheEndOfItsCountdown() {
        final UpdateRequest run = run(UpdateStatus.RUNNING);

        assertFalse(ModeSwitch.holds(run, List.of(), TARGET, COUNTDOWN_END.minusSeconds(1)), "the countdown runs");
        assertTrue(ModeSwitch.holds(run, List.of(), TARGET, COUNTDOWN_END));
        assertTrue(ModeSwitch.holds(run, List.of(), TARGET, AFTER));
    }

    @Test
    void aFailedSwitchHoldsNobody() {
        final UpdateRequest run = run(UpdateStatus.FAILED);

        // The waiting room's own rules keep the players while the target is down.
        assertFalse(ModeSwitch.holds(
                run, List.of(new RunProgress.Step(RunStep.Kind.START, TARGET, RunStep.Outcome.FAILED)), TARGET, AFTER));
        assertFalse(ModeSwitch.holds(run, List.of(verify(TARGET, RunStep.Outcome.FAILED)), TARGET, AFTER));
        assertFalse(ModeSwitch.holds(run, List.of(), TARGET, AFTER), "it failed before it planned anything");
        assertFalse(
                ModeSwitch.holds(run, List.of(verify("smp", RunStep.Outcome.DONE)), TARGET, AFTER),
                "the old mode's server is back");
    }

    @Test
    void thePassedCheckOfTheTargetEndsTheHold() {
        final UpdateRequest run = run(UpdateStatus.RUNNING);

        assertFalse(ModeSwitch.holds(run, List.of(verify(TARGET, RunStep.Outcome.DONE)), TARGET, AFTER));
        assertFalse(ModeSwitch.holds(run, List.of(verify(TARGET, RunStep.Outcome.WARNING)), TARGET, AFTER));
        assertFalse(
                ModeSwitch.holds(run, List.of(verify(TARGET, RunStep.Outcome.SKIPPED)), TARGET, AFTER),
                "a target that already ran passes without a fresh check");
    }

    @Test
    void anotherServerOrAnUnfinishedCheckKeepsTheHold() {
        final UpdateRequest run = run(UpdateStatus.RUNNING);

        assertTrue(ModeSwitch.holds(run, List.of(verify("smp", RunStep.Outcome.DONE)), TARGET, AFTER));
        assertTrue(ModeSwitch.holds(run, List.of(verify(TARGET, null)), TARGET, AFTER));
        assertTrue(ModeSwitch.holds(run, List.of(verify(TARGET, RunStep.Outcome.FAILED)), TARGET, AFTER));
        assertTrue(ModeSwitch.holds(
                run,
                List.of(new RunProgress.Step(RunStep.Kind.PLAYERS_MOVED, TARGET, RunStep.Outcome.DONE)),
                TARGET,
                AFTER));
    }

    @Test
    void aSwitchThatIsDonePendingOrCancelledHoldsNobody() {
        assertFalse(ModeSwitch.holds(run(UpdateStatus.DONE), List.of(), TARGET, AFTER));
        assertFalse(ModeSwitch.holds(run(UpdateStatus.PENDING), List.of(), TARGET, AFTER));
        assertFalse(ModeSwitch.holds(run(UpdateStatus.CANCELLED), List.of(), TARGET, AFTER));
        assertFalse(ModeSwitch.holds(null, List.of(), TARGET, AFTER), "no switch at all");
    }

    private static RunProgress.Step verify(final String service, final RunStep.@Nullable Outcome outcome) {
        return new RunProgress.Step(RunStep.Kind.VERIFY, service, outcome);
    }

    private static UpdateRequest run(final UpdateStatus status) {
        return new UpdateRequest(
                9L,
                UpdateKind.SWITCH_MODE,
                status,
                Actor.HOST,
                COUNTDOWN_END.minus(Duration.ofMinutes(5)),
                COUNTDOWN_END.minus(Duration.ofMinutes(5)),
                COUNTDOWN_END,
                List.of("smp", TARGET),
                COUNTDOWN_END.minus(Duration.ofMinutes(5)),
                null,
                null);
    }
}
