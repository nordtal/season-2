package eu.nordtal.season.stewardagent.backup;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which archives go once the backups take more of the filesystem than their share.
 * The oldest archive of the largest series goes first; the newest verified archive of a series never goes.
 *
 * @param allowedBytes how much the finished archives and dumps may take together
 */
record DiskBudget(long allowedBytes) {

    /**
     * One finished archive or dump.
     *
     * @param verified whether no mark says the stop behind it could not be verified
     */
    record Held(String name, String series, Instant taken, long bytes, boolean verified) {}

    /** The budget as a share of a filesystem, the percentage held between 1 and 100. */
    static DiskBudget of(final long totalBytes, final int percent) {
        final int share = Math.clamp(percent, 1, 100);
        return new DiskBudget(totalBytes / 100 * share);
    }

    /**
     * Returns what goes, in the order it goes, until the rest fits or only kept archives are left.
     *
     * A series with no verified archive keeps its newest.
     */
    List<String> over(final List<Held> all) {
        long total = all.stream().mapToLong(Held::bytes).sum();
        if (total <= allowedBytes) {
            return List.of();
        }
        final Map<String, List<Held>> bySeries = new LinkedHashMap<>();
        all.stream()
                .sorted(Comparator.comparing(Held::taken).thenComparing(Held::name))
                .forEach(one -> bySeries.computeIfAbsent(one.series(), ignored -> new ArrayList<>())
                        .add(one));
        final Set<Held> kept = new HashSet<>();
        for (final List<Held> series : bySeries.values()) {
            kept.add(series.reversed().stream()
                    .filter(Held::verified)
                    .findFirst()
                    .orElse(series.getLast()));
        }

        final List<String> gone = new ArrayList<>();
        while (total > allowedBytes) {
            final List<Held> largest = bySeries.values().stream()
                    .filter(series -> series.stream().anyMatch(one -> !kept.contains(one)))
                    .max(Comparator.comparingLong(DiskBudget::bytes))
                    .orElse(null);
            if (largest == null) {
                break;
            }
            final Held oldest = largest.stream()
                    .filter(one -> !kept.contains(one))
                    .findFirst()
                    .orElseThrow();
            largest.remove(oldest);
            total -= oldest.bytes();
            gone.add(oldest.name());
        }
        return List.copyOf(gone);
    }

    private static long bytes(final List<Held> series) {
        return series.stream().mapToLong(Held::bytes).sum();
    }
}
