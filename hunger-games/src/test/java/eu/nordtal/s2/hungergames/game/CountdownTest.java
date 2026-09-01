package eu.nordtal.s2.hungergames.game;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CountdownTest {

    @Test
    void theConfiguredDefaultAnnouncesTheFullDurationFirstAndThenTheMarksBelowIt() {
        assertEquals(List.of(60, 30, 20, 10, 5, 4, 3, 2, 1), Countdown.marks(60));
    }

    @Test
    void aDurationThatIsItselfAMarkDoesNotAnnounceThatMarkTwice() {
        final List<Integer> marks = Countdown.marks(30);
        assertEquals(List.of(30, 20, 10, 5, 4, 3, 2, 1), marks);
        assertEquals(marks.size(), marks.stream().distinct().count());
    }

    @Test
    void marksAboveTheDurationAreDropped() {
        assertEquals(List.of(7, 5, 4, 3, 2, 1), Countdown.marks(7));
    }

    @Test
    void aLongerCountdownStillOnlySpeaksNineTimes() {
        assertEquals(List.of(300, 60, 30, 20, 10, 5, 4, 3, 2, 1), Countdown.marks(300));
    }

    @Test
    void aCountdownOfOneSecondSaysItOnce() {
        assertEquals(List.of(1), Countdown.marks(1));
    }

    @Test
    void aCountdownOfZeroOrLessSaysNothing() {
        assertTrue(Countdown.marks(0).isEmpty());
        assertTrue(Countdown.marks(-5).isEmpty());
    }

    @Test
    void everyMarkIsStrictlyDescendingAndPositive() {
        final List<Integer> marks = Countdown.marks(60);
        for (int index = 1; index < marks.size(); index++) {
            assertTrue(marks.get(index) < marks.get(index - 1),
                    "mark " + index + " is not below its predecessor");
        }
        assertTrue(marks.stream().allMatch(mark -> mark > 0));
    }
}
