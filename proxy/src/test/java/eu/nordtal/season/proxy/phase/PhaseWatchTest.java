package eu.nordtal.season.proxy.phase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.SeasonPhase;
import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.database.phase.DateChange;
import eu.nordtal.season.database.phase.PhaseChange;
import eu.nordtal.season.database.phase.PhaseDirectory;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link PhaseWatch} against a fake {@link PhaseDirectory}.
 *
 * A failed read keeps the last known phase, and a never-read phase is {@code MAINTENANCE}, never an open one.
 */
class PhaseWatchTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(PhaseWatchTest.class);

    private FakeDirectory directory;
    private List<String> changes;
    private PhaseWatch watch;

    @BeforeEach
    void freshWatch() {
        directory = new FakeDirectory(SeasonPhase.PRE_EVENT);
        changes = new ArrayList<>();
        watch = new PhaseWatch(directory, LOGGER, (previous, current) -> changes.add(previous + " -> " + current));
    }

    @Test
    void aProcessThatHasNeverReadTheRowAssumesMaintenance() {
        assertFalse(watch.everRead());
        assertEquals(
                SeasonPhase.MAINTENANCE, watch.lastKnown(), "the state that lets nobody in is the safe one to guess");
    }

    @Test
    void theFirstSuccessfulReadIsReportedAsALearntPhaseNotAsAChange() {
        assertTrue(watch.refresh());

        assertTrue(watch.everRead());
        assertEquals(SeasonPhase.PRE_EVENT, watch.lastKnown());
        assertEquals(
                List.of("null -> PRE_EVENT"),
                changes,
                "the callback gets a null previous so a listener can tell 'we just learned it' from "
                        + "'it changed under us'");
    }

    @Test
    void readingTheSameValueBackIsNotAChange() {
        watch.refresh();
        changes.clear();

        watch.refresh();
        watch.refresh();

        assertTrue(changes.isEmpty(), "the poll runs every 30 seconds; it must not log a change each time");
        assertEquals(3, directory.reads, "but it does re-read every time - nothing is cached as truth");
    }

    @Test
    void aChangeIsObservedAndReportedOnce() {
        watch.refresh();
        directory.phase = SeasonPhase.SMP;

        watch.refresh();
        watch.refresh();

        assertEquals(SeasonPhase.SMP, watch.lastKnown());
        assertEquals(List.of("null -> PRE_EVENT", "PRE_EVENT -> SMP"), changes);
    }

    @Test
    void aFailedReadKeepsTheLastKnownPhaseRatherThanGuessing() {
        watch.refresh();
        directory.phase = SeasonPhase.SMP;
        watch.refresh();

        directory.failing = true;
        assertFalse(watch.refresh(), "a failed read says so");

        assertEquals(
                SeasonPhase.SMP, watch.lastKnown(), "a phase that cannot be read falls back to the last known one");
        assertTrue(watch.everRead());
    }

    @Test
    void aFailedFirstReadLeavesTheProcessOnMaintenance() {
        directory.failing = true;

        assertFalse(watch.refresh());

        assertFalse(watch.everRead());
        assertEquals(SeasonPhase.MAINTENANCE, watch.lastKnown(), "...and if there is no last known phase, MAINTENANCE");
    }

    @Test
    void theWatchRecoversWhenTheDatabaseComesBack() {
        watch.refresh();
        directory.failing = true;
        watch.refresh();

        directory.failing = false;
        directory.phase = SeasonPhase.MAINTENANCE;
        assertTrue(watch.refresh());

        assertEquals(SeasonPhase.MAINTENANCE, watch.lastKnown());
        assertEquals(
                List.of("null -> PRE_EVENT", "PRE_EVENT -> MAINTENANCE"),
                changes,
                "the outage produced no phantom change on the way in or out");
    }

    @Test
    void aListenerThatThrowsDoesNotUndoTheObservedPhase() {
        final PhaseWatch throwing = new PhaseWatch(directory, LOGGER, (previous, current) -> {
            throw new IllegalStateException("routing exploded");
        });

        assertTrue(throwing.refresh());
        assertEquals(
                SeasonPhase.PRE_EVENT,
                throwing.lastKnown(),
                "the phase is already swapped by the time a listener runs; a broken listener must "
                        + "not make the proxy forget what the row said");
    }

    /** Answers whatever it is told to, or throws. */
    private static final class FakeDirectory implements PhaseDirectory {

        @Override
        public java.util.Optional<java.time.Instant> launch() {
            // The opening date is only rendered in the MOTD; no test here is about it.
            return java.util.Optional.empty();
        }

        @Override
        public java.util.Optional<java.time.Instant> smpStart() {
            // Nothing in proxy reads it; it anchors purchases in the bot.
            return java.util.Optional.empty();
        }

        @Override
        public DateChange setLaunch(final java.time.Instant at, final Actor actor) {
            throw new UnsupportedOperationException("this fake only reads the row");
        }

        @Override
        public DateChange setSmpStart(final java.time.Instant at, final Actor actor) {
            throw new UnsupportedOperationException("this fake only reads the row");
        }

        private SeasonPhase phase;
        private boolean failing;
        private int reads;

        private FakeDirectory(final SeasonPhase phase) {
            this.phase = phase;
        }

        @Override
        public SeasonPhase currentPhase() {
            reads++;
            if (failing) {
                throw new IllegalStateException("the database is unreachable");
            }
            return phase;
        }

        @Override
        public PhaseChange switchPhase(final SeasonPhase phase, final Actor actor, final String reason) {
            throw new UnsupportedOperationException("PhaseWatch never writes");
        }
    }
}
