package eu.nordtal.s2.database.phase;

import eu.nordtal.s2.common.time.NetworkTime;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Parses and formats the two season dates, so both {@code /phase} commands agree on what a date means.
 * Dates are in {@link NetworkTime#ZONE}; a clock change's repeated or missing hour is resolved, not refused.
 */
public final class SeasonDates {

    /** The word that clears a date instead of setting one. */
    public static final String CLEAR = "clear";

    /** What an admin types; a {@code T} for the space is accepted too. */
    public static final String PATTERN = "yyyy-MM-dd HH:mm";

    // STRICT, hence 'uuuu' not 'yyyy': SMART would clamp February 30 to the month's end instead of refusing it.
    private static final DateTimeFormatter TYPED =
            DateTimeFormatter.ofPattern(PATTERN.replace('y', 'u'), Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter SHOWN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z", Locale.ROOT);

    private SeasonDates() {}

    /**
     * Returns the instant a typed date names, or empty when it is not in {@link #PATTERN}; empty never means no date.
     */
    public static Optional<Instant> parse(final @Nullable String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        final String normalised = text.strip().replace('T', ' ');
        try {
            final LocalDateTime local = LocalDateTime.parse(normalised, TYPED);
            return Optional.of(ZonedDateTime.of(local, NetworkTime.ZONE).toInstant());
        } catch (final DateTimeException notADate) {
            return Optional.empty();
        }
    }

    /** Returns whether this is the word that clears a date rather than a date. */
    public static boolean isClear(final @Nullable String text) {
        return text != null && CLEAR.equalsIgnoreCase(text.strip());
    }

    /** Returns a date in the zone it is typed in, or {@code "not set"} for {@code null}. */
    public static String format(final @Nullable Instant when) {
        return format(when, "not set");
    }

    /** Formats a date with the caller's own word for a missing one, such as a translated text. */
    public static String format(final @Nullable Instant when, final String unset) {
        return when == null ? unset : SHOWN.format(when.atZone(NetworkTime.ZONE));
    }
}
