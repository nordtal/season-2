package eu.nordtal.s2.steward.worker.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The runs before the container, out of the rotated logs in the volume. */
class LogArchiveTest {

    @TempDir
    Path root;

    /** The oldest line Docker still has: the container's first run started here. */
    private static final Instant OLDEST = Instant.parse("2026-09-22T19:44:20Z");

    private Path logs() throws IOException {
        return Files.createDirectories(root.resolve("smp").resolve("logs"));
    }

    private void archive(final String name, final Instant rotated, final String... lines)
            throws IOException {
        final Path file = logs().resolve(name);
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(file))) {
            out.write((String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));
        }
        Files.setLastModifiedTime(file, FileTime.from(rotated));
    }

    @Test
    @DisplayName("only what was rotated before Docker's oldest line, newest run nearest the live one")
    void onlyTheRunsDockerDoesNotHave() throws IOException {
        archive("2026-09-21-1.log.gz", Instant.parse("2026-09-22T10:00:00Z"), "[08:00:00] [x/INFO]: a1", "a2");
        // Rotated seconds after the container started: holds the run before it.
        archive("2026-09-22-3.log.gz", OLDEST.plusSeconds(10), "[10:00:05] [x/INFO]: b1", "b2");
        // Rotated at the restart on the 23rd: holds the container's first run, which Docker has.
        archive("2026-09-23-2.log.gz", Instant.parse("2026-09-23T21:42:41Z"), "[19:44:22] [x/INFO]: c1");
        Files.writeString(logs().resolve("latest.log"), "[21:43:00] [x/INFO]: now\n");

        final LogArchive.Backlog backlog = new LogArchive(root).before("smp", OLDEST, 100);

        assertEquals(List.of("Earlier run, 21 Sep 08:00", "Earlier run, 22 Sep 10:00"),
                backlog.runs().stream().map(LogArchive.Run::label).toList());
        assertEquals(List.of("[10:00:05] [x/INFO]: b1", "b2"), backlog.runs().get(1).lines());
        assertTrue(backlog.exhausted());
        assertEquals(4, backlog.lineCount());
    }

    @Test
    @DisplayName("stops at the number asked for, keeps the newest lines of the last run and says there is more")
    void stopsAtTheNumber() throws IOException {
        archive("2026-09-21-1.log.gz", Instant.parse("2026-09-22T10:00:00Z"), "[08:00:00] [x/INFO]: a1", "a2");
        archive("2026-09-22-1.log.gz", Instant.parse("2026-09-22T12:00:00Z"), "[10:00:00] [x/INFO]: b1", "b2", "b3");

        final LogArchive.Backlog backlog = new LogArchive(root).before("smp", OLDEST, 4);

        assertEquals(2, backlog.runs().size());
        assertEquals(List.of("a2"), backlog.runs().get(0).lines());
        assertFalse(backlog.exhausted());
    }

    @Test
    @DisplayName("a service with no logs in a volume here has no backlog and nothing older")
    void noVolumeNoBacklog() {
        final LogArchive.Backlog backlog = new LogArchive(root).before("postgres", OLDEST, 1000);
        assertTrue(backlog.runs().isEmpty());
        assertTrue(backlog.exhausted());
        assertTrue(new LogArchive(null).before("smp", OLDEST, 1000).runs().isEmpty());
    }
}
