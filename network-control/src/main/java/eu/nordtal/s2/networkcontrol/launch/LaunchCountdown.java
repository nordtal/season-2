package eu.nordtal.s2.networkcontrol.launch;

import eu.nordtal.s2.common.message.Messages;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/**
 * How long until the network opens, as a line somebody reads once.
 *
 * <p>Both places that show it are read-once surfaces: a disconnect screen, which is gone the moment
 * the player closes it, and the MOTD in the server browser, which refreshes on the client's own
 * schedule and not on ours. That is why this is deliberately coarse - days and hours, or hours and
 * minutes, never seconds. A number that is already stale by the time it is read should not pretend
 * to a precision it does not have.
 *
 * <p>The instant comes from {@code season_phase.launch} ({@code V8__pre_launch.sql}) and may be
 * absent: a phase without an announced date is a real state, not a defect, and
 * {@link #render(Messages, Locale, Instant, Instant)} answers the "not announced yet" line for it.
 *
 * <p><b>A passed instant renders as "any moment now", never as a negative or as zero.</b> Nothing
 * switches the phase when the date passes - that stays an admin's decision - so the window between
 * the announced instant and the actual switch is a normal state that has to read as one.
 */
public final class LaunchCountdown {

    private LaunchCountdown() {
    }

    /**
     * Renders the remaining time, or the line for a date nobody has announced.
     *
     * @param messages the bundle to take the wording from
     * @param locale   the language to render in; English is the fallback the bundle guarantees
     * @param launch   when the network opens, or {@code null} when no date is set
     * @param now      the instant to measure against, passed in so this is testable without a clock
     * @return one line, never {@code null} and never empty
     */
    public static String render(final Messages messages, final Locale locale, final Instant launch,
                                final Instant now) {
        if (launch == null) {
            return messages.get(locale, "gate.countdown.unknown");
        }

        final Duration remaining = Duration.between(now, launch);
        if (remaining.isZero() || remaining.isNegative() || remaining.toMinutes() < 1) {
            return messages.get(locale, "countdown.imminent");
        }
        if (remaining.toDays() >= 1) {
            return messages.format(locale, "countdown.days",
                    "days", remaining.toDays(),
                    "hours", remaining.toHoursPart());
        }
        if (remaining.toHours() >= 1) {
            return messages.format(locale, "countdown.hours",
                    "hours", remaining.toHours(),
                    "minutes", remaining.toMinutesPart());
        }
        return messages.format(locale, "countdown.minutes", "minutes", remaining.toMinutes());
    }

    /**
     * The full sentence, ready to put under a disconnect screen: the countdown wrapped in
     * {@code gate.countdown}, or the "no date announced" line on its own - which is already a
     * sentence and must not be wrapped in one.
     *
     * @param messages the bundle
     * @param locale   the language
     * @param launch   the opening instant, or {@code null}
     * @param now      the instant to measure against
     * @return one sentence
     */
    public static String sentence(final Messages messages, final Locale locale, final Instant launch,
                                  final Instant now) {
        if (launch == null) {
            return messages.get(locale, "gate.countdown.unknown");
        }
        return messages.format(locale, "gate.countdown", "countdown", render(messages, locale, launch, now));
    }

    /**
     * The same sentence as a component, for the disconnect screens.
     *
     * <p><b>It composes first and parses once</b>, rather than going through
     * {@code MessageRenderer.format}, and the difference matters here: {@code gate.countdown} wraps
     * {@code countdown.*}, so a renderer would escape the inner message's tags on its way into the
     * outer one and a formatted countdown could never carry any. Parsing the finished sentence lets
     * either key carry tags - and nothing arbitrary is substituted along the way, because every
     * value this class puts in is a number it computed itself. The MOTD keeps taking the
     * {@link #sentence} form; {@code Placeholders} escapes it there for the same reason, since a
     * MOTD template is edited by hand.</p>
     *
     * @param messages the bundle
     * @param locale   the language
     * @param launch   the opening instant, or {@code null}
     * @param now      the instant to measure against
     * @return one sentence, parsed as MiniMessage
     */
    public static Component component(final Messages messages, final Locale locale,
                                      final Instant launch, final Instant now) {
        return MiniMessage.miniMessage().deserialize(sentence(messages, locale, launch, now));
    }
}
