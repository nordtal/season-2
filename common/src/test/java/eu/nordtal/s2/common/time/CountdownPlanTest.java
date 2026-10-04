package eu.nordtal.s2.common.time;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class CountdownPlanTest {

    /** A game's start: sparse far out, dense at the end, the whole time first. */
    private static final CountdownPlan GAME =
            CountdownPlan.at(60, 30, 20, 10, 5, 4, 3, 2, 1).fromTheStart();

    /** A restart: three chat lines, then every second. */
    private static final CountdownPlan RESTART = CountdownPlan.at(60, 30, 10).everySecondFrom(10);

    private static List<Long> spoken(final CountdownPlan plan, final Duration left) {
        return plan.beats(left, seconds -> seconds, null).stream()
                .map(CountdownPlan.Beat::seconds)
                .toList();
    }

    private static List<Long> spoken(final CountdownPlan plan, final long seconds) {
        return spoken(plan, Duration.ofSeconds(seconds));
    }

    @Test
    void theWholeTimeIsSpokenFirstAndThenTheMarksBelowIt() {
        assertEquals(List.of(60L, 30L, 20L, 10L, 5L, 4L, 3L, 2L, 1L), spoken(GAME, 60));
        assertEquals(List.of(300L, 60L, 30L, 20L, 10L, 5L, 4L, 3L, 2L, 1L), spoken(GAME, 300));
    }

    @Test
    void aTimeThatIsItselfAMarkIsNotSpokenTwice() {
        assertEquals(List.of(30L, 20L, 10L, 5L, 4L, 3L, 2L, 1L), spoken(GAME, 30));
    }

    @Test
    void marksAboveTheTimeLeftAreDropped() {
        assertEquals(List.of(7L, 5L, 4L, 3L, 2L, 1L), spoken(GAME, 7));
        assertEquals(List.of(10L, 9L, 8L, 7L, 6L, 5L, 4L, 3L, 2L, 1L), spoken(RESTART, 10));
    }

    @Test
    void nothingLeftIsOnlyTheEnd() {
        assertEquals(List.of(), spoken(GAME, 0));
        assertEquals(List.of(), spoken(GAME, -5));
        final List<CountdownPlan.Beat<String>> beats = GAME.beats(Duration.ZERO, seconds -> "n", "go");
        assertEquals(List.of(new CountdownPlan.Beat<>(Duration.ZERO, 0L, "go")), beats);
    }

    @Test
    void eachBeatIsDueWhenItsNumberBecomesTrue() {
        final List<CountdownPlan.Beat<String>> beats =
                CountdownPlan.at().everySecondFrom(3).beats(Duration.ofSeconds(3), seconds -> "n" + seconds, "go");
        assertEquals(
                List.of(
                        new CountdownPlan.Beat<>(Duration.ZERO, 3L, "n3"),
                        new CountdownPlan.Beat<>(Duration.ofSeconds(1), 2L, "n2"),
                        new CountdownPlan.Beat<>(Duration.ofSeconds(2), 1L, "n1"),
                        new CountdownPlan.Beat<>(Duration.ofSeconds(3), 0L, "go")),
                beats);
    }

    @Test
    void theSecondsShownNotTheMillisecondsDecideWhatIsStillAhead() {
        // 59.98s reads as 60 on a counter, so sixty is still worth saying, at once rather than never.
        final List<CountdownPlan.Beat<Long>> beats =
                RESTART.beats(Duration.ofSeconds(60).minusMillis(20), seconds -> seconds, null);
        assertEquals(60L, beats.getFirst().seconds());
        assertEquals(Duration.ZERO, beats.getFirst().delay());
        assertTrue(beats.stream().allMatch(beat -> !beat.delay().isNegative()));
    }

    @Test
    void aCountdownPlannedLateGetsOnlyTheBeatsAhead() {
        assertEquals(List.of(10L, 9L, 8L, 7L, 6L, 5L, 4L, 3L, 2L, 1L), spoken(RESTART, 25));
    }

    @Test
    void theMarksAreDescendingDistinctAndPositive() {
        assertEquals(
                List.of(10L, 9L, 8L, 7L, 6L, 5L, 4L, 3L, 2L, 1L),
                CountdownPlan.at(10, 0, -1, 5).everySecondFrom(10).marks());
    }
}
