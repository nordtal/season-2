package eu.nordtal.s2.networkcontrol.update;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules about when a player is spoken to before the network goes down.
 * <p>
 * All of them are here rather than in {@link RestartWatch} because they are the part that can be
 * wrong in a way nobody notices until a restart happens: too many messages, a number that is not
 * the truth, or a countdown that replays itself from the top when a proxy reconnects mid-way.
 * </p>
 */
class CountdownTest {

    private final Countdown countdown = new Countdown();

    @Test
    @DisplayName("a full minute produces exactly five announcements")
    void aFullCountdownSpeaksFiveTimes() {
        // Polled every five seconds, which is what RestartWatch does.
        final List<Announcement> said = new ArrayList<>();
        for (long left = 60; left >= 0; left -= 5) {
            countdown.pending(1L, left).ifPresent(said::add);
        }

        assertEquals(5, said.size(), said.toString());
        assertEquals(List.of(60L, 30L, 10L, 5L, 0L),
                said.stream().map(Announcement::seconds).toList());
        assertEquals(Announcement.Kind.NOW, said.get(4).kind(),
                "the last one is the restart itself, not another number");
    }

    @Test
    @DisplayName("the number spoken is what is left, not the threshold that triggered it")
    void itSaysTheTruthAndNotTheRoundNumber() {
        // The poll does not land on exact seconds. Saying "60" at 57 would be a lie by three
        // seconds about the one thing this exists to be believed about.
        final Announcement first = countdown.pending(1L, 57L).orElseThrow();

        assertEquals(Announcement.Kind.COUNTDOWN, first.kind());
        assertEquals(57L, first.seconds());
    }

    @Test
    @DisplayName("a countdown joined late does not replay the announcements it missed")
    void joiningLateDoesNotReplay() {
        // A proxy that restarts with forty seconds already gone. Without this it would say
        // "60 seconds" and then "40 seconds" in the same breath.
        final Announcement first = countdown.pending(1L, 40L).orElseThrow();
        assertEquals(40L, first.seconds());

        assertTrue(countdown.pending(1L, 35L).isEmpty());
        assertEquals(28L, countdown.pending(1L, 28L).orElseThrow().seconds(),
                "and the next threshold it has NOT passed still fires");
    }

    @Test
    @DisplayName("nothing is repeated while the seconds tick down between thresholds")
    void quietBetweenThresholds() {
        countdown.pending(1L, 60L);

        assertTrue(countdown.pending(1L, 55L).isEmpty());
        assertTrue(countdown.pending(1L, 50L).isEmpty());
        assertTrue(countdown.pending(1L, 31L).isEmpty());
        assertTrue(countdown.pending(1L, 30L).isPresent());
    }

    // ---------------------------------------------------------------- cancelling

    @Test
    @DisplayName("a countdown that stops before zero is announced as cancelled")
    void aStoppedCountdownIsAnnounced() {
        countdown.pending(1L, 60L);
        countdown.pending(1L, 30L);

        final Announcement gone = countdown.gone().orElseThrow();
        assertEquals(Announcement.Kind.CANCELLED, gone.kind());
    }

    @Test
    @DisplayName("a countdown that ran out is not a cancellation")
    void reachingZeroIsNotCancelling() {
        // The row disappears the moment the updater claims it, which is a fraction of a second
        // after it hits zero. Telling everybody "the restart was called off" and then restarting
        // is the worst of the possible messages.
        countdown.pending(1L, 5L);
        countdown.pending(1L, 0L);

        assertEquals(Optional.empty(), countdown.gone());
    }

    @Test
    @DisplayName("a quiet network says nothing at all")
    void nothingPendingSaysNothing() {
        assertEquals(Optional.empty(), countdown.gone());
        assertEquals(Optional.empty(), countdown.gone());
    }

    @Test
    @DisplayName("a second restart starts its own countdown")
    void aNewRequestStartsFresh() {
        countdown.pending(1L, 60L);
        countdown.pending(1L, 30L);

        // Cancelled and asked for again, or asked for twice - either way the announcements for the
        // new one must not be suppressed by the old one's bookkeeping.
        final Announcement first = countdown.pending(2L, 60L).orElseThrow();
        assertEquals(60L, first.seconds());
    }
}
