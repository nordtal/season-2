package eu.nordtal.s2.steward.worker.backup;

import org.jetbrains.annotations.NotNull;

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
 * How long a backup is kept - the arithmetic, with no directory under it.
 *
 * <h2>Two rules, and the order they compose in</h2>
 * Till chose the staggered schedule on 2026-09-18 (steward/95): {@code daily} days are kept in full,
 * then one copy a week for {@code weekly} weeks, then one a month for {@code monthly} months. The
 * second rule is his own and is in no standard tool - <i>"in the end one backup a day is left, at
 * most the last of them"</i>, translated. Several runs in one day are normal here (somebody
 * takes one by hand before touching something, and the nightly one arrives anyway), and a few days
 * later only the <b>last</b> of that day survives.
 *
 * <p><b>The day collapses first, and the schedule then counts days.</b> The other order is what a
 * flat retention does today and it is the bug this replaces: three runs on one Tuesday would spend
 * three of the fourteen slots, so a busy week would silently shorten the history to a few days. Once
 * the day is one file, {@code daily} is a number of <em>days</em> and means what it says on the
 * page.</p>
 *
 * <h2>The grace period, which is the part that needs a reason</h2>
 * {@code collapseAfterDays} is how long the extra runs of a day are left alone. Zero would delete
 * the backup somebody took by hand five minutes ago the moment the nightly one lands - which is the
 * one moment they are certainly still working on whatever they took it for. Till asked for the
 * collapse to happen "a few days later", so the grace is a setting and its default is three.
 *
 * <p>Nothing inside the grace window is ever deleted by this class, for any reason: it is the newest
 * couple of days, {@code daily} is at least 1, and a sweep that can reach into "yesterday" is a
 * sweep whose worst bug is unrecoverable.</p>
 *
 * <h2>Why this is separate from {@link TarSnapshots}</h2>
 * That class owns a directory, a clock, a naming scheme and a list of files that may or may not be
 * archives. What should be deleted is arithmetic on timestamps, and that is where the mistakes are:
 * a sweep wrong by one week deletes six months of history on its next run, and nobody finds out
 * until a restore. The arithmetic is here and is checked against dates written out by hand in
 * {@code RetentionTest}; the files stay there.
 *
 * @param daily             how many of the most recent days are kept in full
 * @param weekly            how many ISO weeks keep their newest surviving day
 * @param monthly           how many calendar months keep their newest surviving day
 * @param collapseAfterDays how long several runs of one day are all kept before only the last of
 *                          that day survives
 */
public record Retention(int daily, int weekly, int monthly, int collapseAfterDays) {

    /**
     * One archive, as this class needs it: something to name in a report and the moment it stands
     * for. The moment comes from the stamp in the file name rather than from an mtime - a file
     * copied off this host and back has a new mtime and the same age.
     */
    public record Dated(@NotNull String name, @NotNull Instant taken) {
    }

    public Retention {
        if (daily < 1) {
            // Refused rather than obeyed, the same argument the flat `keep` made: a retention of
            // zero deletes every backup there is, and the likeliest way to arrive here is a config
            // value nobody set being read as 0.
            throw new IllegalArgumentException("backup.retention.daily must be at least 1, was " + daily);
        }
        if (weekly < 0 || monthly < 0) {
            throw new IllegalArgumentException(
                    "backup.retention.weekly and .monthly cannot be negative, were "
                            + weekly + " and " + monthly);
        }
        if (collapseAfterDays < 0) {
            throw new IllegalArgumentException(
                    "backup.retention.collapse-after-days cannot be negative, was " + collapseAfterDays);
        }
    }

    /**
     * Which of these archives may go, oldest first.
     *
     * <p>One series at a time - the caller groups by volume, and the database dump is a series of
     * its own. Counting two volumes together would keep fourteen copies of whichever was written
     * last and none of the other, which is the failure that looks exactly like a working
     * retention.</p>
     *
     * @param all everything of one series that is on the disk, in any order
     * @param now the moment the sweep is running, which decides what is still inside the grace
     */
    public @NotNull List<Dated> expired(final @NotNull List<Dated> all, final @NotNull Instant now) {
        final Instant settledBefore = now.minus(Duration.ofDays(collapseAfterDays));

        // Newest first, so "the last run of a day" and "the newest day of a week" are both simply
        // the first entry a loop sees.
        final List<Dated> newestFirst = all.stream()
                .sorted(Comparator.comparing(Dated::taken).thenComparing(Dated::name).reversed())
                .toList();

        final Set<Dated> keep = new HashSet<>();
        final Map<LocalDate, Dated> perDay = new LinkedHashMap<>();
        for (final Dated one : newestFirst) {
            // The first one this loop meets on a given day is the last one taken that day, and it
            // is the one that stands for the day from here on.
            perDay.putIfAbsent(LocalDate.ofInstant(one.taken(), ZoneOffset.UTC), one);
            // Inside the grace, every run survives - a day that has not settled yet is not
            // collapsed, and the schedule below never reaches back this far either.
            if (!one.taken().isBefore(settledBefore)) {
                keep.add(one);
            }
        }

        // What the schedule is applied to: one archive per day, newest day first. A day's
        // representative is not kept for being one - it survives only if the schedule keeps its
        // day, which is what makes `daily` a window rather than "every day there has ever been".
        final List<Dated> days = List.copyOf(perDay.values());

        keep.addAll(days.stream().limit(daily).toList());
        keep.addAll(newestOfEach(days, weekly,
                day -> day.get(IsoFields.WEEK_BASED_YEAR) * 100 + day.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)));
        keep.addAll(newestOfEach(days, monthly, day -> day.getYear() * 100 + day.getMonthValue()));

        return all.stream()
                .filter(one -> !keep.contains(one))
                .sorted(Comparator.comparing(Dated::name))
                .toList();
    }

    /**
     * The newest day of each of the {@code buckets} newest buckets, where a bucket is a week or a
     * month.
     *
     * <p>Counted from the newest bucket rather than from the end of the daily window, which is the
     * convention every other backup tool uses and the one the sentence on the page describes: eight
     * weekly copies means eight weeks of history, not eight weeks <em>on top of</em> the daily ones.
     * The buckets the daily window already covers therefore keep nothing extra, and that is why the
     * total is "at most daily + weekly + monthly" rather than exactly it.</p>
     */
    private static List<Dated> newestOfEach(final List<Dated> daysNewestFirst, final int buckets,
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
