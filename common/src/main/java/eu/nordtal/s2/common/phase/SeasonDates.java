package eu.nordtal.s2.common.phase;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Locale;
import java.util.Optional;

/**
 * How the two season dates are typed and shown - the one place both {@code /phase} commands agree
 * on, so the Discord bot and the Velocity proxy cannot drift apart on what a date means.
 *
 * <h2>One time zone, written down rather than inherited</h2>
 * {@link #ZONE} is {@code Europe/Berlin}, hard-coded on purpose. The alternative is the JVM's
 * default, and every container in {@code compose.yml} runs on UTC - so an admin typing
 * {@code 18:00} would get 18:00 UTC, which is 20:00 on the clock everyone else in the season is
 * reading. A date typed here is a wall-clock time in the zone the season lives in, and daylight
 * saving is applied for the date in question: the same {@code 18:00} is {@code +02:00} in October
 * and {@code +01:00} in November, which is exactly the arithmetic that goes wrong when a human
 * writes the offset by hand.
 *
 * <h2>The one hour that is not a time</h2>
 * On the night the clocks go back an hour repeats, and on the night they go forward an hour does
 * not exist. {@link ZonedDateTime#of} resolves both without complaining - the repeated hour gets
 * the earlier (summer) offset, the missing hour is pushed forward by the gap. Neither is worth
 * refusing a season date over: both land within an hour of what was meant, and both nights are in
 * March and October, where a season opening is a deliberate choice somebody would notice.
 */
public final class SeasonDates {

    /** The zone every season date is typed and displayed in. */
    public static final ZoneId ZONE = ZoneId.of("Europe/Berlin");

    /** The word that clears a date instead of setting one. */
    public static final String CLEAR = "clear";

    /** What an admin types. The {@code T} is accepted too, because half the world's tooling emits it. */
    public static final String PATTERN = "yyyy-MM-dd HH:mm";

    // STRICT, and therefore 'uuuu' rather than 'yyyy': the default resolver is SMART, which
    // silently clamps 2026-02-30 to the end of February instead of refusing it. A season date that
    // quietly becomes a different date is the one failure this class must not have. STRICT rejects
    // the year-of-era field, which is why the pattern the admin is shown and the pattern that
    // parses differ by exactly that one letter.
    private static final DateTimeFormatter TYPED =
            DateTimeFormatter.ofPattern(PATTERN.replace('y', 'u'), Locale.ROOT)
                    .withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter SHOWN =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z", Locale.ROOT);

    private SeasonDates() {
    }

    /**
     * Reads a date an admin typed.
     *
     * @param text what was typed, may be {@code null}
     * @return the instant it names, or empty when it is not a date in {@link #PATTERN} - the
     *         caller turns that into a message naming the pattern, because "empty" here never
     *         means "no date", which is {@link #CLEAR}'s job
     */
    public static @NotNull Optional<Instant> parse(final @Nullable String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        // A single 'T' where the space belongs is the only shape difference worth tolerating;
        // anything else is a typo the admin should see rather than have guessed at.
        final String normalised = text.strip().replace('T', ' ');
        try {
            final LocalDateTime local = LocalDateTime.parse(normalised, TYPED);
            return Optional.of(ZonedDateTime.of(local, ZONE).toInstant());
        } catch (final DateTimeException notADate) {
            return Optional.empty();
        }
    }

    /** @return whether this is the word that clears a date rather than a date */
    public static boolean isClear(final @Nullable String text) {
        return text != null && CLEAR.equalsIgnoreCase(text.strip());
    }

    /**
     * How a date is shown back, in the same zone it is typed in.
     *
     * @param when the instant, may be {@code null}
     * @return the formatted date, or {@code "not set"} for {@code null} - a real state and not a
     *         missing value, so it is spelled out rather than left blank
     */
    public static @NotNull String format(final @Nullable Instant when) {
        return when == null ? "not set" : SHOWN.format(when.atZone(ZONE));
    }
}
