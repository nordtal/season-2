package eu.nordtal.s2.database.phase;

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
 * Parses the two season dates, so both {@code /phase} commands agree on what a date means; a message shows them.
 * Dates are in the zone the caller names, the network's default; a clock change's repeated or missing hour is
 * resolved, not refused.
 */
public final class SeasonDates {

    /** The word that clears a date instead of setting one. */
    public static final String CLEAR = "clear";

    /** What an admin types; a {@code T} for the space is accepted too. */
    public static final String PATTERN = "yyyy-MM-dd HH:mm";

    // STRICT, hence 'uuuu' not 'yyyy': SMART would clamp February 30 to the month's end instead of refusing it.
    private static final DateTimeFormatter TYPED =
            DateTimeFormatter.ofPattern(PATTERN.replace('y', 'u'), Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);

    private SeasonDates() {}

    /**
     * Returns the instant a typed date names, or empty when it is not in {@link #PATTERN}; empty never means no date.
     */
    public static Optional<Instant> parse(final @Nullable String text, final ZoneId zone) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        final String normalised = text.strip().replace('T', ' ');
        try {
            final LocalDateTime local = LocalDateTime.parse(normalised, TYPED);
            return Optional.of(ZonedDateTime.of(local, zone).toInstant());
        } catch (final DateTimeException notADate) {
            return Optional.empty();
        }
    }

    /** Returns whether this is the word that clears a date rather than a date. */
    public static boolean isClear(final @Nullable String text) {
        return text != null && CLEAR.equalsIgnoreCase(text.strip());
    }
}
