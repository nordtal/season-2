package eu.nordtal.s2.smp.farm;

import java.time.Duration;
import java.time.LocalTime;

/**
 * "Once a day at HH:mm" - the whole of the farm world's schedule, and nothing else.
 *
 * <p>Its own class because it is the one part of the daily reset that can be tested without a
 * server, and because getting "how long until 04:00" wrong is a mistake that shows up once a day at
 * a time nobody is watching. {@code postgres-backup/backup.sh} does the same arithmetic in shell
 * for the same reason; this is the Java half and it is tested against fixed clocks rather than
 * against the wall.
 */
public final class DailySchedule {

    private final LocalTime at;

    private DailySchedule(final LocalTime at) {
        this.at = at;
    }

    /**
     * Parses an {@code HH:mm} string.
     *
     * @throws IllegalArgumentException on anything else, deliberately: a schedule nobody can parse
     *                                  must stop the load rather than silently become "never"
     */
    public static DailySchedule parse(final String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("the reset time must be HH:mm, was empty");
        }
        final String trimmed = text.trim();
        try {
            return new DailySchedule(LocalTime.parse(trimmed));
        } catch (final RuntimeException exception) {
            throw new IllegalArgumentException(
                    "the reset time must be HH:mm in 24-hour form, was '" + trimmed + "'");
        }
    }

    public LocalTime at() {
        return at;
    }

    /**
     * How long from {@code now} until the next occurrence.
     *
     * <p>Exactly at the configured time this returns a full day rather than zero. That is on
     * purpose: the caller schedules the next run the moment the current one finishes, and a zero
     * would put it straight back into the same second, forever.
     */
    public Duration until(final LocalTime now) {
        final Duration delta = Duration.between(now, at);
        return delta.isPositive() ? delta : delta.plusDays(1);
    }

    /** The warning instants for a reset, as durations before it, largest first. */
    public static java.util.List<Duration> warningsBefore(final java.util.List<Integer> minutes) {
        return minutes.stream()
                .filter(m -> m != null && m > 0)
                .distinct()
                .sorted(java.util.Comparator.reverseOrder())
                .map(Duration::ofMinutes)
                .toList();
    }

    @Override
    public String toString() {
        return at.toString();
    }
}
