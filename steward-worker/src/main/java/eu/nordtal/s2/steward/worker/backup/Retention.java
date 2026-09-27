package eu.nordtal.s2.steward.worker.backup;

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
 * Decides which backups expire: each day collapses to its last run, then days, weeks and months are kept.
 *
 * @param daily how many of the most recent days are kept in full
 * @param weekly how many ISO weeks keep their newest surviving day
 * @param monthly how many calendar months keep their newest surviving day
 * @param collapseAfterDays how long several runs of one day are all kept before only the last survives
 */
public record Retention(int daily, int weekly, int monthly, int collapseAfterDays) {

    /** One archive: a name for the report and the moment taken from the stamp in its file name, not its mtime. */
    public record Dated(String name, Instant taken) {}

    public Retention {
        if (daily < 1) {
            // Zero would delete every backup and is likely an unset value read as 0.
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
     * Returns which archives of one series may go, oldest first; nothing inside the grace window ever goes.
     *
     * @param all everything of one series on the disk, in any order
     * @param now the moment of the sweep, which decides what is still inside the grace
     */
    public List<Dated> expired(final List<Dated> all, final Instant now) {
        final Instant settledBefore = now.minus(Duration.ofDays(collapseAfterDays));

        // Newest first, so "the last run of a day" and "the newest day of a week" are both the first entry a loop sees.
        final List<Dated> newestFirst = all.stream()
                .sorted(Comparator.comparing(Dated::taken)
                        .thenComparing(Dated::name)
                        .reversed())
                .toList();

        final Set<Dated> keep = new HashSet<>();
        final Map<LocalDate, Dated> perDay = new LinkedHashMap<>();
        for (final Dated one : newestFirst) {
            // The first one met on a given day is the last one taken and stands for the day.
            perDay.putIfAbsent(LocalDate.ofInstant(one.taken(), ZoneOffset.UTC), one);
            // Inside the grace every run survives; an unsettled day is not collapsed.
            if (!one.taken().isBefore(settledBefore)) {
                keep.add(one);
            }
        }

        // A day's representative survives only if the schedule keeps its day.
        final List<Dated> days = List.copyOf(perDay.values());

        keep.addAll(days.stream().limit(daily).toList());
        keep.addAll(newestOfEach(
                days,
                weekly,
                day -> day.get(IsoFields.WEEK_BASED_YEAR) * 100 + day.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)));
        keep.addAll(newestOfEach(days, monthly, day -> day.getYear() * 100 + day.getMonthValue()));

        return all.stream()
                .filter(one -> !keep.contains(one))
                .sorted(Comparator.comparing(Dated::name))
                .toList();
    }

    /**
     * Returns the newest day of each of the {@code buckets} newest weeks or months.
     *
     * Counted from the newest bucket, so the total is at most {@code daily + weekly + monthly}.
     */
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
