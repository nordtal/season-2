package eu.nordtal.s2.steward.worker.serve;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The order of the choreography inside {@code Runner}, read as text (season-2-ops/122).
 *
 * <h2>Why this cannot be a test that runs the thing</h2>
 * The same reason {@link CountdownComesAfterResolvingTest} gives, and it applies harder here.
 * Reaching these three statements for real means resolving a plan against GitHub, Modrinth and the
 * Fill API, taking a Postgres advisory lock and sitting through a countdown; what is being asserted
 * is one statement being above another, which no assertion about the outcome can see. A run whose
 * standby came up <em>after</em> the warning behaves identically in every green test and differs
 * only on the day the standby does not come up - which is the day this exists for.
 *
 * <h2>The two mutations it catches</h2>
 * <ul>
 *   <li><b>Opening the window after the countdown</b>, which reads like an improvement: the standby
 *       would boot while the players are being warned, so the run is a minute faster. What it costs
 *       is the abort: a standby that refuses to come up would then be discovered by a run that has
 *       already told every player on the network that the servers are going down.</li>
 *   <li><b>Stopping on the tick instead of waiting</b> - dropping {@code waitUntilEmpty} or putting
 *       it after the first stop. Both leave a green build and take the world away from whoever is
 *       standing in it.</li>
 * </ul>
 */
class StandbyComesBeforeTheWarningTest {

    private final String source =
            read("steward-worker/src/main/java/eu/nordtal/s2/steward/worker/serve/Runner.java");

    @Test
    @DisplayName("an update opens the standby window before it warns anybody")
    void theUpdateOpensFirst() {
        assertOrder(bracket("private Outcome update(", "\n    private boolean countDown("));
    }

    @Test
    @DisplayName("a backup runs the same choreography as an update")
    void theBackupOpensFirst() {
        // Till, 2026-09-20: a backup takes the same approach as an update. A service that stops for
        // a snapshot throws people out exactly as hard as one that stops for a new jar.
        assertOrder(bracket("private Outcome backupUnderLock(", "\n    private Outcome restart("));
    }

    @Test
    @DisplayName("a restart runs it too - it is the run that stops the most")
    void theRestartOpensFirst() {
        assertOrder(bracket("private Outcome restartUnderLock(",
                "\n    // ---------------------------------------------------------------- down"));
    }

    /** open -> countDown -> waitUntilEmpty -> run.stop, in that order and no other. */
    private static void assertOrder(final String method) {
        final int opened = at(method, "choreography.open(");
        final int countdown = at(method, "countDown(request.id()");
        final int waited = at(method, "choreography.waitUntilEmpty(");
        final int stopped = at(method, "run.stop(planned, runtime)");

        assertTrue(opened < countdown,
                "the standbys are started after the countdown has begun. A standby that will not"
                        + " come up is then discovered by a run that has already warned every"
                        + " player on the network, instead of by one that has touched nothing");
        assertTrue(countdown < waited,
                "the wait for the players is above the countdown, so it waits for people who have"
                        + " not been told anything yet");
        assertTrue(waited < stopped,
                "a service is stopped before the run has waited for the players to be moved off"
                        + " it. That is the whole of Till's decision of 2026-09-20");
        assertTrue(method.contains("choreography.close()"),
                "nothing stops the standbys again, so a run leaves a second network running");
    }

    private String bracket(final String from, final String to) {
        final int start = source.indexOf(from);
        assertTrue(start > 0, "Runner#" + from + " is gone - if it was renamed, this test moves"
                + " with it, because a check that cannot find its subject silently stops running");
        final int end = source.indexOf(to, start);
        assertTrue(end > start, "the method after `" + from + "` is gone or has moved above it;"
                + " this test brackets one method and needs both ends");
        return source.substring(start, end);
    }

    /**
     * Where {@code token} is, refusing {@code -1}.
     *
     * <p>Not {@code indexOf} at the call site: a missing token answers -1, which is smaller than
     * every real position - so an ordering assertion goes green the moment the call it protects is
     * deleted.</p>
     */
    private static int at(final String haystack, final String token) {
        final int index = haystack.indexOf(token);
        assertTrue(index >= 0, "this method of Runner no longer contains `" + token + "`. If that"
                + " call was removed the guard is gone; if it was renamed, rename it here too.");
        return index;
    }

    /** The same walk up to {@code settings.gradle.kts} its two sibling source rules make. */
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
