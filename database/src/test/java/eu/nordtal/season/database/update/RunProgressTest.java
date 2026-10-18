package eu.nordtal.season.database.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.database.update.RunProgress.Step;
import eu.nordtal.season.database.update.RunStep.Kind;
import eu.nordtal.season.database.update.RunStep.Outcome;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The percentage a recreate of smp shows in the waiting room, step by step. */
class RunProgressTest {

    private static final Set<String> SMP = Set.of("smp");

    /** The steps of a recreate of smp, in the order the run writes them. */
    private static final List<Step> RECREATE = List.of(
            new Step(Kind.COUNTDOWN, null, Outcome.DONE),
            new Step(Kind.PLAYERS_MOVED, null, Outcome.DONE),
            new Step(Kind.STOP, "smp", Outcome.DONE),
            new Step(Kind.INSTALL, "smp", Outcome.DONE),
            new Step(Kind.VERIFY, "smp", Outcome.DONE),
            new Step(Kind.PLAYERS_BACK, null, Outcome.DONE));

    @Test
    void aRunWithEveryStepDoneStaysBelowAHundredUntilItEnds() {
        assertEquals(OptionalInt.of(99), RunProgress.percent(RECREATE, SMP), "a running run never shows 100");
    }

    @Test
    void finishingAStepNeverLowersThePercentage() {
        for (int written = 1; written <= RECREATE.size(); written++) {
            final List<Step> running = new ArrayList<>(RECREATE.subList(0, written));
            running.set(written - 1, running(running.get(written - 1)));
            final int before = RunProgress.percent(running, SMP).orElseThrow();
            final int after =
                    RunProgress.percent(RECREATE.subList(0, written), SMP).orElseThrow();

            assertTrue(
                    after > before, "finishing " + RECREATE.get(written - 1) + " went from " + before + " to " + after);
        }
    }

    @Test
    void aSkippedStepDropsOutOfTheTotal() {
        final List<Step> skipped = List.of(
                new Step(Kind.COUNTDOWN, null, Outcome.DONE),
                new Step(Kind.PLAYERS_MOVED, null, Outcome.DONE),
                new Step(Kind.SAVE_VOLUME, "smp", Outcome.SKIPPED),
                new Step(Kind.STOP, "smp", Outcome.DONE));
        final List<Step> without = List.of(skipped.get(0), skipped.get(1), skipped.get(3));

        assertEquals(RunProgress.percent(without, SMP), RunProgress.percent(skipped, SMP));
    }

    @Test
    void theStepsAheadOfEveryStoppedServiceCount() {
        final List<Step> countdown = List.of(new Step(Kind.COUNTDOWN, null, Outcome.DONE));

        assertTrue(
                RunProgress.percent(countdown, Set.of("smp", "hunger-games")).orElseThrow()
                        < RunProgress.percent(countdown, SMP).orElseThrow(),
                "a second service has its own stop and health check ahead");
    }

    @Test
    void withoutAStepNothingIsKnown() {
        assertEquals(OptionalInt.empty(), RunProgress.percent(List.of(), Set.of()));
    }

    private static Step running(final Step step) {
        return new Step(step.kind(), step.service(), null);
    }
}
