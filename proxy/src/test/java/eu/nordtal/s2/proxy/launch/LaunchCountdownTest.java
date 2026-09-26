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
 * Every case here is one a player can actually see, and the two that matter most are the ones that
 * are not a duration at all: no date announced, and a date that has passed while nobody has
 * switched the phase yet. Both are normal states of a network before its opening, and both would
 * otherwise render as something that looks broken.
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
        // Below a minute there is nothing useful to count, and "0 minutes" reads as a fault.
        assertEquals("any moment now", render(Duration.ofSeconds(30)));
        assertEquals("any moment now", render(Duration.ZERO));
    }

    @Test
    void aPassedInstantNeverRendersNegative() {
        // The normal state between the announced instant and an admin's switch; "-3 hours" would look like a bug.
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
        // Placeholders escapes whatever it inserts; a tag here would become literal "<gray>" text, not colour.
        final String line = LaunchCountdown.render(MESSAGES, Locale.ENGLISH, null, NOW);

        assertFalse(line.contains("<"), line);
    }

    @Test
    void theSentenceFormWrapsACountdownButNotTheNoDateLine() {
        // Wrapping the two would double the sentence; contains, not startsWith, since a tag now opens the line.
        final String counting =
                LaunchCountdown.sentence(MESSAGES, Locale.ENGLISH, NOW.plus(Duration.ofMinutes(20)), NOW);
        assertTrue(counting.contains("The network opens in"), counting);
        assertTrue(counting.contains("20 minutes"), counting);

        final String unknown = LaunchCountdown.sentence(MESSAGES, Locale.ENGLISH, null, NOW);
        assertFalse(unknown.contains("The network opens in"), unknown);
    }

    @Test
    void germanIsTranslatedRatherThanFallingBackToEnglish() {
        // The bundle falls back to English for a missing key, silently, which is how a half-translated screen ships.
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
