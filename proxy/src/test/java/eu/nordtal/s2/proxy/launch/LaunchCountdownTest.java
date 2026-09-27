package eu.nordtal.s2.proxy.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.message.Messages;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * The countdown the three {@code PRE_LAUNCH} screens and the server browser share.
 *
 * No announced date and a passed date are normal states and must not render as faults.
 */
class LaunchCountdownTest {

    private static final Messages MESSAGES = Messages.load("messages/proxy", Locale.ENGLISH, Locale.GERMAN);

    private static final Instant NOW = Instant.parse("2026-09-03T12:00:00Z");

    @Test
    void daysAndHoursWhileTheOpeningIsStillDaysAway() {
        final String line = render(Duration.ofDays(3).plusHours(4).plusMinutes(30));

        assertTrue(line.contains("3"), line);
        assertTrue(line.contains("4"), line);
        assertFalse(line.contains("30"), "minutes are noise next to three days: " + line);
    }

    @Test
    void hoursAndMinutesInsideTheLastDay() {
        final String line = render(Duration.ofHours(5).plusMinutes(12));

        assertTrue(line.contains("5"), line);
        assertTrue(line.contains("12"), line);
    }

    @Test
    void minutesOnlyInTheLastHour() {
        assertEquals("42 minutes", render(Duration.ofMinutes(42)));
    }

    @Test
    void theLastMinuteReadsAsImminentRatherThanAsZero() {
        // Below a minute there is nothing to count, and "0 minutes" reads as a fault.
        assertEquals("any moment now", render(Duration.ofSeconds(30)));
        assertEquals("any moment now", render(Duration.ZERO));
    }

    @Test
    void aPassedInstantNeverRendersNegative() {
        // The normal state between the announced instant and an admin's switch.
        final String line = render(Duration.ofHours(-3));

        assertEquals("any moment now", line);
        assertFalse(line.contains("-"), line);
    }

    @Test
    void noAnnouncedDateSaysSoInsteadOfCountingFromNothing() {
        final String line = LaunchCountdown.render(MESSAGES, Locale.ENGLISH, null, NOW);

        assertTrue(line.toLowerCase(Locale.ROOT).contains("not announced"), line);
    }

    @Test
    void theNoDateFragmentCarriesNoMiniMessageTag() {
        // Placeholders escapes what it inserts, so a tag here would show as literal text.
        final String line = LaunchCountdown.render(MESSAGES, Locale.ENGLISH, null, NOW);

        assertFalse(line.contains("<"), line);
    }

    @Test
    void theSentenceFormWrapsACountdownButNotTheNoDateLine() {
        // Wrapping would double the sentence; contains, since a tag opens the line.
        final String counting =
                LaunchCountdown.sentence(MESSAGES, Locale.ENGLISH, NOW.plus(Duration.ofMinutes(20)), NOW);
        assertTrue(counting.contains("The network opens in"), counting);
        assertTrue(counting.contains("20 minutes"), counting);

        final String unknown = LaunchCountdown.sentence(MESSAGES, Locale.ENGLISH, null, NOW);
        assertFalse(unknown.contains("The network opens in"), unknown);
    }

    @Test
    void germanIsTranslatedRatherThanFallingBackToEnglish() {
        // A missing key falls back to English silently, which is how a half-translated screen ships.
        assertEquals(
                "42 Minuten", LaunchCountdown.render(MESSAGES, Locale.GERMAN, NOW.plus(Duration.ofMinutes(42)), NOW));
        assertEquals("jedem Moment", LaunchCountdown.render(MESSAGES, Locale.GERMAN, NOW, NOW));
        assertTrue(LaunchCountdown.sentence(MESSAGES, Locale.GERMAN, NOW.plus(Duration.ofMinutes(42)), NOW)
                .contains("Das Netzwerk öffnet"));
    }

    private static String render(final Duration remaining) {
        return LaunchCountdown.render(MESSAGES, Locale.ENGLISH, NOW.plus(remaining), NOW);
    }
}
