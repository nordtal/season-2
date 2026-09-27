package eu.nordtal.s2.steward.worker.serve;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The archives are written before anything decides the run is a failure.
 *
 * Read as source text, since reaching {@code backupUnderLock} for real needs PostgreSQL, a dump and the Docker socket.
 */
class BackupSavesBeforeItSettlesTest {

    private final String source =
            read("steward-worker/src/main/java/eu/nordtal/s2/steward/worker/serve/BackupSequence.java");

    @Test
    void theVolumesAreSavedBeforeAnythingDecidesTheRunIsAFailure() {
        final String backup = backupMethod();

        assertTrue(
                at(backup, "run.save(") < at(backup, "settle("),
                "the run decides it is a failure before it has taken the backup. The whole point of"
                        + " failing is to warn about archives that exist; failing instead of"
                        + " writing them is the loss the warning is there to prevent");
    }

    /** The body of {@code backupUnderLock}, so the assertion cannot straddle two methods. */
    private String backupMethod() {
        final int from = source.indexOf("static Outcome runUnderLock(");
        assertTrue(
                from > 0,
                "BackupSequence#runUnderLock is gone - if it was renamed, this test moves"
                        + " with it, because a check that cannot find its subject silently stops running");
        final int to = source.indexOf("\n}", from);
        assertTrue(
                to > from,
                "BackupSequence has no closing brace after runUnderLock; this test"
                        + " brackets one method and needs both ends");
        return source.substring(from, to);
    }

    /** Where {@code token} is, refusing {@code -1} so a deleted call cannot pass an ordering check. */
    private static int at(final String haystack, final String token) {
        final int index = haystack.indexOf(token);
        assertTrue(
                index >= 0,
                "Runner#backupUnderLock no longer contains `" + token + "`. If that"
                        + " call was removed the guard is gone; if it was renamed, rename it here too.");
        return index;
    }

    private static String read(final String relative) {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null && !Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) {
            candidate = candidate.getParent();
        }
        if (candidate == null) {
            throw new IllegalStateException("no settings.gradle.kts above the working directory");
        }
        try {
            return Files.readString(candidate.resolve(relative), StandardCharsets.UTF_8);
        } catch (final IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
