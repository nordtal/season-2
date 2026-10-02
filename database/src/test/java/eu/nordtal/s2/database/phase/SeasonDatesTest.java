package eu.nordtal.s2.database.phase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/** Tests the date format both {@code /phase} commands share, whose offset is derived from the date. */
class SeasonDatesTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    @Test
    void aSummerDateIsTwoHoursAheadOfUtc() {
        // Before the last Sunday of October, so Berlin is still on CEST.
        assertEquals(
                Instant.parse("2026-10-01T16:00:00Z"),
                SeasonDates.parse("2026-10-01 18:00", BERLIN).orElseThrow());
    }

    @Test
    void aWinterDateIsOneHourAheadOfUtc() {
        // Same wall-clock time five weeks later, one hour further from UTC.
        assertEquals(
                Instant.parse("2026-11-15T17:00:00Z"),
                SeasonDates.parse("2026-11-15 18:00", BERLIN).orElseThrow());
    }

    @Test
    void theIsoStyleSeparatorIsAccepted() {
        assertEquals(SeasonDates.parse("2026-10-01 18:00", BERLIN), SeasonDates.parse("2026-10-01T18:00", BERLIN));
    }

    @Test
    void surroundingWhitespaceIsIgnored() {
        assertEquals(SeasonDates.parse("2026-10-01 18:00", BERLIN), SeasonDates.parse("  2026-10-01 18:00  ", BERLIN));
    }

    @Test
    void anythingThatIsNotThePatternIsRefused() {
        // Every one of these is a plausible thing to type, and none of them may be guessed at.
        assertTrue(SeasonDates.parse(null, BERLIN).isEmpty());
        assertTrue(SeasonDates.parse("", BERLIN).isEmpty());
        assertTrue(SeasonDates.parse("   ", BERLIN).isEmpty());
        assertTrue(SeasonDates.parse("tomorrow", BERLIN).isEmpty());
        assertTrue(SeasonDates.parse("01.10.2026 18:00", BERLIN).isEmpty(), "German order is not the pattern");
        assertTrue(SeasonDates.parse("2026-10-01", BERLIN).isEmpty(), "a date needs a time");
        assertTrue(SeasonDates.parse("2026-10-01 18:00:00", BERLIN).isEmpty(), "seconds are not in the pattern");
        assertTrue(SeasonDates.parse("2026-13-01 18:00", BERLIN).isEmpty(), "there is no thirteenth month");
        assertTrue(SeasonDates.parse("2026-02-30 18:00", BERLIN).isEmpty(), "February has no thirtieth");
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
        assertEquals("not set", SeasonDates.format(null, BERLIN));
    }

    @Test
    void aDateIsShownBackInTheZoneItWasTypedIn() {
        final String shown =
                SeasonDates.format(SeasonDates.parse("2026-10-01 18:00", BERLIN).orElseThrow(), BERLIN);

        assertTrue(
                shown.startsWith("2026-10-01 18:00"),
                "the wall-clock time that was typed has to come back, was: " + shown);
        // The offset rather than a zone abbreviation, which would need locale knowledge.
        assertTrue(shown.endsWith("GMT+02:00"), "the offset has to be named, was: " + shown);
    }

    @Test
    void aDateIsTypedAndShownInTheZoneTheNetworkNames() {
        final ZoneId helsinki = ZoneId.of("Europe/Helsinki");
        final Instant typed = SeasonDates.parse("2026-10-01 18:00", helsinki).orElseThrow();

        assertEquals(Instant.parse("2026-10-01T15:00:00Z"), typed);
        assertTrue(SeasonDates.format(typed, BERLIN).startsWith("2026-10-01 17:00"));
    }
}
