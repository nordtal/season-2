package eu.nordtal.s2.steward.worker.serve;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The order of the choreography inside {@code Runner}, read as text.
 *
 * The standby opens before the countdown, and {@code waitUntilEmpty} comes before the first stop.
 */
class StandbyComesBeforeTheWarningTest {

    private final String sequence =
            read("steward-worker/src/main/java/eu/nordtal/s2/steward/worker/serve/UpdateSequence.java");
    private final String backup =
            read("steward-worker/src/main/java/eu/nordtal/s2/steward/worker/serve/BackupSequence.java");
    private final String restart =
            read("steward-worker/src/main/java/eu/nordtal/s2/steward/worker/serve/RestartSequence.java");

    @Test
    void anUpdateOpensTheStandbyWindowBeforeItWarnsAnybody() {
        // UpdateSequence.run opens the standbys through openUpdateStandbys, which must call choreography.open.
        assertTrue(bracket(sequence, "private static UpdateReport openUpdateStandbys(", "\n    private static")
                .contains("choreography.open("));
        assertOrder(
                bracket(sequence, "static Outcome run(", "\n    private static UpdateReport openUpdateStandbys("),
                "openUpdateStandbys(");
    }

    @Test
    void aBackupRunsTheSameChoreographyAsAnUpdate() {
        // A service that stops for a snapshot throws people out exactly as hard as one that stops for a new jar.
        assertOrder(bracket(backup, "static Outcome runUnderLock(", "\n}"));
    }

    @Test
    void aRestartRunsItTooItIsTheRunThatStopsTheMost() {
        assertOrder(bracket(restart, "static Outcome runUnderLock(", "\n}"));
    }

    /** open -> countDown -> waitUntilEmpty -> run.stop, in that order and no other. */
    private static void assertOrder(final String method) {
        assertOrder(method, "choreography.open(");
    }

    private static void assertOrder(final String method, final String opener) {
        final int opened = at(method, opener);
        final int countdown = at(method, "countDown(request.id()");
        final int waited = at(method, "choreography.waitUntilEmpty(");
        final int stopped = at(method, "run.stop(planned, runtime)");

        assertTrue(
                opened < countdown,
                "the standbys are started after the countdown has begun. A standby that will not"
                        + " come up is then discovered by a run that has already warned every"
                        + " player on the network, instead of by one that has touched nothing");
        assertTrue(
                countdown < waited,
                "the wait for the players is above the countdown, so it waits for people who have"
                        + " not been told anything yet");
        assertTrue(
                waited < stopped,
                "a service is stopped before the run has waited for the players to be moved off"
                        + " it, and that ordering is the whole point");
        assertTrue(
                method.contains("choreography.close()"),
                "nothing stops the standbys again, so a run leaves a second network running");
    }

    private static String bracket(final String source, final String from, final String to) {
        final int start = source.indexOf(from);
        assertTrue(
                start > 0,
                "`" + from + "` is gone - if it was renamed, this test moves"
                        + " with it, because a check that cannot find its subject silently stops running");
        final int end = source.indexOf(to, start);
        assertTrue(
                end > start,
                "the method after `" + from + "` is gone or has moved above it;"
                        + " this test brackets one method and needs both ends");
        return source.substring(start, end);
    }

    /** Where {@code token} is, refusing {@code -1} so a deleted call cannot pass an ordering check. */
    private static int at(final String haystack, final String token) {
        final int index = haystack.indexOf(token);
        assertTrue(
                index >= 0,
                "this method of Runner no longer contains `" + token + "`. If that"
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
