package eu.nordtal.s2.proxy.launch;

import static eu.nordtal.s2.proxy.ProxyMessages.MESSAGES;

import eu.nordtal.s2.messagerendering.MessageRenderer;
import eu.nordtal.s2.messages.Messages;
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
     * Renders the remaining time, or the line for a date nobody has announced.
     *
     * @param launch when the network opens, or {@code null} when no date is set
     * @return one line, never {@code null} and never empty
     */
    public static String render(
            final Messages messages, final Locale locale, final @Nullable Instant launch, final Instant now) {
        if (launch == null) {
            // A plain-text fragment substituted into a wrapper; a tag here would survive escaping as literal text.
            return messages.format(locale, MESSAGES.countdown().unknown());
        }

        final Duration remaining = Duration.between(now, launch);
        if (remaining.isZero() || remaining.isNegative() || remaining.toMinutes() < 1) {
            return messages.format(locale, MESSAGES.countdown().imminent());
        }
        if (remaining.toDays() >= 1) {
            return messages.format(locale, MESSAGES.countdown().days(remaining.toDays(), remaining.toHoursPart()));
        }
        if (remaining.toHours() >= 1) {
            return messages.format(locale, MESSAGES.countdown().hours(remaining.toHours(), remaining.toMinutesPart()));
        }
        return messages.format(locale, MESSAGES.countdown().minutes(remaining.toMinutes()));
    }

    /** The countdown wrapped in {@code gate.countdown}, or the "no date announced" line, for the disconnect screens. */
    public static Component component(
            final Messages messages, final Locale locale, final @Nullable Instant launch, final Instant now) {
        final MessageRenderer renderer = MessageRenderer.of(messages);
        if (launch == null) {
            return renderer.format(locale, MESSAGES.gate().countdownSection().unknown());
        }
        return renderer.format(locale, MESSAGES.gate().countdown(render(messages, locale, launch, now)));
    }
}
