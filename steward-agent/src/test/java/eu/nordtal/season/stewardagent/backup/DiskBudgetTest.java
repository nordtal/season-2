package eu.nordtal.season.stewardagent.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The budget alone, on plain values: which archives go when the backups outgrow their share of the disk. */
class DiskBudgetTest {

    private static final String SMP = "nordtal-s2_mc-smp";
    private static final String PLUGINS = "nordtal-s2_mc-smp-plugins";

    @Test
    void underTheBudgetNothingGoes() {
        assertEquals(List.of(), new DiskBudget(100).over(List.of(held(SMP, 1, 40, true), held(SMP, 2, 40, true))));
    }

    @Test
    void theOldestArchiveOfTheLargestSeriesGoesFirstUntilTheRestFits() {
        final List<String> gone = new DiskBudget(100)
                .over(List.of(
                        held(SMP, 1, 40, true),
                        held(SMP, 2, 40, true),
                        held(SMP, 3, 40, true),
                        held(PLUGINS, 1, 5, true),
                        held(PLUGINS, 2, 5, true)));

        assertEquals(List.of(SMP + "-1"), gone, "130 bytes for 100: smp is the largest, and without its oldest 90 fit");
    }

    @Test
    void theNewestVerifiedArchiveOfASeriesStaysEvenWhenTheBudgetIsStillExceeded() {
        final List<String> gone = new DiskBudget(10)
                .over(List.of(
                        held(SMP, 1, 40, true),
                        held(SMP, 2, 40, true),
                        // Newer, but its stop was not verified, so the one before it is what a restore trusts.
                        held(SMP, 3, 40, false),
                        held(PLUGINS, 1, 5, true)));

        assertEquals(List.of(SMP + "-1", SMP + "-3"), gone);
    }

    @Test
    void aSeriesWithNoVerifiedArchiveKeepsItsNewest() {
        final List<String> gone = new DiskBudget(0).over(List.of(held(SMP, 1, 40, false), held(SMP, 2, 40, false)));

        assertEquals(List.of(SMP + "-1"), gone);
    }

    @Test
    void theBudgetIsItsShareOfTheFilesystem() {
        assertEquals(new DiskBudget(60_000_000_000L), DiskBudget.of(200_000_000_000L, 30));
    }

    private static DiskBudget.Held held(final String series, final int day, final long bytes, final boolean verified) {
        return new DiskBudget.Held(
                series + "-" + day, series, Instant.parse("2026-10-0" + day + "T02:45:00Z"), bytes, verified);
    }
}
