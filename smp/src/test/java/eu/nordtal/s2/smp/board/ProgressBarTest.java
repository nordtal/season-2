package eu.nordtal.s2.smp.board;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The objective board's bar, where the rounding is the whole of it.
 *
 * <p>Two lies a bar can tell, and both of them at the only moments anybody is watching: showing
 * "full" before it is, and showing "empty" after somebody has already contributed.
 */
class ProgressBarTest {

    @Test
    void anEmptyBarIsEmptyAndAFullBarIsFull() {
        assertEquals("░░░░░░░░░░", ProgressBar.of(0.0, 10));
        assertEquals("██████████", ProgressBar.of(1.0, 10));
    }

    @Test
    void aBarNeverLooksFullBeforeItIs() {
        assertEquals("█████████░", ProgressBar.of(0.999, 10),
                "99.9 % must leave a visible gap - the last block is the whole point of the bar");
        assertEquals("█████████░", ProgressBar.of(0.95, 10));
        assertEquals(99, ProgressBar.percent(0.999));
        assertEquals(100, ProgressBar.percent(1.0));
    }

    @Test
    void anythingStartedShowsSomething() {
        assertEquals("█░░░░░░░░░", ProgressBar.of(0.001, 10),
                "one delivered item out of three thousand must not look like nothing");
        assertEquals(0, ProgressBar.percent(0.001),
                "though the number beside it may honestly still say zero");
    }

    /** A target lowered below the collected amount is an escape hatch the concept keeps. */
    @Test
    void moreThanFullIsFullAndNotWider() {
        assertEquals("██████████", ProgressBar.of(1.7, 10));
        assertEquals(10, ProgressBar.of(1.7, 10).length());
        assertEquals(100, ProgressBar.percent(1.7));
    }

    @Test
    void negativeIsEmptyRatherThanAnError() {
        assertEquals("░░░░░░░░░░", ProgressBar.of(-0.5, 10));
        assertEquals(0, ProgressBar.percent(-0.5));
    }

    @Test
    void theBarIsAlwaysExactlyAsWideAsItWasAsked() {
        for (double ratio = 0.0; ratio <= 1.0; ratio += 0.05) {
            assertEquals(20, ProgressBar.of(ratio, 20).length(),
                    "a bar that changes width as it fills makes the whole board jump");
        }
        assertTrue(ProgressBar.of(0.5, 0).isEmpty());
    }
}
