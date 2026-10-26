package eu.nordtal.season.proxy.launch;

import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Sent;
import eu.nordtal.season.messages.value.Example;
import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** The time until the network opens, in days and hours or in hours and minutes. */
public final class LaunchCountdown {

    /** How far away the opening is, which picks the words of the countdown. */
    public enum Stage {
        DAYS,
        HOURS,
        MINUTES,
        /** Less than a minute, or past. */
        IMMINENT,
        /** No opening date is set. */
        UNKNOWN
    }

    /**
     * The countdown's values: {@code days} and {@code hours} at {@link Stage#DAYS}, {@code hours} and {@code minutes}
     * at {@link Stage#HOURS}, {@code minutes} at {@link Stage#MINUTES}, none at the other stages.
     */
    public record Countdown(
            Stage countdown,
            @Example("2") long days,
            @Example("5") long hours,
            @Example("40") long minutes) {}

    /** The line under a login screen before the opening. */
    public static final DocumentSpec<Countdown> LINE = DocumentSpec.game(
                    "proxy", "launch-countdown", "Login", DocumentSpec.Shape.KICK_SCREEN, Countdown.class)
            .titled("Time until launch");

    private LaunchCountdown() {}

    /** Returns the remaining time, at {@link Stage#UNKNOWN} for a {@code null} launch date. */
    public static Countdown left(final @Nullable Instant launch, final Instant now) {
        if (launch == null) {
            return new Countdown(Stage.UNKNOWN, 0, 0, 0);
        }
        final Duration remaining = Duration.between(now, launch);
        if (remaining.isZero() || remaining.isNegative() || remaining.toMinutes() < 1) {
            return new Countdown(Stage.IMMINENT, 0, 0, 0);
        }
        if (remaining.toDays() >= 1) {
            return new Countdown(Stage.DAYS, remaining.toDays(), remaining.toHoursPart(), 0);
        }
        if (remaining.toHours() >= 1) {
            return new Countdown(Stage.HOURS, 0, remaining.toHours(), remaining.toMinutesPart());
        }
        return new Countdown(Stage.MINUTES, 0, 0, remaining.toMinutes());
    }

    /** Returns the line with the remaining time, or the line for a missing launch date. */
    public static Sent<Countdown> line(final @Nullable Instant launch, final Instant now) {
        return LINE.with(left(launch, now));
    }
}
