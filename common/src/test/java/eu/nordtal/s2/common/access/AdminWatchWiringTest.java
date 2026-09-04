package eu.nordtal.s2.common.access;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That all three Paper backends actually run the admin watcher, and run it once.
 *
 * <h2>Why this is a text search and not a real test</h2>
 * The same reason {@code ReadinessWiringTest} and {@code FatalPathsStopTheServerTest} are: what it
 * protects cannot be reached from a JVM with no server in it. {@link AdminOperators} is covered
 * properly by {@code AdminOperatorsTest} - the grant, the removal, the sweep and the fact that a
 * refresh which changes nothing writes nothing are all ordinary code. What no unit test can reach is
 * <b>whether anything calls it</b>.
 *
 * <p>That is not a hypothetical gap. {@code AdminOperators#refresh} was written on 2026-09-04 for
 * exactly this purpose and then had no caller at all for a day - a mechanism that existed, was
 * tested, and did nothing, on the one question ("is this person still an admin?") where doing
 * nothing looks identical to working. A grep is a weak test; it is also the only one available here,
 * and the regression it catches is silent everywhere else.</p>
 */
class AdminWatchWiringTest {

    /**
     * The three dedicated backends. The proxy is not in this list on purpose: it has no operators
     * to grant, and its own half of the same problem - {@code LoginRoster} going stale - was fixed
     * on 2026-09-02 and rides its phase listener.
     */
    private static final List<String> PAPER_PLUGINS = List.of(
            "smp/src/main/java/eu/nordtal/s2/smp/SmpPlugin.java",
            "limbo/src/main/java/eu/nordtal/s2/limbo/LimboPlugin.java",
            "hunger-games/src/main/java/eu/nordtal/s2/hungergames/HungerGamesPlugin.java");

    @Test
    @DisplayName("every backend sweeps ops.json at enable and then starts a watcher")
    void allThreeSweepAndWatch() throws IOException {
        for (final String relative : PAPER_PLUGINS) {
            final String text = read(relative);

            assertTrue(text.contains("operators.sweep()"),
                    relative + " does not sweep ops.json at enable. The file is persistent, so"
                            + " anybody left in it by a crash or a SIGKILL would still be an operator"
                            + " on this start - which is the whole reason an operator is a property"
                            + " of the session and not of the disk.");
            assertTrue(text.contains("new AdminWatch("),
                    relative + " does not build an AdminWatch, so its admin flags are read once per"
                            + " session: an admin revoked in Discord keeps operator on this server"
                            + " until they choose to disconnect.");
            assertTrue(text.contains("adminWatch.start("),
                    relative + " builds an AdminWatch and never starts it, which is the same as not"
                            + " having one and looks like having one.");
            assertTrue(text.contains("adminWatch.close()"),
                    relative + " never closes its AdminWatch. The listener owns a database connection"
                            + " outside the pool and a thread parked on it; a disable that leaves"
                            + " both running leaks one of each per reload.");
        }
    }

    @Test
    @DisplayName("the gate and the watcher share one FullServerAdmission, because two would drift")
    void oneAdmissionCachePerBackend() throws IOException {
        for (final String relative : PAPER_PLUGINS) {
            final String text = read(relative);

            // FullServerAdmission is warmed on the pre-login thread and read back inside the
            // fullness check. A second instance is not a duplicate - it is an empty cache, and the
            // one that answers the fullness check would be the empty one. smp built its instance
            // inline inside a constructor argument until 2026-09-04, which is precisely the shape
            // that makes a second `new` look harmless.
            assertEquals(1, occurrences(text, "new FullServerAdmission()"),
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
        assertTrue(Files.isRegularFile(path), relative + " no longer exists - if a module was renamed"
                + " this list has to move with it, because a missing file is a check that silently"
                + " stops running");
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /** Anchors on the directory holding settings.gradle.kts, never on the nearest file by name. */
    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null && !Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) {
            candidate = candidate.getParent();
        }
        if (candidate == null) {
            throw new IllegalStateException("no settings.gradle.kts above " + Path.of("").toAbsolutePath());
        }
        return candidate;
    }
}
