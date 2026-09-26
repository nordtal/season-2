package eu.nordtal.s2.common.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Checks that all three Paper backends run the admin watcher, exactly once.
 *
 * A text search, because whether anything calls it cannot be reached from a JVM without a server.
 */
class AdminWatchWiringTest {

    /** The three dedicated backends; the proxy has no operators to grant. */
    private static final List<String> PAPER_PLUGINS = List.of(
            "smp/src/main/java/eu/nordtal/s2/smp/SmpPlugin.java",
            "limbo/src/main/java/eu/nordtal/s2/limbo/LimboPlugin.java",
            "hunger-games/src/main/java/eu/nordtal/s2/hungergames/HungerGamesPlugin.java");

    @Test
    void everyBackendSweepsOpsJsonAtEnableAndThenStartsAWatcher() throws IOException {
        for (final String relative : PAPER_PLUGINS) {
            final String text = read(relative);

            assertTrue(
                    text.contains("operators.sweep()"),
                    relative + " does not sweep ops.json at enable. The file is persistent, so"
                            + " anybody left in it by a crash or a SIGKILL would still be an operator"
                            + " on this start - which is the whole reason an operator is a property"
                            + " of the session and not of the disk.");
            assertTrue(
                    text.contains("new AdminWatch("),
                    relative + " does not build an AdminWatch, so its admin flags are read once per"
                            + " session: an admin revoked in Discord keeps operator on this server"
                            + " until they choose to disconnect.");
            assertTrue(
                    text.contains("adminWatch.start("),
                    relative + " builds an AdminWatch and never starts it, which is the same as not"
                            + " having one and looks like having one.");
            // Either the bare call or the method reference inside Shutdown#quietly.
            assertTrue(
                    text.contains("adminWatch.close()") || text.contains("adminWatch::close"),
                    relative + " never closes its AdminWatch. The listener owns a database connection"
                            + " outside the pool and a thread parked on it; a disable that leaves"
                            + " both running leaks one of each per reload.");
        }
    }

    @Test
    void theGateAndTheWatcherShareOneFullserveradmissionBecauseTwoWouldDrift() throws IOException {
        for (final String relative : PAPER_PLUGINS) {
            final String text = read(relative);

            // A second instance would be an empty cache answering the fullness check.
            assertEquals(
                    1,
                    occurrences(text, "new FullServerAdmission()"),
                    relative + " builds FullServerAdmission a number of times that is not one. It is"
                            + " a cache filled at pre-login and read in the fullness check and now"
                            + " also by the admin watcher; every extra instance is an empty one.");
        }
    }

    private static int occurrences(final String text, final String needle) {
        int count = 0;
        int at = text.indexOf(needle);
        while (at >= 0) {
            count++;
            at = text.indexOf(needle, at + needle.length());
        }
        return count;
    }

    private static String read(final String relative) throws IOException {
        final Path path = repositoryRoot().resolve(relative);
        assertTrue(
                Files.isRegularFile(path),
                relative + " is missing - a renamed module has to move with this list, because a"
                        + " missing file is a check that silently stops running");
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /** Anchors on the directory holding settings.gradle.kts, never on the nearest file by name. */
    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null && !Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) {
            candidate = candidate.getParent();
        }
        if (candidate == null) {
            throw new IllegalStateException(
                    "no settings.gradle.kts above " + Path.of("").toAbsolutePath());
        }
        return candidate;
    }
}
