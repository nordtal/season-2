package eu.nordtal.s2.proxy.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.update.UpdateDirectory;
import eu.nordtal.s2.common.update.UpdateKind;
import eu.nordtal.s2.common.update.UpdateRequest;
import eu.nordtal.s2.common.update.UpdateSource;
import eu.nordtal.s2.common.update.UpdateStatus;
import eu.nordtal.s2.proxy.MutableClock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The rules about when a player is spoken to before the network goes down.
 *
 * All of them are here rather than in {@link RestartWatch} because they are the part that can be
 * wrong in a way nobody notices until an outage happens: too many messages, a number that is not the
 * truth, or a countdown that replays itself from the top when a proxy reconnects mid-way.
 *
 * <b>Planned once, on schedule.</b>
 * The class used to be asked "here is what is left, is there anything to say?" once per five-second
 * poll, so the number it spoke was whatever that poll happened to observe - {@code 27} where 30 was
 * asked for - and the last ten seconds could be spoken at most twice. It now plans the whole
 * countdown once and hands back a delay per beat, which is a thing a test can walk against a real
 * clock rather than against the poll interval it was written next to.
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
                UpdateSource.DISCORD,
                "a",
                NOW,
                clock.instant().plus(in),
                NOW,
                null,
                null);
    }

    private List<Countdown.Beat> beatsFor(final UpdateRequest request) {
        return countdown.beats(request.id(), request.untilDue(clock.instant())).orElseThrow();
    }

    @Test
    void aFullCountdownIsPlannedOnce() {
        // The real countdown constant, not a number typed here - a literal would pass while asserting nothing real.
        final List<Countdown.Beat> beats = beatsFor(due(1L, UpdateDirectory.UPDATE_COUNTDOWN));

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
        // A threshold longer than the countdown drops silently, so the two numbers are held against each other.
        for (final long threshold : Countdown.CHAT_THRESHOLDS) {
            assertTrue(
                    threshold <= UpdateDirectory.UPDATE_COUNTDOWN.toSeconds(),
                    "a chat line at " + threshold + "s cannot be spoken in a "
                            + UpdateDirectory.UPDATE_COUNTDOWN.toSeconds() + "s countdown:"
                            + " the beat is planned for an instant that has already passed and"
                            + " is dropped without a word");
        }
        // The whole set is spoken by a countdown of exactly that length: fitting is not the same as being used.
        assertEquals(
                Countdown.CHAT_THRESHOLDS,
                seconds(kinds(beatsFor(due(1L, UpdateDirectory.UPDATE_COUNTDOWN)), Announcement.Kind.COUNTDOWN)));
    }

    @Test
    void theNumberSpokenIsTheNumberLeft() {
        final List<Countdown.Beat> beats = beatsFor(due(1L, Duration.ofSeconds(30)));

        for (final Countdown.Beat beat : beats) {
            if (beat.announcement().kind() == Announcement.Kind.NOW) {
                assertEquals(Duration.ofSeconds(30), beat.delay(), "zero is at the end");
                continue;
            }
            assertEquals(
                    Duration.ofSeconds(30 - beat.announcement().seconds()),
                    beat.delay(),
                    beat.announcement() + " does not fire when its own number is true");
        }
    }

    @Test
    void theOddMillisecondsAreTheReasonThisIsNotSeconds() {
        // The countdown starts at now() + 30s on the database's clock; truncating to seconds puts every beat off.
        final List<Countdown.Beat> beats = beatsFor(due(1L, Duration.ofMillis(29_640)));

        final Countdown.Beat five = kinds(beats, Announcement.Kind.TICK).stream()
                .filter(beat -> beat.announcement().seconds() == 5L)
                .findFirst()
                .orElseThrow();
        assertEquals(
                Duration.ofMillis(24_640),
                five.delay(),
                "the '5' is shown 5.000 seconds before the servers go, not 5.640");
    }

    /**
     * A first line that survives the latency between steward-worker's write and the proxy's read.
     *
     * steward-worker writes now() + the countdown constant on the database's clock and notifies in
     * the same statement, and the proxy reads that row a little later, so the window it actually
     * has is a shade under the full duration. When that shortfall crosses a whole second, the
     * first thing a player sees is the next line down rather than the top one - and it is not a
     * latency that can be removed, because reading a row written by another process is never free.
     *
     * The countdown is spoken in whole seconds, so the question is not whether the full duration in
     * milliseconds is left but whether a counter showing whole seconds still reads the top value.
     *
     * Written against the real countdown constant less a fixed latency rather than against a
     * literal millisecond count, so that a change to the constant does not silently change what
     * this test is measuring - only the latency itself is the thing this case is about.
     */
    @Test
    void theFirstLineIsNotLostToLatency() {
        final List<Countdown.Beat> beats = beatsFor(due(1L, UpdateDirectory.UPDATE_COUNTDOWN.minusMillis(20)));

        assertEquals(
                Countdown.CHAT_THRESHOLDS,
                seconds(kinds(beats, Announcement.Kind.COUNTDOWN)),
                "the first line was dropped because the row took 20 ms to read");
        assertEquals(
                Duration.ZERO,
                kinds(beats, Announcement.Kind.COUNTDOWN).getFirst().delay(),
                "a beat whose instant has just passed is said now, not scheduled into the past");
        // Asserted through the ticks: a run missing its first chat line also logs twelve beats in total.
        assertEquals(List.of(9L, 8L, 7L, 6L, 5L, 4L, 3L, 2L, 1L), seconds(kinds(beats, Announcement.Kind.TICK)));
        assertEquals(Countdown.CHAT_THRESHOLDS.size() + 9 + 1, beats.size());
    }

    @Test
    void aCountdownJoinedLateDoesNotReplay() {
        // A proxy that comes up with seven seconds left must not say "30 seconds" twenty-three seconds late.
        final List<Countdown.Beat> beats = beatsFor(due(1L, Duration.ofSeconds(7)));

        assertTrue(kinds(beats, Announcement.Kind.COUNTDOWN).isEmpty(), "both chat thresholds are behind us");
        assertEquals(List.of(7L, 6L, 5L, 4L, 3L, 2L, 1L), seconds(kinds(beats, Announcement.Kind.TICK)));
        assertEquals(
                Duration.ofSeconds(7),
                kinds(beats, Announcement.Kind.NOW).getFirst().delay());
    }

    @Test
    void zeroIsStillWorthOneLine() {
        final List<Countdown.Beat> beats = beatsFor(due(1L, Duration.ZERO));

        assertEquals(1, beats.size());
        assertEquals(Announcement.Kind.NOW, beats.getFirst().announcement().kind());
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

        final Optional<List<Countdown.Beat>> second = countdown.beats(2L, Duration.ofSeconds(30));
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
        // The row leaves the counting-down set at zero; reading that as a withdrawal would call off every run.
        beatsFor(due(1L, Duration.ofSeconds(30)));

        assertEquals(
                Announcement.Kind.NOW,
                countdown.gone(UpdateStatus.RUNNING).orElseThrow().kind());
    }

    @Test
    void theZeroBeatIsNotRepeatedByThePoll() {
        // The zero beat and a poll just after it both say "it is happening"; players must hear it once.
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
    void oneTitlePerSecond() throws Exception {
        // A title reaches somebody mining with chat closed; a chat line with a title would collide with the tick.
        final List<Countdown.Beat> beats = beatsFor(due(1L, Duration.ofSeconds(30)));

        final List<Long> chat = seconds(kinds(beats, Announcement.Kind.COUNTDOWN));
        for (final Long tick : seconds(kinds(beats, Announcement.Kind.TICK))) {
            assertFalse(
                    chat.contains(tick),
                    "second " + tick + " has both a chat line and a tick, and both draw a title"
                            + " now: they would be drawn over one another");
        }

        // The other half lives in RestartWatch#say, read as text since no test reaches a proxy directly.
        final String say = Files.readString(Path.of("src/main/java/eu/nordtal/s2/proxy/update/RestartWatch.java"));
        final int countdownCase = say.indexOf("case COUNTDOWN ->");
        assertTrue(countdownCase >= 0, "RestartWatch#say no longer has a COUNTDOWN case");
        final String body = say.substring(countdownCase, say.indexOf("case NOW ->", countdownCase));
        assertTrue(
                body.contains("title("),
                "the chat line is still chat only, so the tick removed above bought nothing: " + body);
    }

    // helpers

    private static List<Countdown.Beat> kinds(final List<Countdown.Beat> beats, final Announcement.Kind kind) {
        return beats.stream().filter(beat -> beat.announcement().kind() == kind).toList();
    }

    private static List<Long> seconds(final List<Countdown.Beat> beats) {
        return beats.stream().map(beat -> beat.announcement().seconds()).toList();
    }
}
