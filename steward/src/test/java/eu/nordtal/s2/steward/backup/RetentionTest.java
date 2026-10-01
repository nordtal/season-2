package eu.nordtal.s2.steward.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The retention decision, without a disk under it.
 *
 * A day collapses to its last run before the schedule counts days, so one busy day cannot eat the daily window.
 */
class RetentionTest {

    /** A Thursday, and deliberately mid-week: a policy that only works on Mondays is a real bug. */
    private static final Instant NOW = at("2026-09-17", "03:00");

    private static Instant at(final String day, final String time) {
        return LocalDateTime.of(LocalDate.parse(day), LocalTime.parse(time)).toInstant(ZoneOffset.UTC);
    }

    /** One archive of one day, named the way `TarSnapshots` names them. */
    private static Retention.Dated stamp(final String day, final String time) {
        return new Retention.Dated(day.replace("-", "") + "T" + time.replace(":", "") + "00Z", at(day, time));
    }

    private static List<String> deleted(final Retention policy, final List<Retention.Dated> all) {
        return policy.expired(all, NOW).stream().map(Retention.Dated::name).toList();
    }

    /** Daily runs, `days` of them, ending yesterday. */
    private static List<Retention.Dated> nightly(final int days) {
        final List<Retention.Dated> all = new ArrayList<>();
        for (int back = 1; back <= days; back++) {
            all.add(stamp(LocalDate.parse("2026-09-17").minusDays(back).toString(), "03:00"));
        }
        return all;
    }

    @Test
    void aDayOlderThanTheGracePeriodKeepsItsLastRunAndLosesTheOthers() {
        final Retention policy = new Retention(30, 0, 0, 3);
        final List<Retention.Dated> threeOnOneDay =
                List.of(stamp("2026-09-10", "03:00"), stamp("2026-09-10", "11:30"), stamp("2026-09-10", "21:15"));

        assertEquals(
                List.of("20260910T030000Z", "20260910T113000Z"),
                deleted(policy, threeOnOneDay),
                "the last run of the day is the one that survives, and the one"
                        + " that is likeliest to hold what the earlier ones were taken before");
    }

    @Test
    void todaysExtraRunsAreLeftAloneUntilTheGracePeriodIsOver() {
        final Retention policy = new Retention(30, 0, 0, 3);
        final List<Retention.Dated> today =
                List.of(stamp("2026-09-16", "03:00"), stamp("2026-09-16", "14:00"), stamp("2026-09-17", "02:00"));

        assertEquals(
                List.of(),
                deleted(policy, today),
                "somebody who took a second backup before touching something wants both of them"
                        + " while they are still touching it; the sweep waits the grace out");
    }

    @Test
    void theNewestDaysSurviveInFullAndTheScheduleCountsDaysRatherThanFiles() {
        final Retention policy = new Retention(7, 0, 0, 0);
        final List<Retention.Dated> all = new ArrayList<>(nightly(10));
        // Two more runs on the same day, which a file-counting window would spend a whole day on.
        all.add(stamp("2026-09-16", "12:00"));
        all.add(stamp("2026-09-16", "18:00"));

        final List<String> gone = deleted(policy, all);
        assertTrue(gone.contains("20260907T030000Z"), "the eighth day back is outside the window");
        assertTrue(gone.contains("20260908T030000Z"), "and so is the ninth");
        assertTrue(
                gone.contains("20260916T120000Z") && gone.contains("20260916T030000Z"),
                "the extra runs of a settled day go because the day keeps one, not because the" + " window is full");
        assertTrue(
                gone.stream().noneMatch(name -> name.startsWith("20260910")),
                "seven days back is still inside a seven-day window");
    }

    @Test
    void oneBackupAWeekSurvivesPastTheDailyWindowForAsManyWeeksAsAsked() {
        // Two daily, three weekly: everything older than two days is judged by its week.
        final Retention policy = new Retention(2, 3, 0, 0);
        final List<String> gone = deleted(policy, nightly(30));

        // The newest day of each of the three newest ISO weeks survives, and the fourth week does not.
        assertTrue(gone.stream().noneMatch(name -> name.startsWith("20260916")), "yesterday");
        assertTrue(gone.stream().noneMatch(name -> name.startsWith("20260915")), "the day before");
        assertTrue(
                gone.stream().noneMatch(name -> name.startsWith("20260913")),
                "the newest day of the week before last - a Sunday, which is what makes this a"
                        + " test of the week and not of the day");
        assertTrue(gone.contains("20260912T030000Z"), "the rest of that week goes; the week keeps one");
        assertTrue(gone.stream().noneMatch(name -> name.startsWith("20260906")), "and one the week before");
        assertTrue(
                gone.contains("20260830T030000Z"),
                "the fourth week back is outside a three-week window, Sunday or not");
    }

    @Test
    void oneBackupAMonthSurvivesPastTheWeeklyWindow() {
        final Retention policy = new Retention(1, 1, 3, 0);
        final List<Retention.Dated> all = new ArrayList<>();
        for (final String day : List.of(
                "2026-09-16", "2026-09-01", "2026-08-31", "2026-08-02", "2026-07-30", "2026-06-29", "2026-05-28")) {
            all.add(stamp(day, "03:00"));
        }

        final List<String> gone = deleted(policy, all);
        assertTrue(
                gone.stream().noneMatch(name -> name.startsWith("20260831")), "the newest August copy is August's one");
        assertTrue(gone.contains("20260802T030000Z"), "the rest of August is not");
        assertTrue(gone.stream().noneMatch(name -> name.startsWith("20260730")), "July keeps one");
        assertTrue(
                gone.contains("20260629T030000Z") && gone.contains("20260528T030000Z"),
                "three months back is three months, so June and May go");
    }

    @Test
    void aPolicyThatWouldKeepNothingIsRefusedNotObeyed() {
        // The likeliest way to arrive at all-zero is an unset config read as 0, and obeying it deletes every backup.
        assertThrows(IllegalArgumentException.class, () -> new Retention(0, 0, 0, 3));
        assertThrows(IllegalArgumentException.class, () -> new Retention(-1, 2, 2, 3));
        assertThrows(IllegalArgumentException.class, () -> new Retention(2, 2, 2, -1));
    }
}
