package eu.nordtal.s2.common.health;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The freshness rule, driven by explicit timestamps.
 *
 * <p>Not one case here sleeps. The whole reason {@link Readiness#fresh(Instant, Instant, Duration)}
 * takes both instants is that a heartbeat test which waits for real time to pass is a test that is
 * either slow or flaky, and usually both - and the arithmetic is the half that can be wrong.</p>
 */
class ReadinessTest {

    private static final Instant BEAT = Instant.parse("2026-09-04T12:00:00Z");

    @Test
    @DisplayName("a marker written a moment ago is fresh")
    void aRecentBeatIsFresh() {
        assertTrue(Readiness.fresh(BEAT, BEAT, Readiness.STALE_AFTER));
        assertTrue(Readiness.fresh(BEAT, BEAT.plusSeconds(29), Readiness.STALE_AFTER));
        assertTrue(Readiness.fresh(BEAT, BEAT.plusSeconds(89), Readiness.STALE_AFTER));
    }

    @Test
    @DisplayName("the window closes exactly where compose.yml's `-lt 90` closes it")
    void theBoundaryMatchesTheShell() {
        // compose.yml cannot read STALE_AFTER - it is a shell test inside a YAML file - so the two
        // are two copies of one number, and the boundary is the part that drifts silently. An age of
        // exactly 90 is stale in `test ... -lt 90`, and it has to be stale here too.
        assertTrue(Readiness.fresh(BEAT, BEAT.plusMillis(89_999), Readiness.STALE_AFTER));
        assertFalse(Readiness.fresh(BEAT, BEAT.plusSeconds(90), Readiness.STALE_AFTER));
        assertFalse(Readiness.fresh(BEAT, BEAT.plusSeconds(91), Readiness.STALE_AFTER));
    }

    @Test
    @DisplayName("three missed beats is what makes a marker stale")
    void staleAfterIsThreeBeats() {
        // The two constants are not independent: 90s is 3 x 30s, and a beat interval raised past a
        // third of the window would make a perfectly healthy process flap.
        assertEquals(3, Readiness.STALE_AFTER.toSeconds() / Readiness.BEAT.toSeconds(),
                "STALE_AFTER is no longer three beats - a container will now go red on fewer missed"
                        + " refreshes than the comment in Readiness claims");
        assertEquals(0, Readiness.STALE_AFTER.toSeconds() % Readiness.BEAT.toSeconds());
    }

    @Test
    @DisplayName("a marker from the future is fresh, because the shell says so too")
    void aClockThatJumpedReadsAsFresh() {
        // `test $(( now - mtime )) -lt 90` is true for a negative age. Answering differently here
        // would be a second answer to one question, which is worse than being generous.
        assertTrue(Readiness.fresh(BEAT, BEAT.minusSeconds(3600), Readiness.STALE_AFTER));
    }

    @Test
    @DisplayName("refresh creates the marker, its parent directory, and moves its modification time")
    void refreshWritesTheFile(@TempDir final Path directory) throws IOException {
        final Path marker = directory.resolve("nested/nordtal-ready");
        final Readiness readiness = new Readiness(marker, complaint -> {
            throw new AssertionError("a working refresh complained: " + complaint);
        });

        assertTrue(readiness.refresh());
        assertTrue(Files.isRegularFile(marker), "the marker was not created");

        // The healthcheck reads the modification time and never the content, so a refresh that left
        // mtime alone would be invisible to the only thing that reads this file. Set it back by hand
        // rather than sleeping, then refresh again.
        final FileTime backdated = FileTime.from(Instant.now().minusSeconds(600));
        Files.setLastModifiedTime(marker, backdated);
        assertTrue(readiness.refresh());
        assertTrue(Files.getLastModifiedTime(marker).toInstant().isAfter(backdated.toInstant()),
                "refresh() did not move the marker's modification time, which is the one thing the"
                        + " container healthcheck looks at");

        // The content is for a human running `cat` during a drill; it is not what is checked.
        assertFalse(Files.readString(marker, StandardCharsets.UTF_8).isBlank());
    }

    @Test
    @DisplayName("a marker that is not there is not fresh, and neither is an old one")
    void theFileAnswerAgreesWithThePredicate(@TempDir final Path directory) throws IOException {
        final Path marker = directory.resolve("nordtal-ready");
        assertFalse(Readiness.fresh(marker, Instant.now(), Readiness.STALE_AFTER),
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
    @DisplayName("a marker that cannot be written complains once, and again after a recovery")
    void aFailedRefreshIsReportedOnce(@TempDir final Path directory) throws IOException {
        // A file where a directory has to be: createDirectories fails, so every refresh fails. The
        // point is the count - a complaint twice a minute for ever is a log nobody reads.
        final Path blocked = directory.resolve("blocked");
        Files.writeString(blocked, "not a directory\n", StandardCharsets.UTF_8);

        final List<String> complaints = new ArrayList<>();
        final Readiness readiness = new Readiness(blocked.resolve("nordtal-ready"), complaints::add);

        assertFalse(readiness.refresh());
        assertFalse(readiness.refresh());
        assertFalse(readiness.refresh());
        assertEquals(1, complaints.size(), "every failed refresh complained: " + complaints);
        assertTrue(complaints.getFirst().contains("nordtal-ready"),
                "the complaint does not name the path that could not be written: " + complaints);

        // Clear the obstruction: the same instance recovers and says nothing about it.
        Files.delete(blocked);
        assertTrue(readiness.refresh());
        assertEquals(1, complaints.size(), "a successful refresh complained: " + complaints);

        // ...and a failure after a recovery is news again, rather than being swallowed by the flag
        // that silenced the first run of failures.
        Files.delete(blocked.resolve("nordtal-ready"));
        Files.delete(blocked);
        Files.writeString(blocked, "not a directory\n", StandardCharsets.UTF_8);
        assertFalse(readiness.refresh());
        assertEquals(2, complaints.size(), "the second outage was never reported: " + complaints);
    }
}
