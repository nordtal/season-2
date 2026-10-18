package eu.nordtal.season.discordbot.status;

import static eu.nordtal.season.discordbot.AccessMessages.MESSAGES;

import eu.nordtal.season.common.SeasonPhase;
import eu.nordtal.season.database.network.NetworkSnapshot;
import eu.nordtal.season.discordbot.DiscordRenderer;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Renders the status channel name of one language from the phase, the counts and the launch time.
 *
 * The countdown has coarse steps, because each change costs a rename. It rounds down.
 */
public final class StatusName {

    private static final int FINAL_HOUR_STEP_MINUTES = 10;

    /** Discord's length limit for a channel name. */
    static final int MAX_LENGTH = 100;

    private StatusName() {}

    /**
     * Returns the channel name, which is never empty and never longer than {@link #MAX_LENGTH}.
     *
     * @param snapshot the counts, which {@code PRE_LAUNCH} and {@code MAINTENANCE} ignore
     * @param launch the launch time, or {@code null} when there is no date
     */
    public static String render(
            final DiscordRenderer messages,
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
            final DiscordRenderer messages, final Locale locale, final @Nullable Instant launch, final Instant now) {
        if (launch == null) {
            return messages.format(locale, MESSAGES.status().preLaunch().unknown());
        }

        final Duration remaining = Duration.between(now, launch);
        if (remaining.toMinutes() < FINAL_HOUR_STEP_MINUTES) {
            // This includes a launch time in the past.
            return messages.format(locale, MESSAGES.status().preLaunch().imminent());
        }
        if (remaining.toDays() >= 1) {
            return messages.format(
                    locale, MESSAGES.status().preLaunch().days(remaining.toDays(), remaining.toHoursPart()));
        }
        if (remaining.toHours() >= 1) {
            // Whole hours only.
            return messages.format(locale, MESSAGES.status().preLaunch().hours(remaining.toHours()));
        }
        final long steps = remaining.toMinutes() / FINAL_HOUR_STEP_MINUTES;
        return messages.format(locale, MESSAGES.status().preLaunch().minutes(steps * FINAL_HOUR_STEP_MINUTES));
    }

    private static String truncate(final String name) {
        return name.length() <= MAX_LENGTH ? name : name.substring(0, MAX_LENGTH);
    }
}
