package eu.nordtal.s2.smp.farm;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * "Once a day at HH:mm", against fixed clocks rather than against the wall.
 *
 * <p>The arithmetic is small and the consequence of getting it wrong is not: a reset that computes
 * the wrong delay misfires once a day, at a time of day chosen precisely because nobody is watching.
 */
class DailyScheduleTest {

    @Test
    void laterTodayIsTheDistanceToIt() {
        final DailySchedule schedule = DailySchedule.parse("04:00");

        assertEquals(Duration.ofHours(3), schedule.until(LocalTime.of(1, 0)));
        assertEquals(Duration.ofMinutes(30), schedule.until(LocalTime.of(3, 30)));
    }

    @Test
    void alreadyPastTodayRollsToTomorrow() {
        final DailySchedule schedule = DailySchedule.parse("04:00");

        assertEquals(Duration.ofHours(23), schedule.until(LocalTime.of(5, 0)));
        assertEquals(Duration.ofHours(20), schedule.until(LocalTime.of(8, 0)));
    }

    /**
     * Exactly on the hour is a full day, never zero - the caller schedules the next run the moment
     * the current one finishes, and a zero would put it straight back into the same second forever.
     */
    @Test
    void exactlyOnTimeIsAFullDayAndNotAnInstantRepeat() {
        assertEquals(Duration.ofDays(1), DailySchedule.parse("04:00").until(LocalTime.of(4, 0)));
    }

    @Test
    void aScheduleNobodyCanParseStopsTheLoad() {
        assertThrows(IllegalArgumentException.class, () -> DailySchedule.parse("4am"));
        assertThrows(IllegalArgumentException.class, () -> DailySchedule.parse("25:00"));
        assertThrows(IllegalArgumentException.class, () -> DailySchedule.parse(""));
        assertThrows(IllegalArgumentException.class, () -> DailySchedule.parse(null));
    }

    @Test
    void aLeadingZeroIsAnHourAndNotAnOctalLiteral() {
        assertEquals(LocalTime.of(8, 5), DailySchedule.parse("08:05").at());
    }

    @Test
    void warningsComeOutLargestFirstWithoutDuplicatesOrNonsense() {
        assertEquals(
                List.of(Duration.ofMinutes(30), Duration.ofMinutes(10), Duration.ofMinutes(5),
                        Duration.ofMinutes(1)),
                DailySchedule.warningsBefore(List.of(5, 30, 1, 10, 5, 0, -3)));
    }
}
