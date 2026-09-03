package eu.nordtal.s2.discordbot.status;

import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.network.NetworkSnapshot;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/**
 * What the status channel is called, right now, in one language.
 *
 * <p>Pure: phase, snapshot, opening instant and "now" in, one string out. Everything that talks to
 * Discord lives in {@link StatusChannels}, so every rule below is testable without a guild and
 * without waiting for a clock.
 *
 * <h2>The phase decides what the channel is for</h2>
 * <table>
 *   <caption>What the name says, per phase</caption>
 *   <tr><th>phase</th><th>the name</th></tr>
 *   <tr><td>{@code PRE_LAUNCH}</td><td>how long until the network opens</td></tr>
 *   <tr><td>{@code PRE_EVENT}</td><td>how many teams have registered</td></tr>
 *   <tr><td>{@code START_EVENT}</td><td>how many teams and players are still alive</td></tr>
 *   <tr><td>{@code SMP}</td><td>how many players are registered on the SMP</td></tr>
 *   <tr><td>{@code MAINTENANCE}</td><td>that the network is down</td></tr>
 * </table>
 *
 * <h2>Every step is coarse on purpose, and that is a rate limit, not a taste</h2>
 * Discord allows <b>two renames per ten minutes per channel</b> and blocks the route hard when that
 * is abused (undocumented; confirmed against discord/discord-api-docs#1900 on 2026-09-03). The
 * channel is therefore only renamed when this method returns something different from what was last
 * set, which makes the granularity of these lines the actual API budget:
 * <ul>
 *   <li>a day or more out: days and whole hours - one change an hour;</li>
 *   <li>under a day: whole hours - one change an hour;</li>
 *   <li>the last hour: minutes rounded <em>down</em> to ten - five changes, then one more;</li>
 *   <li>under ten minutes: a fixed line, which cannot change again.</li>
 * </ul>
 * Rounding down rather than to the nearest ten is deliberate: a countdown that understates the time
 * left sends people early, and one that overstates it sends them late.
 *
 * <p>The counts behave the same way. Teams and players change when a game changes, not on a timer,
 * and the numbers that would move constantly - total aura, milestone percentage - are deliberately
 * not here. {@link StatusChannels} still enforces a floor between renames, because "a team was
 * eliminated" is an event this class cannot pace.
 *
 * <h2>A passed opening instant is not a negative number</h2>
 * Nothing switches the phase when the date passes; that stays an admin's decision. The window
 * between the announced instant and the actual switch is a normal state and reads as one.
 */
public final class StatusName {

    /** The step the last hour is rendered in - and, with it, the number of renames it costs. */
    private static final int FINAL_HOUR_STEP_MINUTES = 10;

    /** Discord refuses a channel name longer than this. */
    static final int MAX_LENGTH = 100;

    private StatusName() {
    }

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
    public static String render(final Messages messages, final Locale locale, final SeasonPhase phase,
                                final NetworkSnapshot snapshot, final Instant launch, final Instant now) {
        return truncate(switch (phase) {
            case PRE_LAUNCH -> countdown(messages, locale, launch, now);
            case PRE_EVENT -> messages.format(locale, "status.pre-event",
                    "teams", snapshot.hgTeams(),
                    "players", snapshot.hgParticipants());
            case START_EVENT -> messages.format(locale, "status.start-event",
                    "teams", snapshot.hgTeamsAlive(),
                    "players", snapshot.hgAlive());
            case SMP -> messages.format(locale, "status.smp", "players", snapshot.smpPlayers());
            case MAINTENANCE -> messages.get(locale, "status.maintenance");
        });
    }

    private static String countdown(final Messages messages, final Locale locale, final Instant launch,
                                    final Instant now) {
        if (launch == null) {
            return messages.get(locale, "status.pre-launch.unknown");
        }

        final Duration remaining = Duration.between(now, launch);
        if (remaining.toMinutes() < FINAL_HOUR_STEP_MINUTES) {
            // Covers zero and negative too: the date has passed and nobody has switched the phase.
            return messages.get(locale, "status.pre-launch.imminent");
        }
        if (remaining.toDays() >= 1) {
            return messages.format(locale, "status.pre-launch.days",
                    "days", remaining.toDays(),
                    "hours", remaining.toHoursPart());
        }
        if (remaining.toHours() >= 1) {
            // Whole hours, with the minutes deliberately dropped: a name carrying minutes would
            // change sixty times an hour and there is budget for twelve.
            return messages.format(locale, "status.pre-launch.hours", "hours", remaining.toHours());
        }
        final long steps = remaining.toMinutes() / FINAL_HOUR_STEP_MINUTES;
        return messages.format(locale, "status.pre-launch.minutes",
                "minutes", steps * FINAL_HOUR_STEP_MINUTES);
    }

    /**
     * Discord rejects a name over 100 characters outright, which would make the channel stop
     * updating rather than look wrong - so a translation somebody made too long is cut here instead
     * of failing there.
     */
    private static String truncate(final String name) {
        return name.length() <= MAX_LENGTH ? name : name.substring(0, MAX_LENGTH);
    }
}
