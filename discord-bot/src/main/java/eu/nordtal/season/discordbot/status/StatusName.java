package eu.nordtal.season.discordbot.status;

import eu.nordtal.season.common.SeasonPhase;
import eu.nordtal.season.database.network.NetworkSnapshot;
import eu.nordtal.season.discordbot.DocumentRenderer;
import eu.nordtal.season.discordbot.StoredDocuments;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.value.Example;
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

    /** Which step of the countdown before the launch the name shows. */
    enum Countdown {
        DAYS,
        HOURS,
        MINUTES,
        IMMINENT,
        UNKNOWN
    }

    /**
     * @param teams the teams registered before the event, or left during it
     * @param players the players alive during the event, or online in the season
     */
    record Status(
            @Example("smp") SeasonPhase phase,
            @Example("days") Countdown countdown,
            @Example("3") long days,
            @Example("5") long hours,
            @Example("40") long minutes,
            @Example("12") long teams,
            @Example("31") long players) {}

    static final DocumentSpec<Status> STATUS =
            DocumentSpec.channelName(StoredDocuments.NAMESPACE, "status", "Status channel", Status.class);

    private StatusName() {}

    /**
     * Returns the channel name, which is never empty and never longer than Discord allows.
     *
     * @param snapshot the counts, which {@code PRE_LAUNCH} and {@code MAINTENANCE} ignore
     * @param launch the launch time, or {@code null} when there is no date
     */
    public static String render(
            final DocumentRenderer documents,
            final Locale locale,
            final SeasonPhase phase,
            final NetworkSnapshot snapshot,
            final @Nullable Instant launch,
            final Instant now) {
        return documents.plain(
                STATUS,
                switch (phase) {
                    case PRE_LAUNCH -> countdown(launch, now);
                    case PRE_EVENT -> counts(phase, snapshot.hgTeams(), 0);
                    case START_EVENT -> counts(phase, snapshot.hgTeamsAlive(), snapshot.hgAlive());
                    case SMP -> counts(phase, 0, snapshot.smpPlayers());
                    case MAINTENANCE -> counts(phase, 0, 0);
                },
                locale);
    }

    private static Status counts(final SeasonPhase phase, final long teams, final long players) {
        return new Status(phase, Countdown.UNKNOWN, 0, 0, 0, teams, players);
    }

    private static Status countdown(final @Nullable Instant launch, final Instant now) {
        if (launch == null) {
            return step(Countdown.UNKNOWN, 0, 0, 0);
        }
        final Duration remaining = Duration.between(now, launch);
        if (remaining.toMinutes() < FINAL_HOUR_STEP_MINUTES) {
            // This includes a launch time in the past.
            return step(Countdown.IMMINENT, 0, 0, 0);
        }
        if (remaining.toDays() >= 1) {
            return step(Countdown.DAYS, remaining.toDays(), remaining.toHoursPart(), 0);
        }
        if (remaining.toHours() >= 1) {
            // Whole hours only.
            return step(Countdown.HOURS, 0, remaining.toHours(), 0);
        }
        final long steps = remaining.toMinutes() / FINAL_HOUR_STEP_MINUTES;
        return step(Countdown.MINUTES, 0, 0, steps * FINAL_HOUR_STEP_MINUTES);
    }

    private static Status step(final Countdown countdown, final long days, final long hours, final long minutes) {
        return new Status(SeasonPhase.PRE_LAUNCH, countdown, days, hours, minutes, 0, 0);
    }
}
