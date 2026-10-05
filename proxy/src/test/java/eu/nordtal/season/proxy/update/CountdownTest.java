package eu.nordtal.season.proxy.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.time.CountdownPlan;
import eu.nordtal.season.database.update.UpdateDirectory;
import eu.nordtal.season.database.update.UpdateKind;
import eu.nordtal.season.database.update.UpdateRequest;
import eu.nordtal.season.database.update.UpdateStatus;
import eu.nordtal.season.proxy.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * When a player is spoken to before the network goes down.
 *
 * The countdown is planned once, with a delay per beat, so a test can walk it against a clock.
 */
class CountdownTest {

    private static final Instant NOW = Instant.parse("2026-09-08T20:00:00Z");

    private final MutableClock clock = new MutableClock(NOW);
    private final Countdown countdown = new Countdown();

    /** A row due {@code in} from the clock's current instant. */
    private UpdateRequest due(final long id, final Duration in) {
        return new UpdateRequest(
                id,
                UpdateKind.UPDATE,
                UpdateStatus.RUNNING,
                Actor.HOST,
                NOW,
                clock.instant().plus(in),
                null,
                List.of(),
                NOW,
                null,
                null);
    }

    private List<CountdownPlan.Beat<Announcement>> beatsFor(final UpdateRequest request) {
        return countdown.beats(request.id(), request.untilDue(clock.instant())).orElseThrow();
    }

    @Test
    void aFullCountdownIsPlannedOnce() {
        // The real countdown constant, since a literal would assert nothing real.
        final List<CountdownPlan.Beat<Announcement>> beats = beatsFor(due(1L, UpdateDirectory.UPDATE_COUNTDOWN));

        assertEquals(
                3,
                kinds(beats, Announcement.Kind.COUNTDOWN).size(),
                "chat gets sixty, thirty and ten; a line every five seconds is how a warning"
                        + " becomes something people learn to ignore");
        assertEquals(List.of(60L, 30L, 10L), seconds(kinds(beats, Announcement.Kind.COUNTDOWN)));
        assertEquals(
                List.of(9L, 8L, 7L, 6L, 5L, 4L, 3L, 2L, 1L),
                seconds(kinds(beats, Announcement.Kind.TICK)),
                "ten is missing on purpose: it has a chat line, and that line draws the title of"
                        + " that second itself");
        assertEquals(1, kinds(beats, Announcement.Kind.NOW).size(), "and exactly one 'it is happening'");
        assertEquals(13, beats.size());
    }

    @Test
    void theThresholdsFitInsideTheCountdown() {
        // A threshold longer than the countdown drops silently.
        for (final long threshold : Countdown.CHAT_THRESHOLDS) {
            assertTrue(
                    threshold <= UpdateDirectory.UPDATE_COUNTDOWN.toSeconds(),
                    "a chat line at " + threshold + "s cannot be spoken in a "
                            + UpdateDirectory.UPDATE_COUNTDOWN.toSeconds() + "s countdown:"
                            + " the beat is planned for an instant that has already passed and"
                            + " is dropped without a word");
        }
        // A countdown of exactly that length speaks the whole set.
        assertEquals(
                Countdown.CHAT_THRESHOLDS,
                seconds(kinds(beatsFor(due(1L, UpdateDirectory.UPDATE_COUNTDOWN)), Announcement.Kind.COUNTDOWN)));
    }

    @Test
    void theNumberSpokenIsTheNumberLeft() {
        final List<CountdownPlan.Beat<Announcement>> beats = beatsFor(due(1L, Duration.ofSeconds(30)));

        for (final CountdownPlan.Beat<Announcement> beat : beats) {
            if (beat.said().kind() == Announcement.Kind.NOW) {
                assertEquals(Duration.ofSeconds(30), beat.delay(), "zero is at the end");
                continue;
            }
            assertEquals(
                    Duration.ofSeconds(30 - beat.said().seconds()),
                    beat.delay(),
                    beat.said() + " does not fire when its own number is true");
        }
    }

    @Test
    void theOddMillisecondsAreTheReasonThisIsNotSeconds() {
        // The countdown starts at now() + 30s on the database's clock; truncating to seconds shifts every beat.
        final List<CountdownPlan.Beat<Announcement>> beats = beatsFor(due(1L, Duration.ofMillis(29_640)));

        final CountdownPlan.Beat<Announcement> five = kinds(beats, Announcement.Kind.TICK).stream()
                .filter(beat -> beat.said().seconds() == 5L)
                .findFirst()
                .orElseThrow();
        assertEquals(
                Duration.ofMillis(24_640),
                five.delay(),
                "the '5' is shown 5.000 seconds before the servers go, not 5.640");
    }

    /**
     * A first line that survives the latency between steward's write and the proxy's read.
     *
     * Measured against the real constant less a fixed latency, in whole seconds as spoken.
     */
    @Test
    void theFirstLineIsNotLostToLatency() {
        final List<CountdownPlan.Beat<Announcement>> beats =
                beatsFor(due(1L, UpdateDirectory.UPDATE_COUNTDOWN.minusMillis(20)));

        assertEquals(
                Countdown.CHAT_THRESHOLDS,
                seconds(kinds(beats, Announcement.Kind.COUNTDOWN)),
                "the first line was dropped because the row took 20 ms to read");
        assertEquals(
                Duration.ZERO,
                kinds(beats, Announcement.Kind.COUNTDOWN).getFirst().delay(),
                "a beat whose instant has just passed is said now, not scheduled into the past");
        // Asserted through the ticks: a run missing its first chat line also logs twelve beats.
        assertEquals(List.of(9L, 8L, 7L, 6L, 5L, 4L, 3L, 2L, 1L), seconds(kinds(beats, Announcement.Kind.TICK)));
        assertEquals(Countdown.CHAT_THRESHOLDS.size() + 9 + 1, beats.size());
    }

    @Test
    void aCountdownJoinedLateDoesNotReplay() {
        // A proxy that comes up with seven seconds left must not say "30 seconds".
        final List<CountdownPlan.Beat<Announcement>> beats = beatsFor(due(1L, Duration.ofSeconds(7)));

        assertTrue(kinds(beats, Announcement.Kind.COUNTDOWN).isEmpty(), "both chat thresholds are behind us");
        assertEquals(List.of(7L, 6L, 5L, 4L, 3L, 2L, 1L), seconds(kinds(beats, Announcement.Kind.TICK)));
        assertEquals(
                Duration.ofSeconds(7),
                kinds(beats, Announcement.Kind.NOW).getFirst().delay());
    }

    @Test
    void zeroIsStillWorthOneLine() {
        final List<CountdownPlan.Beat<Announcement>> beats = beatsFor(due(1L, Duration.ZERO));

        assertEquals(1, beats.size());
        assertEquals(Announcement.Kind.NOW, beats.getFirst().said().kind());
        assertEquals(Duration.ZERO, beats.getFirst().delay());
    }

    @Test
    void theSecondSightingOfOneRowChangesNothing() {
        final UpdateRequest request = due(1L, Duration.ofSeconds(30));
        assertTrue(
                countdown.beats(request.id(), request.untilDue(clock.instant())).isPresent());

        clock.advance(Duration.ofSeconds(5));
        assertTrue(
                countdown.beats(request.id(), request.untilDue(clock.instant())).isEmpty(),
                "the beats are already on the scheduler; re-planning would double every line");
    }

    @Test
    void aNewRowStartsOver() {
        beatsFor(due(1L, Duration.ofSeconds(30)));

        final Optional<List<CountdownPlan.Beat<Announcement>>> second = countdown.beats(2L, Duration.ofSeconds(30));
        assertTrue(second.isPresent());
        assertEquals(2L, countdown.watching());
    }

    // the row stops counting down

    @Test
    void cancelledSaysCancelled() {
        beatsFor(due(1L, Duration.ofSeconds(30)));

        assertEquals(
                Announcement.Kind.CANCELLED,
                countdown.gone(UpdateStatus.CANCELLED).orElseThrow().kind());
    }

    @Test
    void reachingZeroIsNotCancelling() {
        // The row leaves the counting set at zero; reading that as a withdrawal would cancel every run.
        beatsFor(due(1L, Duration.ofSeconds(30)));

        assertEquals(
                Announcement.Kind.NOW,
                countdown.gone(UpdateStatus.RUNNING).orElseThrow().kind());
    }

    @Test
    void theZeroBeatIsNotRepeatedByThePoll() {
        // The zero beat and a poll just after it both say it is happening; players hear it once.
        beatsFor(due(1L, Duration.ofSeconds(30)));
        countdown.zeroReached();

        assertTrue(countdown.gone(UpdateStatus.RUNNING).isEmpty());
    }

    @Test
    void failedIsItsOwnAnswer() {
        beatsFor(due(1L, Duration.ofSeconds(30)));

        assertEquals(
                Announcement.Kind.FAILED,
                countdown.gone(UpdateStatus.FAILED).orElseThrow().kind());
    }

    @Test
    void aVanishedRowIsACancellation() {
        beatsFor(due(1L, Duration.ofSeconds(30)));

        assertEquals(
                Announcement.Kind.CANCELLED, countdown.gone(null).orElseThrow().kind());
    }

    @Test
    void goneWithoutACountdownIsSilent() {
        assertTrue(countdown.gone(UpdateStatus.CANCELLED).isEmpty());
        assertFalse(
                countdown.beats(1L, Duration.ofSeconds(30)).isEmpty(),
                "and the bookkeeping is clean enough for the next one");
    }

    @Test
    void oneTitlePerSecond() {
        // The chat line draws a title as well (ProxyRulesTest), so a tick on its second would collide with it.
        final List<CountdownPlan.Beat<Announcement>> beats = beatsFor(due(1L, Duration.ofSeconds(30)));

        final List<Long> chat = seconds(kinds(beats, Announcement.Kind.COUNTDOWN));
        for (final Long tick : seconds(kinds(beats, Announcement.Kind.TICK))) {
            assertFalse(
                    chat.contains(tick),
                    "second " + tick + " has both a chat line and a tick, and both draw a title"
                            + " now: they would be drawn over one another");
        }
    }

    // helpers

    private static List<CountdownPlan.Beat<Announcement>> kinds(
            final List<CountdownPlan.Beat<Announcement>> beats, final Announcement.Kind kind) {
        return beats.stream().filter(beat -> beat.said().kind() == kind).toList();
    }

    private static List<Long> seconds(final List<CountdownPlan.Beat<Announcement>> beats) {
        return beats.stream().map(beat -> beat.said().seconds()).toList();
    }
}
