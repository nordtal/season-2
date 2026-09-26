package eu.nordtal.s2.proxy.online;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.online.OnlineDirectory;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * When a tick of {@link OnlineWriter} actually writes.
 *
 * Two cadences in two comparisons, and the reason they are worth a test of their own is that
 * both ways of getting them wrong are silent. Too slow and steward-worker's ten-second wait for a
 * service to empty decides on a number from before the players were moved - it waits the whole cap
 * every run and then reports a count that was never true. Too fast and this writes four rows a
 * second for the whole season to no end at all.
 */
class OnlineWriterCadenceTest {

    private static final Instant NOON = Instant.parse("2026-09-20T12:00:00Z");

    @Test
    void theFirstTickWrites() {
        // Otherwise every dashboard says "nothing known" for ten seconds, for no reason anybody chose.
        assertTrue(OnlineWriter.isDue(null, NOON, false));
    }

    @Test
    void theOrdinaryCadence() {
        assertFalse(OnlineWriter.isDue(NOON, NOON.plusSeconds(1), false));
        assertFalse(OnlineWriter.isDue(NOON, NOON.plusSeconds(9), false));
        assertTrue(
                OnlineWriter.isDue(NOON, NOON.plus(OnlineDirectory.WRITE_INTERVAL), false),
                "the interval is inclusive - a tick landing exactly on it must not be skipped,"
                        + " because the next one is a whole interval later");
    }

    @Test
    void theHurriedCadence() {
        assertTrue(OnlineWriter.isDue(NOON, NOON.plusSeconds(1), true));
        assertTrue(
                OnlineWriter.isDue(NOON, NOON, true),
                "a run is the one case where the same second is worth writing twice: the whole"
                        + " question is whether the last player has gone yet");
    }
}
