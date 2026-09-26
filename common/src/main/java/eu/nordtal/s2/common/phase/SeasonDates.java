package eu.nordtal.s2.common.phase;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Parses and formats the two season dates, so both {@code /phase} commands agree on what a date means.
 *
 * {@link #ZONE} is {@code Europe/Berlin}, not the containers' UTC, and daylight saving applies per date.
 * A repeated or missing hour at a clock change is resolved by {@link ZonedDateTime#of} without refusal.
 */
public final class SeasonDates {

    /** The zone every season date is typed and displayed in. */
    public static final ZoneId ZONE = ZoneId.of("Europe/Berlin");

    /** The word that clears a date instead of setting one. */
    public static final String CLEAR = "clear";

    /** What an admin types. The {@code T} is accepted too, because half the world's tooling emits it. */
    public static final String PATTERN = "yyyy-MM-dd HH:mm";

    // STRICT, hence 'uuuu' not 'yyyy': SMART would clamp February 30 to the month's end instead of refusing it.
    private static final DateTimeFormatter TYPED =
            DateTimeFormatter.ofPattern(PATTERN.replace('y', 'u'), Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter SHOWN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z", Locale.ROOT);

    private SeasonDates() {}

    /**
     * Reads a date an admin typed.
     *
     * @param text what was typed, may be {@code null}
     * @return the instant it names, or empty when it is not a date in {@link #PATTERN} - the
     *         caller turns that into a message naming the pattern, because "empty" here never
     *         means "no date", which is {@link #CLEAR}'s job
     */
    public static Optional<Instant> parse(final @Nullable String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        // A 'T' for the space is the only shape difference tolerated; anything else is a typo to show.
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
    public static String format(final @Nullable Instant when) {
        return format(when, "not set");
    }

    /**
     * Formats a date with the caller's own word for a missing one, such as a translated text.
     *
     * @param when  the instant, may be {@code null}
     * @param unset what to say for {@code null}
     */
    public static String format(final @Nullable Instant when, final String unset) {
        return when == null ? unset : SHOWN.format(when.atZone(ZONE));
    }
}
