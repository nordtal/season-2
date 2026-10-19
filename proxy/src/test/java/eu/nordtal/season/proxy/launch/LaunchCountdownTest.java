package eu.nordtal.season.proxy.launch;

import static eu.nordtal.season.proxy.ProxyRenderer.RENDERER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The countdown the three {@code PRE_LAUNCH} screens and the server browser share.
 *
 * No announced date and a passed date are normal states and must not render as faults.
 */
class LaunchCountdownTest {

    private static final Instant NOW = Instant.parse("2026-09-03T12:00:00Z");

    @Test
    void daysAndHoursWhileTheOpeningIsStillDaysAway() {
        assertEquals(
                new LaunchCountdown.Countdown(LaunchCountdown.Stage.DAYS, 3, 4, 0),
                LaunchCountdown.left(NOW.plus(Duration.ofDays(3).plusHours(4).plusMinutes(30)), NOW),
                "minutes are noise next to three days");
        assertEquals(
                "The network opens in 3 days 4 hours.",
                render(Locale.ENGLISH, Duration.ofDays(3).plusHours(4)));
    }

    @Test
    void hoursAndMinutesInsideTheLastDay() {
        assertEquals(
                "The network opens in 5 hours 12 minutes.",
                render(Locale.ENGLISH, Duration.ofHours(5).plusMinutes(12)));
    }

    @Test
    void minutesOnlyInTheLastHour() {
        assertEquals("The network opens in 42 minutes.", render(Locale.ENGLISH, Duration.ofMinutes(42)));
    }

    @Test
    void theLastMinuteReadsAsImminentRatherThanAsZero() {
        // Below a minute there is nothing to count, and "0 minutes" reads as a fault.
        assertEquals("The network opens in any moment now.", render(Locale.ENGLISH, Duration.ofSeconds(30)));
        assertEquals("The network opens in any moment now.", render(Locale.ENGLISH, Duration.ZERO));
    }

    @Test
    void aPassedInstantNeverRendersNegative() {
        // The normal state between the announced instant and an admin's switch.
        final String line = render(Locale.ENGLISH, Duration.ofHours(-3));

        assertEquals("The network opens in any moment now.", line);
        assertFalse(line.contains("-"), line);
    }

    @Test
    void noAnnouncedDateSaysSoInsteadOfCountingFromNothing() {
        final String line = plain(Locale.ENGLISH, null);

        assertTrue(line.toLowerCase(Locale.ROOT).contains("not announced"), line);
        assertFalse(line.contains("The network opens in"), line);
    }

    @Test
    void germanIsTranslatedRatherThanFallingBackToEnglish() {
        assertEquals("Das Netzwerk öffnet in 42 Minuten.", render(Locale.GERMAN, Duration.ofMinutes(42)));
        assertEquals("Das Netzwerk öffnet in jedem Moment.", render(Locale.GERMAN, Duration.ZERO));
    }

    private static String render(final Locale locale, final Duration remaining) {
        return plain(locale, NOW.plus(remaining));
    }

    private static String plain(final Locale locale, final @Nullable Instant launch) {
        return PlainTextComponentSerializer.plainText()
                .serialize(RENDERER.format(locale, LaunchCountdown.line(launch, NOW)));
    }
}
