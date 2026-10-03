package eu.nordtal.s2.steward.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.database.update.UpdateDirectory;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The arithmetic of the nightly clock, without a database and without waiting for 04:45. */
class NightlyClockTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    private NightlyClock at(final String time) {
        final Optional<NightlyClock> clock = NightlyClock.from(noDirectory(), time, Clock.system(BERLIN));
        assertTrue(clock.isPresent(), time + " should have been accepted");
        return clock.get();
    }

    @Test
    void aMomentBeforeTheHourWaitsTheFractionAndNeverZero() {
        // Flooring this to zero would fire while the target is still ahead, re-arm, and write one request per pass.
        final Duration until = at("04:45").untilNext(ZonedDateTime.of(2026, 9, 13, 4, 44, 59, 600_000_000, BERLIN));

        assertTrue(until.toMillis() > 0 && until.toMillis() < 1000, until.toString());
    }

    @Test
    void firingExactlyOnTheSecondArmsForTomorrowNotForRightNow() {
        final Duration until = at("04:45").untilNext(ZonedDateTime.of(2026, 9, 13, 4, 45, 0, 0, BERLIN));

        assertEquals(Duration.ofHours(24), until, "a wait of zero is the loop this class exists not to have");
    }

    @Test
    void laterInTheDayMeansTomorrowAtTheSameLocalTime() {
        final ZonedDateTime now = ZonedDateTime.of(2026, 9, 13, 5, 0, 0, 0, BERLIN);
        final Duration until = at("04:45").untilNext(now);

        assertEquals(4, now.plus(until).getHour());
        assertEquals(45, now.plus(until).getMinute());
        assertEquals(14, now.plus(until).getDayOfMonth());
    }

    @Test
    void theHourTheClocksGoForwardIsStill0445AndItIs23HoursAway() {
        // Europe/Berlin loses an hour in late March; a clock counting fixed hours would fire an hour off until October.
        final ZonedDateTime beforeTheChange = ZonedDateTime.of(2027, 3, 27, 5, 45, 0, 0, BERLIN);
        final Duration until = at("04:45").untilNext(beforeTheChange);

        assertEquals(4, beforeTheChange.plus(until).getHour());
        assertEquals(45, beforeTheChange.plus(until).getMinute());
        // Twenty-two, not twenty-three: the wall clock moves 23 hours, but 02:00-03:00 does not exist that night.
        assertEquals(
                Duration.ofHours(22),
                until,
                "the wait is the real time to the next 04:45, not the hours on the face of a clock");
    }

    @Test
    void anUnreadableTimeIsRefusedAndDoesNotQuietlyBecomeMidnight() {
        assertTrue(NightlyClock.from(noDirectory(), "quarter to five", Clock.system(BERLIN))
                .isEmpty());
        assertTrue(
                NightlyClock.from(noDirectory(), "25:00", Clock.system(BERLIN)).isEmpty());
        assertTrue(NightlyClock.from(noDirectory(), "", Clock.system(BERLIN)).isEmpty(), "empty means off");
        assertTrue(NightlyClock.from(noDirectory(), null, Clock.system(BERLIN)).isEmpty());
    }

    @Test
    void theNextBackupIsAMomentOnThisHostWhoeverIsAskingAndFromWhere() {
        // "Tonight" must be worked out in the host's time zone, or an admin east of it can schedule AFTER the backup.
        final ZonedDateTime beforeIt = ZonedDateTime.of(2026, 9, 13, 3, 0, 0, 0, BERLIN);
        final ZonedDateTime afterIt = ZonedDateTime.of(2026, 9, 13, 5, 0, 0, 0, BERLIN);

        assertEquals(
                ZonedDateTime.of(2026, 9, 13, 4, 45, 0, 0, BERLIN),
                NightlyClock.next("04:45", BERLIN, beforeIt).orElseThrow());
        assertEquals(
                ZonedDateTime.of(2026, 9, 14, 4, 45, 0, 0, BERLIN),
                NightlyClock.next("04:45", BERLIN, afterIt).orElseThrow(),
                "past today's, so it is tomorrow's - never a moment already gone");

        // Asked from somewhere else entirely: the answer is the same instant, expressed here.
        assertEquals(
                ZonedDateTime.of(2026, 9, 13, 4, 45, 0, 0, BERLIN),
                NightlyClock.next("04:45", BERLIN, beforeIt.withZoneSameInstant(ZoneId.of("Pacific/Auckland")))
                        .orElseThrow());

        assertTrue(NightlyClock.next("", BERLIN, beforeIt).isEmpty(), "empty means no backup");
        assertTrue(NightlyClock.next("quarter to five", BERLIN, beforeIt).isEmpty());
    }

    @Test
    void aNightThatIsNotOneOfTheChosenWeekdaysIsSkippedNotShortened() {
        // 09-13 is a Sunday, so a schedule of Monday and Thursday has its next firing tomorrow morning.
        final List<String> monAndThu = List.of("MONDAY", "THURSDAY");
        final ZonedDateTime sundayNight = ZonedDateTime.of(2026, 9, 13, 3, 0, 0, 0, BERLIN);

        assertEquals(
                ZonedDateTime.of(2026, 9, 14, 4, 45, 0, 0, BERLIN),
                NightlyClock.next("04:45", monAndThu, BERLIN, sundayNight).orElseThrow(),
                "Sunday is not one of the two, so the next firing is Monday's");

        // Monday, and today's has already gone: the next one is Thursday and not tomorrow.
        assertEquals(
                ZonedDateTime.of(2026, 9, 17, 4, 45, 0, 0, BERLIN),
                NightlyClock.next("04:45", monAndThu, BERLIN, ZonedDateTime.of(2026, 9, 14, 5, 0, 0, 0, BERLIN))
                        .orElseThrow(),
                "three days are skipped whole - a day-of-week schedule that only ever adds one day"
                        + " is a daily backup wearing a different config key");

        // And the day it IS on still behaves like the daily one: before the time, it is today's.
        assertEquals(
                ZonedDateTime.of(2026, 9, 14, 4, 45, 0, 0, BERLIN),
                NightlyClock.next("04:45", monAndThu, BERLIN, ZonedDateTime.of(2026, 9, 14, 3, 0, 0, 0, BERLIN))
                        .orElseThrow());
    }

    @Test
    void noWeekdayAtAllIsNoNightlyBackupExactlyAsAnEmptyTimeIs() {
        final ZonedDateTime sundayNight = ZonedDateTime.of(2026, 9, 13, 3, 0, 0, 0, BERLIN);
        assertTrue(
                NightlyClock.next("04:45", List.of(), BERLIN, sundayNight).isEmpty(),
                "a schedule with no day in it cannot fire, and saying so is better than quietly"
                        + " running every night because the list looked unset");
        assertTrue(NightlyClock.from(noDirectory(), "04:45", List.of(), Clock.system(BERLIN))
                .isEmpty());
    }

    @Test
    void aWeekdayNobodyCanReadIsDroppedAndTheReadableOnesStillSchedule() {
        final ZonedDateTime sundayNight = ZonedDateTime.of(2026, 9, 13, 3, 0, 0, 0, BERLIN);
        assertEquals(
                ZonedDateTime.of(2026, 9, 17, 4, 45, 0, 0, BERLIN),
                NightlyClock.next("04:45", List.of("thursday", " Thu ", "whenever"), BERLIN, sundayNight)
                        .orElseThrow(),
                "case and spacing are not the operator's problem; a word that is not a weekday is"
                        + " logged and ignored rather than taking the whole schedule with it");
    }

    @Test
    void noListAtAllIsEveryNightTheShapeEveryDeploymentBeforeThisHad() {
        final ZonedDateTime sundayNight = ZonedDateTime.of(2026, 9, 13, 3, 0, 0, 0, BERLIN);
        assertEquals(
                NightlyClock.next("04:45", BERLIN, sundayNight),
                NightlyClock.next("04:45", null, BERLIN, sundayNight),
                "a config file written before backup.days existed has to keep running nightly");
    }

    @Test
    void aBackupRefusedBecauseAnotherRunIsOpenIsAskedForAgainNotLost() {
        final ZonedDateTime due = ZonedDateTime.of(2026, 9, 13, 4, 45, 0, 0, BERLIN);
        final NightlyClock clock =
                NightlyClock.from(busy(), "04:45", Clock.system(BERLIN)).orElseThrow();

        assertEquals(NightlyClock.RETRY, clock.fire(due, due), "another run is open: ask again soon");
        assertEquals(NightlyClock.RETRY, clock.fire(due, due.plusMinutes(90)));

        final ZonedDateTime tooLate = due.plus(NightlyClock.PATIENCE);
        assertEquals(
                clock.untilNext(tooLate),
                clock.fire(due, tooLate),
                "past its patience it waits for tomorrow rather than running into the morning");
    }

    /** Every submit is refused, as the directory refuses one while another run is open. */
    private static UpdateDirectory busy() {
        final eu.nordtal.s2.database.update.UpdateRequest open = new eu.nordtal.s2.database.update.UpdateRequest(
                9L,
                eu.nordtal.s2.database.update.UpdateKind.UPDATE,
                eu.nordtal.s2.database.update.UpdateStatus.RUNNING,
                eu.nordtal.s2.common.id.Actor.HOST,
                java.time.Instant.now(),
                java.time.Instant.now(),
                null,
                List.of(),
                null,
                null,
                null);
        return (UpdateDirectory) java.lang.reflect.Proxy.newProxyInstance(
                UpdateDirectory.class.getClassLoader(),
                new Class<?>[] {UpdateDirectory.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("submit")) {
                        throw new eu.nordtal.s2.messages.Refused(
                                eu.nordtal.s2.database.update.UpdateRefusal.RUN_OPEN,
                                eu.nordtal.s2.database.DatabaseMessages.MESSAGES
                                        .update()
                                        .runOpen(open.id(), open.kind(), open.status()));
                    }
                    throw new AssertionError("the clock asked the database: " + method.getName());
                });
    }

    /** Nothing here ever submits, so the directory is a proxy that refuses every call. */
    private static UpdateDirectory noDirectory() {
        return (UpdateDirectory) java.lang.reflect.Proxy.newProxyInstance(
                UpdateDirectory.class.getClassLoader(),
                new Class<?>[] {UpdateDirectory.class},
                (proxy, method, args) -> {
                    throw new AssertionError("the clock asked the database: " + method.getName());
                });
    }

    @Test
    void theUpdateClockAsksForAnUpdateAsTheClockRatherThanAsAPerson() {
        final List<Object[]> submitted = new java.util.ArrayList<>();
        final UpdateDirectory recording = (UpdateDirectory) java.lang.reflect.Proxy.newProxyInstance(
                UpdateDirectory.class.getClassLoader(),
                new Class<?>[] {UpdateDirectory.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("submit")) {
                        submitted.add(args);
                        return new eu.nordtal.s2.database.update.UpdateRequest(
                                12L,
                                (eu.nordtal.s2.database.update.UpdateKind) args[0],
                                eu.nordtal.s2.database.update.UpdateStatus.PENDING,
                                (eu.nordtal.s2.common.id.Actor) args[1],
                                java.time.Instant.now(),
                                java.time.Instant.now(),
                                null,
                                List.of(),
                                null,
                                null,
                                null);
                    }
                    throw new AssertionError("the clock asked the database: " + method.getName());
                });
        final NightlyClock clock = NightlyClock.from(
                        recording, NightlyClock.Job.UPDATE, "03:30", List.of("SUN"), Clock.system(BERLIN))
                .orElseThrow();
        final ZonedDateTime sunday = ZonedDateTime.of(2026, 9, 27, 3, 30, 0, 0, BERLIN);

        final Duration next = clock.fire(sunday, sunday);

        assertEquals(1, submitted.size());
        assertEquals(eu.nordtal.s2.database.update.UpdateKind.UPDATE, submitted.get(0)[0]);
        assertEquals(eu.nordtal.s2.common.id.Actor.STEWARD, submitted.get(0)[1], "the clock, not a person");
        assertEquals(Duration.ofDays(7), next, "Sunday only: the next one is a week away");
    }

    @Test
    void anEmptyUpdateAtIsNoUpdateClockAtAllWhichIsTheDefault() {
        assertTrue(
                NightlyClock.from(noDirectory(), NightlyClock.Job.UPDATE, "", List.of("MONDAY"), Clock.system(BERLIN))
                        .isEmpty());
    }
}
