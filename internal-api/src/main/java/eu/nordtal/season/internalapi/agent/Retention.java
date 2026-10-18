package eu.nordtal.season.internalapi.agent;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decides which backups expire. A day keeps its last run, then days, weeks and months are kept.
 *
 * @param daily how many of the most recent days are kept in full
 * @param weekly how many ISO weeks keep their newest surviving day
 * @param monthly how many calendar months keep their newest surviving day
 * @param collapseAfterDays after how many days only the last run of a day survives
 */
public record Retention(int daily, int weekly, int monthly, int collapseAfterDays) {

    /** One archive: its name and the moment from the stamp in its file name. */
    public record Dated(String name, Instant taken) {}

    public Retention {
        if (daily < 1) {
            // Zero would delete every backup.
            throw new IllegalArgumentException("backup.retention.daily must be at least 1, was " + daily);
        }
        if (weekly < 0 || monthly < 0) {
            throw new IllegalArgumentException(
                    "backup.retention.weekly and .monthly cannot be negative, were " + weekly + " and " + monthly);
        }
        if (collapseAfterDays < 0) {
            throw new IllegalArgumentException(
                    "backup.retention.collapse-after-days cannot be negative, was " + collapseAfterDays);
        }
    }

    /**
     * Returns which archives of one series may go, oldest first. Nothing inside the grace window goes.
     *
     * @param all everything of one series on the disk
     */
    public List<Dated> expired(final List<Dated> all, final Instant now) {
        return expired(all, now, false);
    }

    /**
     * Returns which archives of a series that is no longer taken may go, oldest first.
     * Days, weeks and months count back from {@code now}.
     */
    public List<Dated> expiredByCalendar(final List<Dated> all, final Instant now) {
        return expired(all, now, true);
    }

    private List<Dated> expired(final List<Dated> all, final Instant now, final boolean byCalendar) {
        final LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        final Instant settledBefore = now.minus(Duration.ofDays(collapseAfterDays));

        final List<Dated> newestFirst = all.stream()
                .sorted(Comparator.comparing(Dated::taken)
                        .thenComparing(Dated::name)
                        .reversed())
                .toList();

        final Set<Dated> keep = new HashSet<>();
        final Map<LocalDate, Dated> perDay = new LinkedHashMap<>();
        for (final Dated one : newestFirst) {
            perDay.putIfAbsent(LocalDate.ofInstant(one.taken(), ZoneOffset.UTC), one);
            if (!one.taken().isBefore(settledBefore)) {
                keep.add(one);
            }
        }

        final List<Dated> days = List.copyOf(perDay.values());

        // With the calendar, each schedule counts days back from today inside its own window.
        final List<Dated> dailyDays = byCalendar ? since(days, today.minusDays(daily - 1L)) : days;
        final List<Dated> weeklyDays =
                byCalendar ? since(days, today.minusWeeks(weekly - 1L).with(DayOfWeek.MONDAY)) : days;
        final List<Dated> monthlyDays =
                byCalendar ? since(days, today.minusMonths(monthly - 1L).withDayOfMonth(1)) : days;

        keep.addAll(dailyDays.stream().limit(daily).toList());
        keep.addAll(newestOfEach(
                weeklyDays,
                weekly,
                day -> day.get(IsoFields.WEEK_BASED_YEAR) * 100 + day.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)));
        keep.addAll(newestOfEach(monthlyDays, monthly, day -> day.getYear() * 100 + day.getMonthValue()));

        return all.stream()
                .filter(one -> !keep.contains(one))
                .sorted(Comparator.comparing(Dated::name))
                .toList();
    }

    private static List<Dated> since(final List<Dated> days, final LocalDate from) {
        return days.stream()
                .filter(one -> !LocalDate.ofInstant(one.taken(), ZoneOffset.UTC).isBefore(from))
                .toList();
    }

    /** Returns the newest day of each of the {@code buckets} newest weeks or months. */
    private static List<Dated> newestOfEach(
            final List<Dated> daysNewestFirst,
            final int buckets,
            final java.util.function.ToIntFunction<LocalDate> bucketOf) {
        if (buckets < 1) {
            return List.of();
        }
        final List<Dated> kept = new ArrayList<>();
        final Set<Integer> seen = new HashSet<>();
        for (final Dated one : daysNewestFirst) {
            final int bucket = bucketOf.applyAsInt(LocalDate.ofInstant(one.taken(), ZoneOffset.UTC));
            if (seen.contains(bucket)) {
                continue;
            }
            if (seen.size() == buckets) {
                break;
            }
            seen.add(bucket);
            kept.add(one);
        }
        return kept;
    }
}
