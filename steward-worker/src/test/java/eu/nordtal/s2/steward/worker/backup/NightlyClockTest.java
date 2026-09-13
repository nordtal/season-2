package eu.nordtal.s2.steward.worker.backup;

import eu.nordtal.s2.common.update.UpdateDirectory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The arithmetic of the nightly clock, without a database and without waiting for 04:45.
 *
 * <p>Every one of these is a bug that has already happened once, in the clock this replaces.</p>
 */
class NightlyClockTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    private NightlyClock at(final String time) {
        final Optional<NightlyClock> clock =
                NightlyClock.from(noDirectory(), time, BERLIN);
        assertTrue(clock.isPresent(), time + " should have been accepted");
        return clock.get();
    }

    @Test
    @DisplayName("a moment before the hour waits the fraction, and never zero")
    void aFractionIsStillAWait() {
        // The predecessor floored this to zero seconds, fired while the target was still ahead,
        // re-armed for another fraction, and wrote one backup request per pass.
        final Duration until = at("04:45").untilNext(
                ZonedDateTime.of(2026, 9, 13, 4, 44, 59, 600_000_000, BERLIN));

        assertTrue(until.toMillis() > 0 && until.toMillis() < 1000, until.toString());
    }

    @Test
    @DisplayName("firing exactly on the second arms for tomorrow, not for right now")
    void exactlyOnTimeMeansTomorrow() {
        final Duration until = at("04:45").untilNext(
                ZonedDateTime.of(2026, 9, 13, 4, 45, 0, 0, BERLIN));

        assertEquals(Duration.ofHours(24), until,
                "a wait of zero is the loop this class exists not to have");
    }

    @Test
    @DisplayName("later in the day means tomorrow at the same local time")
    void laterMeansTomorrow() {
        final ZonedDateTime now = ZonedDateTime.of(2026, 9, 13, 5, 0, 0, 0, BERLIN);
        final Duration until = at("04:45").untilNext(now);

        assertEquals(4, now.plus(until).getHour());
        assertEquals(45, now.plus(until).getMinute());
        assertEquals(14, now.plus(until).getDayOfMonth());
    }

    @Test
    @DisplayName("the hour the clocks go forward is still 04:45, and it is 23 hours away")
    void survivesTheSpringForward() {
        // Europe/Berlin loses an hour on the last Sunday in March at 02:00. A clock that counted
        // in fixed hours would fire at 05:45 that morning and stay an hour off until October.
        final ZonedDateTime beforeTheChange =
                ZonedDateTime.of(2027, 3, 27, 5, 45, 0, 0, BERLIN);
        final Duration until = at("04:45").untilNext(beforeTheChange);

        assertEquals(4, beforeTheChange.plus(until).getHour());
        assertEquals(45, beforeTheChange.plus(until).getMinute());
        // TWENTY-TWO, not twenty-three, and the difference is the point. The wall clock moves 23
        // hours (05:45 Saturday to 04:45 Sunday); real time passes 22, because 02:00 to 03:00 does
        // not exist that night. A clock that scheduled a fixed 24 hours would drift an hour and
        // stay there until October; one that scheduled the wall-clock difference would fire an
        // hour late. This schedules the real duration to the right wall-clock moment.
        assertEquals(Duration.ofHours(22), until,
                "the wait is the real time to the next 04:45, not the hours on the face of a clock");
    }

    @Test
    @DisplayName("an unreadable time is refused, and does not quietly become midnight")
    void nonsenseIsRefused() {
        assertTrue(NightlyClock.from(noDirectory(), "quarter to five", BERLIN).isEmpty());
        assertTrue(NightlyClock.from(noDirectory(), "25:00", BERLIN).isEmpty());
        assertTrue(NightlyClock.from(noDirectory(), "", BERLIN).isEmpty(), "empty means off");
        assertTrue(NightlyClock.from(noDirectory(), null, BERLIN).isEmpty());
    }

    /** Nothing here ever submits, so the directory is a proxy that refuses every call. */
    private static UpdateDirectory noDirectory() {
        return (UpdateDirectory) java.lang.reflect.Proxy.newProxyInstance(
                UpdateDirectory.class.getClassLoader(),
                new Class<?>[]{UpdateDirectory.class},
                (proxy, method, args) -> {
                    throw new AssertionError("the clock asked the database: " + method.getName());
                });
    }
}
