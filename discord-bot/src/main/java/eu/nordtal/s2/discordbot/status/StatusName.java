package eu.nordtal.s2.discordbot.status;

import static eu.nordtal.s2.discordbot.AccessMessages.MESSAGES;

import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.database.network.NetworkSnapshot;
import eu.nordtal.s2.messages.Messages;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Renders the status channel name for one language, from the phase, the counts and the opening instant.
 *
 * Steps are coarse because each change costs a rename; the countdown rounds down so people arrive early, not late.
 */
public final class StatusName {

    /** The step the last hour is rendered in, and so the number of renames it costs. */
    private static final int FINAL_HOUR_STEP_MINUTES = 10;

    /** Discord refuses a channel name longer than this. */
    static final int MAX_LENGTH = 100;

    private StatusName() {}

    /**
     * Returns the channel name, never empty and never longer than {@link #MAX_LENGTH}.
     *
     * @param messages the bundle to take the wording from
     * @param locale the language to render in
     * @param phase the current season phase
     * @param snapshot the counts; ignored for {@code PRE_LAUNCH} and {@code MAINTENANCE}
     * @param launch when the network opens, or {@code null} when no date has been announced
     * @param now the instant to measure against
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
            // Covers a passed date too, since nobody has switched the phase yet.
            return messages.format(locale, MESSAGES.status().preLaunch().imminent());
        }
        if (remaining.toDays() >= 1) {
            return messages.format(
                    locale, MESSAGES.status().preLaunch().days(remaining.toDays(), remaining.toHoursPart()));
        }
        if (remaining.toHours() >= 1) {
            // Whole hours: minutes would rename sixty times an hour.
            return messages.format(locale, MESSAGES.status().preLaunch().hours(remaining.toHours()));
        }
        final long steps = remaining.toMinutes() / FINAL_HOUR_STEP_MINUTES;
        return messages.format(locale, MESSAGES.status().preLaunch().minutes(steps * FINAL_HOUR_STEP_MINUTES));
    }

    /** Cuts a name to the length Discord accepts, since a longer one is rejected outright. */
    private static String truncate(final String name) {
        return name.length() <= MAX_LENGTH ? name : name.substring(0, MAX_LENGTH);
    }
}
