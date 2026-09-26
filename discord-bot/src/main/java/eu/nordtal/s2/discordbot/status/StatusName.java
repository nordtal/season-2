package eu.nordtal.s2.discordbot.status;

import static eu.nordtal.s2.discordbot.AccessMessages.MESSAGES;

import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.network.NetworkSnapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * What the status channel is called, right now, in one language.
 *
 * Pure: phase, snapshot, opening instant and "now" in, one string out. Everything that talks to Discord lives in
 * {@link StatusChannels}, so every rule below is testable without a guild and without waiting for a clock.
 *
 * The phase decides what the channel is for: {@code PRE_LAUNCH} says how long until the network opens,
 * {@code PRE_EVENT} how many teams have registered, {@code START_EVENT} how many teams and players are still
 * alive, {@code SMP} how many players are registered on the SMP, and {@code MAINTENANCE} that the network is down.
 *
 * Every step is coarse on purpose, and that is a rate limit, not a taste: Discord allows two renames per ten
 * minutes per channel and blocks the route hard when that is abused (undocumented, per
 * discord/discord-api-docs#1900). The channel is therefore only renamed when this method returns something
 * different from what was last set, which makes the granularity of these lines the actual API budget:
 *
 * - a day or more out: days and whole hours - one change an hour;
 *
 * - under a day: whole hours - one change an hour;
 *
 * - the last hour: minutes rounded down to ten - five changes, then one more;
 *
 * - under ten minutes: a fixed line, which cannot change again.
 *
 * Rounding down rather than to the nearest ten is deliberate: a countdown that understates the time left sends
 * people early, and one that overstates it sends them late.
 *
 * The counts behave the same way. Teams and players change when a game changes, not on a timer, and the numbers that
 * would move constantly - total aura, milestone percentage - are deliberately not here. {@link StatusChannels} still
 * enforces a floor between renames, because "a team was eliminated" is an event this class cannot pace.
 *
 * A passed opening instant is not a negative number: Nothing switches the phase when the date passes; that stays an
 * admin's decision. The window between the announced instant and the actual switch is a normal state and reads as
 * one.
 */
public final class StatusName {

    /** The step the last hour is rendered in - and, with it, the number of renames it costs. */
    private static final int FINAL_HOUR_STEP_MINUTES = 10;

    /** Discord refuses a channel name longer than this. */
    static final int MAX_LENGTH = 100;

    private StatusName() {}

    /**
     * @param messages the bundle to take the wording from
     * @param locale   the language to render in
     * @param phase    the current season phase
     * @param snapshot the counts; ignored for {@code PRE_LAUNCH} and {@code MAINTENANCE}
     * @param launch   when the network opens, or {@code null} when no date has been announced
     * @param now      the instant to measure against
     * @return the channel name, never {@code null}, never empty, never longer than
     *         {@link #MAX_LENGTH}
     */
    public static String render(
            final Messages messages,
            final Locale locale,
            final SeasonPhase phase,
            final NetworkSnapshot snapshot,
            final @Nullable Instant launch,
            final Instant now) {
        return truncate(
                switch (phase) {
                    case PRE_LAUNCH -> countdown(messages, locale, launch, now);
                    case PRE_EVENT -> messages.format(locale, MESSAGES.status().preEvent(snapshot.hgTeams()));
                    case START_EVENT ->
                        messages.format(
                                locale, MESSAGES.status().startEvent(snapshot.hgTeamsAlive(), snapshot.hgAlive()));
                    case SMP -> messages.format(locale, MESSAGES.status().smp(snapshot.smpPlayers()));
                    case MAINTENANCE ->
                        messages.format(locale, MESSAGES.status().maintenance());
                });
    }

    private static String countdown(
            final Messages messages, final Locale locale, final @Nullable Instant launch, final Instant now) {
        if (launch == null) {
            return messages.format(locale, MESSAGES.status().preLaunch().unknown());
        }

        final Duration remaining = Duration.between(now, launch);
        if (remaining.toMinutes() < FINAL_HOUR_STEP_MINUTES) {
            // Covers zero and negative too: the date has passed and nobody has switched the phase.
            return messages.format(locale, MESSAGES.status().preLaunch().imminent());
        }
        if (remaining.toDays() >= 1) {
            return messages.format(
                    locale, MESSAGES.status().preLaunch().days(remaining.toDays(), remaining.toHoursPart()));
        }
        if (remaining.toHours() >= 1) {
            // Whole hours, minutes dropped: a name carrying minutes would change sixty times an hour, not twelve.
            return messages.format(locale, MESSAGES.status().preLaunch().hours(remaining.toHours()));
        }
        final long steps = remaining.toMinutes() / FINAL_HOUR_STEP_MINUTES;
        return messages.format(locale, MESSAGES.status().preLaunch().minutes(steps * FINAL_HOUR_STEP_MINUTES));
    }

    /**
     * Discord rejects a name over 100 characters outright.
     *
     * That would stop the channel updating rather than looking wrong, so a translation somebody made too long is
     * cut here instead of failing there.
     */
    private static String truncate(final String name) {
        return name.length() <= MAX_LENGTH ? name : name.substring(0, MAX_LENGTH);
    }
}
