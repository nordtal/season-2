package eu.nordtal.s2.common.phase;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The date format both {@code /phase} commands share.
 * <p>
 * The cases that matter here are the ones a human gets wrong by hand: the offset is not typed, so
 * it has to be derived from the date, and it is not the same offset all year. Everything else is a
 * guard against a typo being read as a date.
 * </p>
 */
class SeasonDatesTest {

    @Test
    void aSummerDateIsTwoHoursAheadOfUtc() {
        // 2026-10-01 is before the last Sunday of October, so Berlin is still on CEST.
        assertEquals(Instant.parse("2026-10-01T16:00:00Z"),
                SeasonDates.parse("2026-10-01 18:00").orElseThrow());
    }

    @Test
    void aWinterDateIsOneHourAheadOfUtc() {
        // Same wall-clock time, five weeks later, one hour further from UTC. This is the whole
        // reason the offset is not typed by hand.
        assertEquals(Instant.parse("2026-11-15T17:00:00Z"),
                SeasonDates.parse("2026-11-15 18:00").orElseThrow());
    }

    @Test
    void theIsoStyleSeparatorIsAccepted() {
        assertEquals(SeasonDates.parse("2026-10-01 18:00"), SeasonDates.parse("2026-10-01T18:00"));
    }

    @Test
    void surroundingWhitespaceIsIgnored() {
        assertEquals(SeasonDates.parse("2026-10-01 18:00"),
                SeasonDates.parse("  2026-10-01 18:00  "));
    }

    @Test
    void anythingThatIsNotThePatternIsRefused() {
        // Every one of these is a plausible thing to type, and none of them may be guessed at.
        assertTrue(SeasonDates.parse(null).isEmpty());
        assertTrue(SeasonDates.parse("").isEmpty());
        assertTrue(SeasonDates.parse("   ").isEmpty());
        assertTrue(SeasonDates.parse("tomorrow").isEmpty());
        assertTrue(SeasonDates.parse("01.10.2026 18:00").isEmpty(), "German order is not the pattern");
        assertTrue(SeasonDates.parse("2026-10-01").isEmpty(), "a date needs a time");
        assertTrue(SeasonDates.parse("2026-10-01 18:00:00").isEmpty(), "seconds are not in the pattern");
        assertTrue(SeasonDates.parse("2026-13-01 18:00").isEmpty(), "there is no thirteenth month");
        assertTrue(SeasonDates.parse("2026-02-30 18:00").isEmpty(), "February has no thirtieth");
    }

    @Test
    void clearIsRecognisedHoweverItIsTyped() {
        assertTrue(SeasonDates.isClear("clear"));
        assertTrue(SeasonDates.isClear("CLEAR"));
        assertTrue(SeasonDates.isClear("  Clear "));
        assertFalse(SeasonDates.isClear(null));
        assertFalse(SeasonDates.isClear("cleared"));
        assertFalse(SeasonDates.isClear("2026-10-01 18:00"));
    }

    @Test
    void noDateIsShownAsWordsRatherThanAsNothing() {
        assertEquals("not set", SeasonDates.format(null));
    }

    @Test
    void aDateIsShownBackInTheZoneItWasTypedIn() {
        final String shown = SeasonDates.format(SeasonDates.parse("2026-10-01 18:00").orElseThrow());

        assertTrue(shown.startsWith("2026-10-01 18:00"),
                "the wall-clock time that was typed has to come back, was: " + shown);
        // The offset itself rather than an abbreviation: "GMT+02:00" needs no knowledge of
        // which three letters Germany uses in October.
        assertTrue(shown.endsWith("GMT+02:00"), "the offset has to be named, was: " + shown);
    }
}
