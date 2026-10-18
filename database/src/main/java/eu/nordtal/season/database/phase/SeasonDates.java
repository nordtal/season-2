package eu.nordtal.season.database.phase;

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
 * Parses the two season dates for both {@code /phase} commands, in the zone the caller names.
 * A repeated or missing hour of a clock change is resolved, not refused.
 */
public final class SeasonDates {

    /** The word that clears a date. */
    public static final String CLEAR = "clear";

    /** The accepted format. A {@code T} may replace the space. */
    public static final String PATTERN = "yyyy-MM-dd HH:mm";

    // STRICT needs 'uuuu': SMART would clamp February 30 to the end of the month.
    private static final DateTimeFormatter TYPED =
            DateTimeFormatter.ofPattern(PATTERN.replace('y', 'u'), Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);

    private SeasonDates() {}

    /** Returns the instant a typed date names, or empty if it does not match {@link #PATTERN}. */
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

    public static boolean isClear(final @Nullable String text) {
        return text != null && CLEAR.equalsIgnoreCase(text.strip());
    }
}
