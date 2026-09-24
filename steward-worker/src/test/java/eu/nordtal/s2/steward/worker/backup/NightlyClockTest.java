package eu.nordtal.s2.steward.worker.backup;

import eu.nordtal.s2.common.update.UpdateDirectory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
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

    @Test
    @DisplayName("the next backup is a moment on this host, whoever is asking and from where")
    void theNextBackupIsThisHostsMoment() {
        // The interface offers "tonight" for a run that stops servers, and it used to work that
        // out in the browser's time zone. An admin one hour east of the host therefore scheduled
        // 04:00 their time - 03:00 here on a good day, 05:00 on a bad one, which is AFTER the
        // nightly backup and therefore exactly the collision the offer exists to avoid.
        final ZonedDateTime beforeIt = ZonedDateTime.of(2026, 9, 13, 3, 0, 0, 0, BERLIN);
        final ZonedDateTime afterIt = ZonedDateTime.of(2026, 9, 13, 5, 0, 0, 0, BERLIN);

        assertEquals(ZonedDateTime.of(2026, 9, 13, 4, 45, 0, 0, BERLIN),
                NightlyClock.next("04:45", BERLIN, beforeIt).orElseThrow());
        assertEquals(ZonedDateTime.of(2026, 9, 14, 4, 45, 0, 0, BERLIN),
                NightlyClock.next("04:45", BERLIN, afterIt).orElseThrow(),
                "past today's, so it is tomorrow's - never a moment already gone");

        // Asked from somewhere else entirely: the answer is the same instant, expressed here.
        assertEquals(ZonedDateTime.of(2026, 9, 13, 4, 45, 0, 0, BERLIN),
                NightlyClock.next("04:45", BERLIN,
                        beforeIt.withZoneSameInstant(ZoneId.of("Pacific/Auckland"))).orElseThrow());

        assertTrue(NightlyClock.next("", BERLIN, beforeIt).isEmpty(), "empty means no backup");
        assertTrue(NightlyClock.next("quarter to five", BERLIN, beforeIt).isEmpty());
    }

    @Test
    @DisplayName("a night that is not one of the chosen weekdays is skipped, not shortened")
    void theClockSkipsADayItWasNotAskedToRunOn() {
        // Till asked for weekdays beside the time (steward/95, his review of 2026-09-18). 09-13 is
        // a Sunday, so a schedule of Monday and Thursday has its next firing tomorrow morning.
        final List<String> monAndThu = List.of("MONDAY", "THURSDAY");
        final ZonedDateTime sundayNight = ZonedDateTime.of(2026, 9, 13, 3, 0, 0, 0, BERLIN);

        assertEquals(ZonedDateTime.of(2026, 9, 14, 4, 45, 0, 0, BERLIN),
                NightlyClock.next("04:45", monAndThu, BERLIN, sundayNight).orElseThrow(),
                "Sunday is not one of the two, so the next firing is Monday's");

        // Monday, and today's has already gone: the next one is Thursday and not tomorrow.
        assertEquals(ZonedDateTime.of(2026, 9, 17, 4, 45, 0, 0, BERLIN),
                NightlyClock.next("04:45", monAndThu, BERLIN,
                        ZonedDateTime.of(2026, 9, 14, 5, 0, 0, 0, BERLIN)).orElseThrow(),
                "three days are skipped whole - a day-of-week schedule that only ever adds one day"
                        + " is a daily backup wearing a different config key");

        // And the day it IS on still behaves like the daily one: before the time, it is today's.
        assertEquals(ZonedDateTime.of(2026, 9, 14, 4, 45, 0, 0, BERLIN),
                NightlyClock.next("04:45", monAndThu, BERLIN,
                        ZonedDateTime.of(2026, 9, 14, 3, 0, 0, 0, BERLIN)).orElseThrow());
    }

    @Test
    @DisplayName("no weekday at all is no nightly backup, exactly as an empty time is")
    void noWeekdayMeansNoBackup() {
        final ZonedDateTime sundayNight = ZonedDateTime.of(2026, 9, 13, 3, 0, 0, 0, BERLIN);
        assertTrue(NightlyClock.next("04:45", List.of(), BERLIN, sundayNight).isEmpty(),
                "a schedule with no day in it cannot fire, and saying so is better than quietly"
                        + " running every night because the list looked unset");
        assertTrue(NightlyClock.from(noDirectory(), "04:45", List.of(), BERLIN).isEmpty());
    }

    @Test
    @DisplayName("a weekday nobody can read is dropped, and the readable ones still schedule")
    void anUnreadableWeekdayIsDropped() {
        final ZonedDateTime sundayNight = ZonedDateTime.of(2026, 9, 13, 3, 0, 0, 0, BERLIN);
        assertEquals(ZonedDateTime.of(2026, 9, 17, 4, 45, 0, 0, BERLIN),
                NightlyClock.next("04:45", List.of("thursday", " Thu ", "whenever"), BERLIN,
                        sundayNight).orElseThrow(),
                "case and spacing are not the operator's problem; a word that is not a weekday is"
                        + " logged and ignored rather than taking the whole schedule with it");
    }

    @Test
    @DisplayName("no list at all is every night - the shape every deployment before this had")
    void noListIsEveryNight() {
        final ZonedDateTime sundayNight = ZonedDateTime.of(2026, 9, 13, 3, 0, 0, 0, BERLIN);
        assertEquals(NightlyClock.next("04:45", BERLIN, sundayNight),
                NightlyClock.next("04:45", null, BERLIN, sundayNight),
                "a config file written before backup.days existed has to keep running nightly");
    }

    @Test
    @DisplayName("a backup refused because another run is open is asked for again, not lost")
    void aBusyNetworkIsAskedAgain() {
        final ZonedDateTime due = ZonedDateTime.of(2026, 9, 13, 4, 45, 0, 0, BERLIN);
        final NightlyClock clock = NightlyClock.from(busy(), "04:45", BERLIN).orElseThrow();

        assertEquals(NightlyClock.RETRY, clock.fire(due, due), "another run is open: ask again soon");
        assertEquals(NightlyClock.RETRY, clock.fire(due, due.plusMinutes(90)));

        final ZonedDateTime tooLate = due.plus(NightlyClock.PATIENCE);
        assertEquals(clock.untilNext(tooLate), clock.fire(due, tooLate),
                "past its patience it waits for tomorrow rather than running into the morning");
    }

    /** Every submit is refused, as the directory refuses one while another run is open. */
    private static UpdateDirectory busy() {
        final eu.nordtal.s2.common.update.UpdateRequest open = new eu.nordtal.s2.common.update.UpdateRequest(
                9L, eu.nordtal.s2.common.update.UpdateKind.UPDATE, eu.nordtal.s2.common.update.UpdateStatus.RUNNING,
                eu.nordtal.s2.common.update.UpdateSource.DISCORD, "a", java.time.Instant.now(),
                java.time.Instant.now(), null, null, null);
        return (UpdateDirectory) java.lang.reflect.Proxy.newProxyInstance(
                UpdateDirectory.class.getClassLoader(),
                new Class<?>[]{UpdateDirectory.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("submit")) {
                        throw eu.nordtal.s2.common.update.RunRefused.runOpen(open);
                    }
                    throw new AssertionError("the clock asked the database: " + method.getName());
                });
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
