package eu.nordtal.s2.networkcontrol.update;

import eu.nordtal.s2.common.update.UpdateKind;
import eu.nordtal.s2.common.update.UpdateRequest;
import eu.nordtal.s2.common.update.UpdateSource;
import eu.nordtal.s2.common.update.UpdateStatus;
import eu.nordtal.s2.networkcontrol.MutableClock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules about when a player is spoken to before the network goes down.
 *
 * <p>All of them are here rather than in {@link RestartWatch} because they are the part that can be
 * wrong in a way nobody notices until an outage happens: too many messages, a number that is not the
 * truth, or a countdown that replays itself from the top when a proxy reconnects mid-way.</p>
 *
 * <h2>Rewritten 2026-09-08 with the schedule</h2>
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
        return new UpdateRequest(id, UpdateKind.UPDATE, UpdateStatus.RUNNING, UpdateSource.DISCORD,
                "a", NOW, clock.instant().plus(in), NOW, null, null);
    }

    private List<Countdown.Beat> beatsFor(final UpdateRequest request) {
        return countdown.beats(request.id(), request.untilDue(clock.instant())).orElseThrow();
    }

    @Test
    @DisplayName("a full countdown is three chat lines and ten subtitles, and nothing else")
    void aFullCountdownIsPlannedOnce() {
        final List<Countdown.Beat> beats = beatsFor(due(1L, Duration.ofSeconds(30)));

        assertEquals(2, kinds(beats, Announcement.Kind.COUNTDOWN).size(),
                "chat gets thirty and ten; twelve chat lines in half a minute is how a warning"
                        + " becomes something people learn to ignore");
        assertEquals(List.of(30L, 10L), seconds(kinds(beats, Announcement.Kind.COUNTDOWN)));
        assertEquals(List.of(10L, 9L, 8L, 7L, 6L, 5L, 4L, 3L, 2L, 1L),
                seconds(kinds(beats, Announcement.Kind.TICK)));
        assertEquals(1, kinds(beats, Announcement.Kind.NOW).size(),
                "and exactly one 'it is happening'");
        assertEquals(13, beats.size());
    }

    @Test
    @DisplayName("every beat is scheduled on the exact instant its own number is true")
    void theNumberSpokenIsTheNumberLeft() {
        final List<Countdown.Beat> beats = beatsFor(due(1L, Duration.ofMillis(30_000)));

        for (final Countdown.Beat beat : beats) {
            if (beat.announcement().kind() == Announcement.Kind.NOW) {
                assertEquals(Duration.ofSeconds(30), beat.delay(), "zero is at the end");
                continue;
            }
            assertEquals(Duration.ofSeconds(30 - beat.announcement().seconds()), beat.delay(),
                    beat.announcement() + " does not fire when its own number is true");
        }
    }

    @Test
    @DisplayName("a countdown that is not a whole number of seconds still lands on the second")
    void theOddMillisecondsAreTheReasonThisIsNotSeconds() {
        // The updater writes now() + 30s on the database's clock and the proxy reads the row some
        // milliseconds later, so a countdown is never a round number here. Truncating to whole
        // seconds first would put every beat up to 999 ms out - the counter would read 3 with 2.1
        // seconds to go, on the one number that has to be believed.
        final List<Countdown.Beat> beats = beatsFor(due(1L, Duration.ofMillis(29_640)));

        final Countdown.Beat five = kinds(beats, Announcement.Kind.TICK).stream()
                .filter(beat -> beat.announcement().seconds() == 5L)
                .findFirst().orElseThrow();
        assertEquals(Duration.ofMillis(24_640), five.delay(),
                "the '5' is shown 5.000 seconds before the servers go, not 5.640");
    }

    @Test
    @DisplayName("a countdown joined late gets the beats still ahead of it and no others")
    void aCountdownJoinedLateDoesNotReplay() {
        // A proxy that comes up with seven seconds left must not say "30 seconds" twenty-three
        // seconds after that stopped being true.
        final List<Countdown.Beat> beats = beatsFor(due(1L, Duration.ofSeconds(7)));

        assertTrue(kinds(beats, Announcement.Kind.COUNTDOWN).isEmpty(),
                "both chat thresholds are behind us");
        assertEquals(List.of(7L, 6L, 5L, 4L, 3L, 2L, 1L),
                seconds(kinds(beats, Announcement.Kind.TICK)));
        assertEquals(Duration.ofSeconds(7), kinds(beats, Announcement.Kind.NOW).getFirst().delay());
    }

    @Test
    @DisplayName("a countdown already at zero says only that it is happening")
    void zeroIsStillWorthOneLine() {
        final List<Countdown.Beat> beats = beatsFor(due(1L, Duration.ZERO));

        assertEquals(1, beats.size());
        assertEquals(Announcement.Kind.NOW, beats.getFirst().announcement().kind());
        assertEquals(Duration.ZERO, beats.getFirst().delay());
    }

    @Test
    @DisplayName("the same row seen again is not planned twice")
    void theSecondSightingOfOneRowChangesNothing() {
        final UpdateRequest request = due(1L, Duration.ofSeconds(30));
        assertTrue(countdown.beats(request.id(), request.untilDue(clock.instant())).isPresent());

        clock.advance(Duration.ofSeconds(5));
        assertTrue(countdown.beats(request.id(), request.untilDue(clock.instant())).isEmpty(),
                "the beats are already on the scheduler; re-planning would double every line");
    }

    @Test
    @DisplayName("a second request replaces the plan rather than adding to it")
    void aNewRowStartsOver() {
        beatsFor(due(1L, Duration.ofSeconds(30)));

        final Optional<List<Countdown.Beat>> second =
                countdown.beats(2L, Duration.ofSeconds(30));
        assertTrue(second.isPresent());
        assertEquals(2L, countdown.watching());
    }

    // ---------------------------------------------------------------- the row stops counting down

    @Test
    @DisplayName("a withdrawn countdown says it was called off")
    void cancelledSaysCancelled() {
        beatsFor(due(1L, Duration.ofSeconds(30)));

        assertEquals(Announcement.Kind.CANCELLED,
                countdown.gone(UpdateStatus.CANCELLED).orElseThrow().kind());
    }

    @Test
    @DisplayName("a row that reached zero and ran is not announced as called off - finding 39")
    void reachingZeroIsNotCancelling() {
        // The failure this exists for: the row does not vanish when the countdown runs out, it
        // stops being in the counting-down set - and reading that as a withdrawal announced EVERY
        // successful run as called off.
        beatsFor(due(1L, Duration.ofSeconds(30)));

        assertEquals(Announcement.Kind.NOW, countdown.gone(UpdateStatus.RUNNING).orElseThrow().kind());
    }

    @Test
    @DisplayName("once the zero beat has been delivered, the poll behind it stays quiet")
    void theZeroBeatIsNotRepeatedByThePoll() {
        // Both paths can reach "it is happening": the scheduled beat, and a poll landing in the
        // milliseconds after the row left the counting-down set. Saying it twice is the one
        // duplicate a player would definitely notice.
        beatsFor(due(1L, Duration.ofSeconds(30)));
        countdown.zeroReached();

        assertTrue(countdown.gone(UpdateStatus.RUNNING).isEmpty());
    }

    @Test
    @DisplayName("a run that failed after the countdown gets its own line, not the cancel one")
    void failedIsItsOwnAnswer() {
        beatsFor(due(1L, Duration.ofSeconds(30)));

        assertEquals(Announcement.Kind.FAILED,
                countdown.gone(UpdateStatus.FAILED).orElseThrow().kind());
    }

    @Test
    @DisplayName("a row deleted by hand is a cancellation, because nothing is going to happen")
    void aVanishedRowIsACancellation() {
        beatsFor(due(1L, Duration.ofSeconds(30)));

        assertEquals(Announcement.Kind.CANCELLED, countdown.gone(null).orElseThrow().kind());
    }

    @Test
    @DisplayName("nothing was being counted down, so nothing is said")
    void goneWithoutACountdownIsSilent() {
        assertTrue(countdown.gone(UpdateStatus.CANCELLED).isEmpty());
        assertFalse(countdown.beats(1L, Duration.ofSeconds(30)).isEmpty(),
                "and the bookkeeping is clean enough for the next one");
    }

    // ---------------------------------------------------------------- helpers

    private static List<Countdown.Beat> kinds(final List<Countdown.Beat> beats,
                                              final Announcement.Kind kind) {
        return beats.stream().filter(beat -> beat.announcement().kind() == kind).toList();
    }

    private static List<Long> seconds(final List<Countdown.Beat> beats) {
        return beats.stream().map(beat -> beat.announcement().seconds()).toList();
    }
}
