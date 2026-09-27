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
 * Why this one assertion is a text search when the rule itself is not: {@link Runner#settle} is a pure function and
 * {@link UnverifiedStopSettlesFailedTest} drives it directly - no source text, no database. What that cannot see is
 * where it is called from, and that is the property this file exists for: {@code settle} decides a run is a failure,
 * and it has to be reached after the backup has been taken rather than instead of it.
 *
 * Reaching {@code Runner#backupUnderLock} for real is not available. It is private, the enclosing {@code backup}
 * takes {@link eu.nordtal.s2.steward.worker.schema.RunLock} on a real {@code DataSource}, its first act is a
 * {@code pg_dump} run inside the postgres container, and the countdown that follows reads and writes this run's own
 * row - so driving it would mean a PostgreSQL, the migrations, a dump over the Docker socket and a
 * {@code StewardSpec} built from a written file, for one ordering. {@link CountdownComesAfterResolvingTest} makes
 * the same trade for the same method, and {@code Runner.java} is already declared in
 * {@code repositoryRootTestInputs} so Gradle re-runs this when it changes.
 *
 * The mutation it is here to catch: A cautious-looking early return: seeing that the stop could not be confirmed and
 * failing the run instead of saving, which reads like the safe thing to do and is the exact loss the whole warning
 * exists to prevent. Settling FAILED is a note about archives that exist. A FAILED row with no archives behind it is
 * the one place nobody would ever go looking for a file.
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

    /**
     * The body of {@code backupUnderLock}, so the assertion cannot straddle two methods.
     *
     * {@code settle(} appears in three of this file's methods and {@code run.save(} in one, so a search of the whole
     * source would compare a call in one against a call in another and pass while proving nothing about either. The
     * end of the bracket has to be the method that really follows this one, not merely the next name found.
     */
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

    /**
     * Where {@code token} is, refusing {@code -1}.
     *
     * Not {@code indexOf} at the call site: a missing token answers -1, and -1 is smaller than every real position - so
     * this ordering assertion would go green the moment the save it is protecting were deleted, which is the failure it
     * exists to catch.
     */
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
