package eu.nordtal.season.papercommon.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The typing schedule: the speed, the pauses after marks and the typing sound. */
class TypingTest {

    @Test
    void aTextTypesAtTheSpeedItIsGiven() {
        final Typing typing = Typing.of(List.of("abc"), 30);

        assertEquals(0, typing.shown(32));
        assertEquals(1, typing.shown(33));
        assertEquals(2, typing.shown(67));
        assertEquals(3, typing.shown(100));
        assertEquals(100, typing.typedAt());
    }

    @Test
    void aFullStopPausesBeforeTheNextCharacter() {
        final Typing typing = Typing.of(List.of("a.b"), 10);

        assertEquals(2, typing.shown(579));
        assertEquals(3, typing.shown(580));
    }

    @Test
    void eachMarkPausesAsLongAsItsKind() {
        assertEquals(300 + 280, Typing.of(List.of("a!b"), 10).typedAt());
        assertEquals(300 + 280, Typing.of(List.of("a?b"), 10).typedAt());
        assertEquals(300 + 280, Typing.of(List.of("a…b"), 10).typedAt());
        assertEquals(300 + 200, Typing.of(List.of("a:b"), 10).typedAt());
        assertEquals(300 + 200, Typing.of(List.of("a;b"), 10).typedAt());
        assertEquals(300 + 120, Typing.of(List.of("a,b"), 10).typedAt());
        assertEquals(300, Typing.of(List.of("a-b"), 10).typedAt());
    }

    @Test
    void aRunOfMarksPausesOnceByItsLongestPause() {
        assertEquals(400 + 280, Typing.of(List.of("a?!b"), 10).typedAt());
        assertEquals(400 + 280, Typing.of(List.of("a,.b"), 10).typedAt());
    }

    @Test
    void nothingPausesAfterTheLastCharacter() {
        assertEquals(300, Typing.of(List.of("ab."), 10).typedAt());
        assertEquals(400, Typing.of(List.of("ab?!"), 10).typedAt());
    }

    @Test
    void aMarkAtALinesEndPausesBeforeTheNextLine() {
        assertEquals(300 + 280, Typing.of(List.of("a.", "b"), 10).typedAt());
    }

    @Test
    void everySecondLetterSoundsAndNeverASpaceOrAMark() {
        final Typing typing = Typing.of(List.of("ab c."), 30);

        assertFalse(typing.soundsBetween(0, 1), "the first letter is quiet");
        assertTrue(typing.soundsBetween(1, 2), "the second letter sounds");
        assertFalse(typing.soundsBetween(2, 3), "a space is quiet");
        assertFalse(typing.soundsBetween(3, 5), "the third letter and a mark are quiet");
    }

    @Test
    void aTextOfOneLetterTypesWithoutASound() {
        assertFalse(Typing.of(List.of("a"), 30).soundsBetween(0, 1));
    }

    @Test
    void theShownCharactersFillTheLinesInOrder() {
        assertEquals(List.of("ab", "c"), Typing.shownLines(List.of("ab", "cd"), 3));
        assertEquals(List.of("a", ""), Typing.shownLines(List.of("ab", "cd"), 1));
        assertEquals(List.of("ab", "cd"), Typing.shownLines(List.of("ab", "cd"), 9));
    }
}
