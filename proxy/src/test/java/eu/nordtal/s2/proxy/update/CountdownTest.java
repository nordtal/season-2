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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
    @DisplayName("a full countdown is three chat lines and nine subtitles, and nothing else")
    void aFullCountdownIsPlannedOnce() {
        // THE REAL COUNTDOWN AND NOT A NUMBER TYPED HERE. It was 30 seconds and became 60 on
        // 2026-09-20, and a test that had spelt the old number would have gone on passing while
        // asserting a countdown nothing runs.
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
                        + " that second itself (season-2-ops/132)");
        assertEquals(1, kinds(beats, Announcement.Kind.NOW).size(), "and exactly one 'it is happening'");
        assertEquals(13, beats.size());
    }

    @Test
    @DisplayName("no chat line is planned for a second the countdown never reaches")
    void theThresholdsFitInsideTheCountdown() {
        // season-2-ops/132, AND IT IS THE OBJECTION THAT HELD THE SIXTY BACK FOR A DAY. A threshold
        // longer than the countdown is not a loud failure: rule three drops it, in silence, and the
        // line is simply never spoken again. That is how the thirty-second line was lost for a
        // season - the same shape, one rounding step smaller.
        //
        // So the two numbers are held against each other rather than each against a literal, and
        // raising either one alone fails here instead of going quiet in production.
        for (final long threshold : Countdown.CHAT_THRESHOLDS) {
            assertTrue(
                    threshold <= UpdateDirectory.UPDATE_COUNTDOWN.toSeconds(),
                    "a chat line at " + threshold + "s cannot be spoken in a "
                            + UpdateDirectory.UPDATE_COUNTDOWN.toSeconds() + "s countdown:"
                            + " the beat is planned for an instant that has already passed and"
                            + " is dropped without a word");
        }
        // And the whole set is spoken by a countdown of exactly that length, which is the other
        // half: a threshold that merely fits is not the same as one that is used.
        assertEquals(
                Countdown.CHAT_THRESHOLDS,
                seconds(kinds(beatsFor(due(1L, UpdateDirectory.UPDATE_COUNTDOWN)), Announcement.Kind.COUNTDOWN)));
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
            assertEquals(
                    Duration.ofSeconds(30 - beat.announcement().seconds()),
                    beat.delay(),
                    beat.announcement() + " does not fire when its own number is true");
        }
    }

    @Test
    @DisplayName("a countdown that is not a whole number of seconds still lands on the second")
    void theOddMillisecondsAreTheReasonThisIsNotSeconds() {
        // steward-worker writes now() + 30s on the database's clock and the proxy reads the row some
        // milliseconds later, so a countdown is never a round number here. Truncating to whole
        // seconds first would put every beat up to 999 ms out - the counter would read 3 with 2.1
        // seconds to go, on the one number that has to be believed.
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

    @Test
    @DisplayName("the first line survives the milliseconds it took to read the row")
    void theFirstLineIsNotLostToLatency() {
        // MEASURED ON THE DEV HOST, 2026-09-19 (season-2-ops/118). steward-worker writes
        // now() + 30s on the database's clock and notifies in the same statement; the proxy read
        // the row 20 ms later and logged "12 beat(s) over PT29.979941209S". Twelve, not thirteen:
        // the thirty-second chat line asked for a full 30 000 ms and 29 980 was not enough, so the
        // first thing a player ever saw was the ten-second line. That is exactly the report - "erst
        // bei 10 Sekunden kommt eine Nachricht" - and it is not a latency that can be removed,
        // because reading a row written by another process is never free.
        //
        // The countdown is spoken in whole seconds, so the question is not whether 30 000 ms are
        // left but whether a counter showing whole seconds still reads 30.
        //
        // Written against the real countdown less those 20 ms rather than against 29 980 outright:
        // the number moved to 60 on 2026-09-20 and the latency did not, and it is the latency this
        // case is about.
        final List<Countdown.Beat> beats = beatsFor(due(1L, UpdateDirectory.UPDATE_COUNTDOWN.minusMillis(20)));

        assertEquals(
                Countdown.CHAT_THRESHOLDS,
                seconds(kinds(beats, Announcement.Kind.COUNTDOWN)),
                "the first line was dropped because the row took 20 ms to read");
        assertEquals(
                Duration.ZERO,
                kinds(beats, Announcement.Kind.COUNTDOWN).getFirst().delay(),
                "a beat whose instant has just passed is said now, not scheduled into the past");
        // The count is asserted through the ticks rather than on its own, and the reason is the
        // trap this whole case is about: the broken run logged twelve beats too, for the opposite
        // reason - two chat lines and ten ticks with the first line missing. A bare number would
        // go green again the day a line is lost twice.
        assertEquals(List.of(9L, 8L, 7L, 6L, 5L, 4L, 3L, 2L, 1L), seconds(kinds(beats, Announcement.Kind.TICK)));
        assertEquals(Countdown.CHAT_THRESHOLDS.size() + 9 + 1, beats.size());
    }

    @Test
    @DisplayName("a countdown joined late gets the beats still ahead of it and no others")
    void aCountdownJoinedLateDoesNotReplay() {
        // A proxy that comes up with seven seconds left must not say "30 seconds" twenty-three
        // seconds after that stopped being true.
        final List<Countdown.Beat> beats = beatsFor(due(1L, Duration.ofSeconds(7)));

        assertTrue(kinds(beats, Announcement.Kind.COUNTDOWN).isEmpty(), "both chat thresholds are behind us");
        assertEquals(List.of(7L, 6L, 5L, 4L, 3L, 2L, 1L), seconds(kinds(beats, Announcement.Kind.TICK)));
        assertEquals(
                Duration.ofSeconds(7),
                kinds(beats, Announcement.Kind.NOW).getFirst().delay());
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
        assertTrue(
                countdown.beats(request.id(), request.untilDue(clock.instant())).isPresent());

        clock.advance(Duration.ofSeconds(5));
        assertTrue(
                countdown.beats(request.id(), request.untilDue(clock.instant())).isEmpty(),
                "the beats are already on the scheduler; re-planning would double every line");
    }

    @Test
    @DisplayName("a second request replaces the plan rather than adding to it")
    void aNewRowStartsOver() {
        beatsFor(due(1L, Duration.ofSeconds(30)));

        final Optional<List<Countdown.Beat>> second = countdown.beats(2L, Duration.ofSeconds(30));
        assertTrue(second.isPresent());
        assertEquals(2L, countdown.watching());
    }

    // ---------------------------------------------------------------- the row stops counting down

    @Test
    @DisplayName("a withdrawn countdown says it was called off")
    void cancelledSaysCancelled() {
        beatsFor(due(1L, Duration.ofSeconds(30)));

        assertEquals(
                Announcement.Kind.CANCELLED,
                countdown.gone(UpdateStatus.CANCELLED).orElseThrow().kind());
    }

    @Test
    @DisplayName("a row that reached zero and ran is not announced as called off - finding 39")
    void reachingZeroIsNotCancelling() {
        // The failure this exists for: the row does not vanish when the countdown runs out, it
        // stops being in the counting-down set - and reading that as a withdrawal announced EVERY
        // successful run as called off.
        beatsFor(due(1L, Duration.ofSeconds(30)));

        assertEquals(
                Announcement.Kind.NOW,
                countdown.gone(UpdateStatus.RUNNING).orElseThrow().kind());
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

        assertEquals(
                Announcement.Kind.FAILED,
                countdown.gone(UpdateStatus.FAILED).orElseThrow().kind());
    }

    @Test
    @DisplayName("a row deleted by hand is a cancellation, because nothing is going to happen")
    void aVanishedRowIsACancellation() {
        beatsFor(due(1L, Duration.ofSeconds(30)));

        assertEquals(
                Announcement.Kind.CANCELLED, countdown.gone(null).orElseThrow().kind());
    }

    @Test
    @DisplayName("nothing was being counted down, so nothing is said")
    void goneWithoutACountdownIsSilent() {
        assertTrue(countdown.gone(UpdateStatus.CANCELLED).isEmpty());
        assertFalse(
                countdown.beats(1L, Duration.ofSeconds(30)).isEmpty(),
                "and the bookkeeping is clean enough for the next one");
    }

    @Test
    @DisplayName("a chat line draws its own title, and takes the tick of that second with it")
    void oneTitlePerSecond() throws Exception {
        // season-2-ops/132. Till wants the warning in both channels: chat is where it is read, a
        // title is what reaches somebody mining with the chat box closed. The moment a chat line
        // draws a title too, the tick of that same second is a second title on the same second -
        // and two titles on one second do not queue, they fade over one another.
        final List<Countdown.Beat> beats = beatsFor(due(1L, Duration.ofSeconds(30)));

        final List<Long> chat = seconds(kinds(beats, Announcement.Kind.COUNTDOWN));
        for (final Long tick : seconds(kinds(beats, Announcement.Kind.TICK))) {
            assertFalse(
                    chat.contains(tick),
                    "second " + tick + " has both a chat line and a tick, and both draw a title"
                            + " now: they would be drawn over one another");
        }

        // The other half is one line up in RestartWatch#say, where no test without a proxy, a
        // roster and a locale can reach it - so it is read as text, the way RecreateDoesNotPullTest
        // reads its route. Without this the loop above passes on a countdown that still says
        // nothing in the middle of the screen at thirty seconds.
        final String say = Files.readString(Path.of("src/main/java/eu/nordtal/s2/proxy/update/RestartWatch.java"));
        final int countdownCase = say.indexOf("case COUNTDOWN ->");
        assertTrue(countdownCase >= 0, "RestartWatch#say no longer has a COUNTDOWN case");
        final String body = say.substring(countdownCase, say.indexOf("case NOW ->", countdownCase));
        assertTrue(
                body.contains("title("),
                "the chat line is still chat only, so the tick removed above bought nothing: " + body);
    }

    // ---------------------------------------------------------------- helpers

    private static List<Countdown.Beat> kinds(final List<Countdown.Beat> beats, final Announcement.Kind kind) {
        return beats.stream().filter(beat -> beat.announcement().kind() == kind).toList();
    }

    private static List<Long> seconds(final List<Countdown.Beat> beats) {
        return beats.stream().map(beat -> beat.announcement().seconds()).toList();
    }
}
