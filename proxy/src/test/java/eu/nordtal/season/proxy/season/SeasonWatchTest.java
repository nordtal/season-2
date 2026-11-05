package eu.nordtal.season.proxy.season;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.season.SeasonMode;
import eu.nordtal.season.common.season.SeasonState;
import eu.nordtal.season.common.time.ManualScheduler;
import eu.nordtal.season.database.season.MaintenanceSpan;
import eu.nordtal.season.database.season.SeasonChange;
import eu.nordtal.season.database.season.SeasonDirectory;
import eu.nordtal.season.proxy.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link SeasonWatch} against a fake {@link SeasonDirectory} and a scheduler run by hand.
 *
 * A failed read keeps the last known state, and a state that was never read is {@link SeasonState#UNREAD}.
 */
class SeasonWatchTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(SeasonWatchTest.class);
    private static final Instant NOW = Instant.parse("2026-11-05T12:00:00Z");
    private static final Instant LAUNCH = NOW.plus(Duration.ofHours(2));
    private static final SeasonState OPEN = new SeasonState(NOW.minus(Duration.ofDays(1)), false, SeasonMode.SMP);

    private final MutableClock clock = new MutableClock(NOW);
    private final ManualScheduler scheduler = new ManualScheduler();
    private final FakeDirectory directory = new FakeDirectory(OPEN);
    private final List<String> changes = new ArrayList<>();
    private final SeasonWatch watch = new SeasonWatch(
            directory, scheduler, clock, LOGGER, (previous, current) -> changes.add(previous + " -> " + current));

    @Test
    void aProcessThatHasNeverReadTheRowAssumesMaintenance() {
        assertFalse(watch.everRead());
        assertEquals(SeasonState.UNREAD, watch.state(), "the state that holds everybody is the safe one to guess");
        assertTrue(watch.state().maintenance());
    }

    @Test
    void theFirstReadIsReportedWithANullPrevious() {
        assertTrue(watch.refresh());

        assertTrue(watch.everRead());
        assertEquals(OPEN, watch.state());
        assertEquals(List.of("null -> " + OPEN), changes);
    }

    @Test
    void readingTheSameValueBackIsNotAChange() {
        watch.refresh();
        changes.clear();

        watch.refresh();
        watch.refresh();

        assertTrue(changes.isEmpty(), "the poll must not report a change each time");
        assertEquals(3, directory.reads, "but it does re-read every time");
    }

    @Test
    void aChangeIsObservedAndReportedOnce() {
        watch.refresh();
        final SeasonState maintenance = OPEN.withMaintenance(true);
        directory.state = maintenance;

        watch.refresh();
        watch.refresh();

        assertEquals(maintenance, watch.state());
        assertEquals(List.of("null -> " + OPEN, OPEN + " -> " + maintenance), changes);
    }

    @Test
    void aFailedReadKeepsTheLastKnownState() {
        watch.refresh();
        directory.failing = true;

        assertFalse(watch.refresh(), "a failed read says so");
        assertEquals(OPEN, watch.state());
        assertTrue(watch.everRead());
    }

    @Test
    void aFailedFirstReadLeavesTheProcessOnTheUnreadState() {
        directory.failing = true;

        assertFalse(watch.refresh());
        assertFalse(watch.everRead());
        assertEquals(SeasonState.UNREAD, watch.state());
    }

    @Test
    void aListenerThatThrowsDoesNotUndoTheObservedState() {
        final SeasonWatch throwing = new SeasonWatch(directory, scheduler, clock, LOGGER, (previous, current) -> {
            throw new IllegalStateException("routing exploded");
        });

        assertTrue(throwing.refresh());
        assertEquals(OPEN, throwing.state());
    }

    // the launch date

    @Test
    void aLaunchDateAheadSetsATimerForTheLaunchInstant() {
        directory.state = OPEN.withLaunch(LAUNCH);

        watch.refresh();

        assertEquals(LAUNCH, watch.armedFor());
        assertEquals(1, scheduler.pending().size());
        assertEquals(Duration.ofHours(2), scheduler.pending().getFirst().delay());
    }

    @Test
    void theTimerReReadsAtTheLaunchAndReportsTheOpeningWithoutANotify() {
        final SeasonState countdown = OPEN.withLaunch(LAUNCH);
        directory.state = countdown;
        watch.refresh();
        changes.clear();
        final int reads = directory.reads;

        clock.advance(Duration.ofHours(2));
        scheduler.runPending();

        assertEquals(reads + 1, directory.reads, "the timer reads the row again");
        assertEquals(
                List.of(countdown + " -> " + countdown),
                changes,
                "the row did not change, yet the opening did, so the listener hears of it");
        assertNull(watch.armedFor(), "a launch in the past needs no timer");
        assertTrue(scheduler.pending().isEmpty());
    }

    @Test
    void aTimerThatFiresEarlyIsSetAgain() {
        directory.state = OPEN.withLaunch(LAUNCH);
        watch.refresh();
        changes.clear();

        clock.advance(Duration.ofHours(1));
        scheduler.runPending();

        assertTrue(changes.isEmpty(), "nothing opened yet");
        assertEquals(LAUNCH, watch.armedFor());
        assertEquals(Duration.ofHours(1), scheduler.pending().getFirst().delay());
    }

    @Test
    void aMovedLaunchDateCancelsTheOldTimer() {
        directory.state = OPEN.withLaunch(LAUNCH);
        watch.refresh();
        final Instant later = LAUNCH.plus(Duration.ofDays(1));
        directory.state = OPEN.withLaunch(later);

        watch.refresh();

        assertEquals(later, watch.armedFor());
        assertEquals(1, scheduler.pending().size(), "the timer of the old date is gone");
        assertEquals(Duration.ofHours(26), scheduler.pending().getFirst().delay());
    }

    @Test
    void closingTheSeasonCancelsTheTimer() {
        directory.state = OPEN.withLaunch(LAUNCH);
        watch.refresh();
        directory.state = OPEN.withLaunch(null);

        watch.refresh();

        assertNull(watch.armedFor());
        assertTrue(scheduler.pending().isEmpty());
    }

    @Test
    void theSameDateReadAgainKeepsOneTimer() {
        directory.state = OPEN.withLaunch(LAUNCH);
        watch.refresh();
        watch.refresh();

        assertEquals(1, scheduler.pending().size());
    }

    /** Answers whatever it is told to, or throws. */
    private static final class FakeDirectory implements SeasonDirectory {

        private SeasonState state;
        private boolean failing;
        private int reads;

        private FakeDirectory(final SeasonState state) {
            this.state = state;
        }

        @Override
        public SeasonState state() {
            reads++;
            if (failing) {
                throw new IllegalStateException("the database is unreachable");
            }
            return state;
        }

        @Override
        public int anchoredGrants() {
            throw new UnsupportedOperationException("SeasonWatch reads the state only");
        }

        @Override
        public Optional<MaintenanceSpan> lastMaintenance() {
            throw new UnsupportedOperationException("SeasonWatch reads the state only");
        }

        @Override
        public SeasonChange setLaunch(final Instant at, final Actor actor, final @Nullable String reason) {
            throw new UnsupportedOperationException("SeasonWatch never writes");
        }

        @Override
        public SeasonChange launchNow(final Actor actor, final @Nullable String reason) {
            throw new UnsupportedOperationException("SeasonWatch never writes");
        }

        @Override
        public SeasonChange closeSeason(final Actor actor, final @Nullable String reason) {
            throw new UnsupportedOperationException("SeasonWatch never writes");
        }

        @Override
        public SeasonChange setMaintenance(final boolean on, final Actor actor, final @Nullable String reason) {
            throw new UnsupportedOperationException("SeasonWatch never writes");
        }

        @Override
        public SeasonChange setMode(final SeasonMode mode, final Actor actor, final @Nullable String reason) {
            throw new UnsupportedOperationException("SeasonWatch never writes");
        }
    }
}
