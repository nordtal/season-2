package eu.nordtal.s2.common.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The freshness rule, driven by explicit timestamps.
 *
 * Not one case here sleeps. The whole reason {@link Readiness#fresh(Instant, Instant, Duration)}
 * takes both instants is that a heartbeat test which waits for real time to pass is a test that is
 * either slow or flaky, and usually both - and the arithmetic is the half that can be wrong.
 */
class ReadinessTest {

    private static final Instant BEAT = Instant.parse("2026-09-04T12:00:00Z");

    @Test
    void aMarkerWrittenAMomentAgoIsFresh() {
        assertTrue(Readiness.fresh(BEAT, BEAT, Readiness.STALE_AFTER));
        assertTrue(Readiness.fresh(BEAT, BEAT.plusSeconds(29), Readiness.STALE_AFTER));
        assertTrue(Readiness.fresh(BEAT, BEAT.plusSeconds(89), Readiness.STALE_AFTER));
    }

    @Test
    void theWindowClosesExactlyWhereComposeYmlsLt90ClosesIt() {
        // compose.yml holds a copy of this boundary: an age of exactly 90 is stale in `test ... -lt 90`.
        assertTrue(Readiness.fresh(BEAT, BEAT.plusMillis(89_999), Readiness.STALE_AFTER));
        assertFalse(Readiness.fresh(BEAT, BEAT.plusSeconds(90), Readiness.STALE_AFTER));
        assertFalse(Readiness.fresh(BEAT, BEAT.plusSeconds(91), Readiness.STALE_AFTER));
    }

    @Test
    void threeMissedBeatsIsWhatMakesAMarkerStale() {
        // A beat interval above a third of the window would make a healthy process flap.
        assertEquals(
                3,
                Readiness.STALE_AFTER.toSeconds() / Readiness.BEAT.toSeconds(),
                "STALE_AFTER is not three beats - a container goes red on a different number of"
                        + " missed refreshes than the comment in Readiness claims");
        assertEquals(0, Readiness.STALE_AFTER.toSeconds() % Readiness.BEAT.toSeconds());
    }

    @Test
    void aMarkerFromTheFutureIsFreshBecauseTheShellSaysSoToo() {
        // `test $(( now - mtime )) -lt 90` is true for a negative age, so this must be too.
        assertTrue(Readiness.fresh(BEAT, BEAT.minusSeconds(3600), Readiness.STALE_AFTER));
    }

    @Test
    void refreshCreatesTheMarkerItsParentDirectoryAndMovesItsModificationTime(@TempDir final Path directory)
            throws IOException {
        final Path marker = directory.resolve("nested/nordtal-ready");
        final Readiness readiness = new Readiness(marker, complaint -> {
            throw new AssertionError("a working refresh complained: " + complaint);
        });

        assertTrue(readiness.refresh());
        assertTrue(Files.isRegularFile(marker), "the marker was not created");

        // The healthcheck reads only the mtime, so it is backdated by hand rather than slept on.
        final FileTime backdated = FileTime.from(Instant.now().minusSeconds(600));
        Files.setLastModifiedTime(marker, backdated);
        assertTrue(readiness.refresh());
        assertTrue(
                Files.getLastModifiedTime(marker).toInstant().isAfter(backdated.toInstant()),
                "refresh() did not move the marker's modification time, which is the one thing the"
                        + " container healthcheck looks at");

        // The content is for a human running `cat` during a drill; it is not what is checked.
        assertFalse(Files.readString(marker, StandardCharsets.UTF_8).isBlank());
    }

    @Test
    void aMarkerThatIsNotThereIsNotFreshAndNeitherIsAnOldOne(@TempDir final Path directory) throws IOException {
        final Path marker = directory.resolve("nordtal-ready");
        assertFalse(
                Readiness.fresh(marker, Instant.now(), Readiness.STALE_AFTER),
                "a missing marker read as fresh - which is what a container looks like before its"
                        + " process has ever finished starting");
        assertTrue(Readiness.lastBeat(marker).isEmpty());

        Files.writeString(marker, "written\n", StandardCharsets.UTF_8);
        final Instant written = Instant.parse("2026-09-04T12:00:00Z");
        Files.setLastModifiedTime(marker, FileTime.from(written));

        assertTrue(Readiness.fresh(marker, written.plusSeconds(10), Readiness.STALE_AFTER));
        assertFalse(Readiness.fresh(marker, written.plusSeconds(120), Readiness.STALE_AFTER));
    }

    @Test
    void aMarkerThatCannotBeWrittenComplainsOnceAndAgainAfterARecovery(@TempDir final Path directory)
            throws IOException {
        // A file where a directory has to be makes every refresh fail; it must be logged once.
        final Path blocked = directory.resolve("blocked");
        Files.writeString(blocked, "not a directory\n", StandardCharsets.UTF_8);

        final List<String> complaints = new ArrayList<>();
        final Readiness readiness = new Readiness(blocked.resolve("nordtal-ready"), complaints::add);

        assertFalse(readiness.refresh());
        assertFalse(readiness.refresh());
        assertFalse(readiness.refresh());
        assertEquals(1, complaints.size(), "every failed refresh complained: " + complaints);
        assertTrue(
                complaints.getFirst().contains("nordtal-ready"),
                "the complaint does not name the path that could not be written: " + complaints);

        // Clear the obstruction: the same instance recovers and says nothing about it.
        Files.delete(blocked);
        assertTrue(readiness.refresh());
        assertEquals(1, complaints.size(), "a successful refresh complained: " + complaints);

        // A failure after a recovery is logged again.
        Files.delete(blocked.resolve("nordtal-ready"));
        Files.delete(blocked);
        Files.writeString(blocked, "not a directory\n", StandardCharsets.UTF_8);
        assertFalse(readiness.refresh());
        assertEquals(2, complaints.size(), "the second outage was never reported: " + complaints);
    }
}
