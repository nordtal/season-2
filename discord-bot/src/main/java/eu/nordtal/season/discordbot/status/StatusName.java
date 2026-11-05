package eu.nordtal.season.discordbot.status;

import eu.nordtal.season.common.season.SeasonMode;
import eu.nordtal.season.common.season.SeasonState;
import eu.nordtal.season.database.network.NetworkSnapshot;
import eu.nordtal.season.discordbot.StoredDocuments;
import eu.nordtal.season.discordrendering.DocumentRenderer;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.value.Example;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/**
 * Renders the status channel name of one language from the season, the counts and the clock.
 *
 * The countdown has coarse steps, because each change costs a rename. It rounds down.
 */
public final class StatusName {

    private static final int FINAL_HOUR_STEP_MINUTES = 10;

    /** What the name shows, in the order of the gate: not launched, then maintenance, then the mode. */
    enum Shown {
        CLOSED,
        COUNTDOWN,
        MAINTENANCE,
        SMP,
        HUNGER_GAMES
    }

    /** Which step of the countdown before the launch the name shows. */
    enum Countdown {
        DAYS,
        HOURS,
        MINUTES,
        IMMINENT,
        NONE
    }

    /**
     * @param playing whether a game of the {@code hunger-games} has eliminated anyone yet
     * @param teams the teams registered for the round, or left in its game
     * @param players the players alive in the game, or the players the {@code smp} has seen
     */
    record Status(
            @Example("smp") Shown state,
            @Example("days") Countdown countdown,
            @Example("3") long days,
            @Example("5") long hours,
            @Example("40") long minutes,
            boolean playing,
            @Example("12") long teams,
            @Example("31") long players) {}

    static final DocumentSpec<Status> STATUS = DocumentSpec.channelName(
                    StoredDocuments.NAMESPACE, "status", "Status channel", Status.class)
            .titled("Status channel name");

    private StatusName() {}

    /** Returns what the name shows for {@code season} at {@code now}. */
    static Shown shown(final SeasonState season, final Instant now) {
        return switch (season.opening(now)) {
            case CLOSED -> Shown.CLOSED;
            case COUNTDOWN -> Shown.COUNTDOWN;
            case LAUNCHED -> {
                if (season.maintenance()) {
                    yield Shown.MAINTENANCE;
                }
                yield season.mode() == SeasonMode.SMP ? Shown.SMP : Shown.HUNGER_GAMES;
            }
        };
    }

    /** Returns whether the name for {@code season} at {@code now} reads the counts. */
    static boolean needsCounts(final SeasonState season, final Instant now) {
        final Shown shown = shown(season, now);
        return shown == Shown.SMP || shown == Shown.HUNGER_GAMES;
    }

    /**
     * Returns the channel name, which is never empty and never longer than Discord allows.
     *
     * @param snapshot the counts, which only an open {@code smp} or {@code hunger-games} reads
     */
    public static String render(
            final DocumentRenderer documents,
            final Locale locale,
            final SeasonState season,
            final NetworkSnapshot snapshot,
            final Instant now) {
        final Instant launch = season.launch();
        return documents.plain(
                STATUS,
                switch (shown(season, now)) {
                    case CLOSED -> counts(Shown.CLOSED, false, 0, 0);
                    case COUNTDOWN ->
                        launch == null ? counts(Shown.CLOSED, false, 0, 0) : countdown(Duration.between(now, launch));
                    case MAINTENANCE -> counts(Shown.MAINTENANCE, false, 0, 0);
                    case SMP -> counts(Shown.SMP, false, 0, snapshot.smpPlayers());
                    case HUNGER_GAMES -> hungerGames(snapshot);
                },
                locale);
    }

    /** Before the first elimination the name counts the registered teams, after it those still in. */
    private static Status hungerGames(final NetworkSnapshot snapshot) {
        if (snapshot.hgEliminated() > 0) {
            return counts(Shown.HUNGER_GAMES, true, snapshot.hgTeamsAlive(), snapshot.hgAlive());
        }
        return counts(Shown.HUNGER_GAMES, false, snapshot.hgTeams(), 0);
    }

    private static Status counts(final Shown shown, final boolean playing, final long teams, final long players) {
        return new Status(shown, Countdown.NONE, 0, 0, 0, playing, teams, players);
    }

    private static Status countdown(final Duration remaining) {
        if (remaining.toMinutes() < FINAL_HOUR_STEP_MINUTES) {
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
        return new Status(Shown.COUNTDOWN, countdown, days, hours, minutes, false, 0, 0);
    }
}
