package eu.nordtal.season.proxy.launch;

import static eu.nordtal.season.proxy.ProxyMessages.MESSAGES;

import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.proxy.ProxyMessages;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import org.jspecify.annotations.Nullable;

/**
 * How long until the network opens, coarse on purpose: days and hours, or hours and minutes.
 *
 * A passed instant renders as "any moment now": nothing switches the phase when the date passes.
 */
public final class LaunchCountdown {

    private LaunchCountdown() {}

    /**
     * The remaining time, or the line for a date nobody has announced.
     *
     * @param launch when the network opens, or {@code null} when no date is set
     */
    public static MessageRef left(final @Nullable Instant launch, final Instant now) {
        final ProxyMessages.NetworkCountdown lines = MESSAGES.countdown();
        if (launch == null) {
            return lines.unknown();
        }
        final Duration remaining = Duration.between(now, launch);
        if (remaining.isZero() || remaining.isNegative() || remaining.toMinutes() < 1) {
            return lines.imminent();
        }
        if (remaining.toDays() >= 1) {
            return lines.days(remaining.toDays(), remaining.toHoursPart());
        }
        if (remaining.toHours() >= 1) {
            return lines.hours(remaining.toHours(), remaining.toMinutesPart());
        }
        return lines.minutes(remaining.toMinutes());
    }

    /** The countdown wrapped in {@code gate.countdown}, or the "no date announced" line, for the disconnect screens. */
    public static Component component(
            final MessageRenderer renderer, final Locale locale, final @Nullable Instant launch, final Instant now) {
        if (launch == null) {
            return renderer.format(locale, MESSAGES.gate().countdownSection().unknown());
        }
        return renderer.format(locale, MESSAGES.gate().countdown(left(launch, now)));
    }
}
