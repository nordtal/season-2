package eu.nordtal.s2.steward.worker.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The heartbeat timer decides when a comment is written, and never writes one itself.
 *
 * A source search, since a blocked write could not be provoked here; it catches {@code beat} being inlined.
 */
class HeartbeatLeavesTheTimerTest {

    private final String logFollows =
            read("steward-worker/src/main/java/eu/nordtal/s2/steward/worker/api/LogFollows.java");

    @Test
    void theScheduledTaskHandsTheWriteOnRatherThanDoingIt() {
        final String serve = serveMethod();

        // Checked first, deliberately: on the failing path serve() still contains `sendComment` and lost `beat()`.
        assertFalse(
                serve.contains("sendComment"),
                "serve() writes a comment from inside the scheduled task again. That is"
                        + " the one thread every follow in the process shares, so a reader that has stopped"
                        + " reading can hold it - see this class for how far that was and was not"
                        + " reproducible here");
        at(serve, "heartbeats.scheduleWithFixedDelay(");
        at(serve, "beat(client, name, beating)");
    }

    @Test
    void theWriteItselfHappensOnTheExecutorThatIsAllowedToBlock() {
        final String beat = beatMethod();
        final int submitted = at(beat, "followers.submit(");
        final int written = at(beat, "client.sendComment(");

        assertTrue(
                submitted < written,
                "beat() writes the comment before handing it to `followers`, so it is writing on"
                        + " whichever thread called it - and the only caller is the timer");
        assertTrue(
                logFollows.contains("Executors.newVirtualThreadPerTaskExecutor()"),
                "`followers` is no longer a virtual thread per task, so handing the write to it"
                        + " parks a platform thread instead - cheap enough to be worth checking, because"
                        + " the whole argument for this shape is that parking there costs nothing");
        assertTrue(
                logFollows.contains("Executors.newSingleThreadScheduledExecutor("),
                "the heartbeat scheduler is no longer a single thread. That is the premise of this"
                        + " whole file: with a pool, one parked write costs one thread of it instead of"
                        + " every follow. If that was deliberate, this test is what should have been"
                        + " changed with it");
    }

    @Test
    void aCommentStillOnItsWayOutMeansTheNextTickIsSkippedNotQueued() {
        final String beat = beatMethod();
        final int guard = at(beat, "beating.compareAndSet(false, true)");
        final int submitted = at(beat, "followers.submit(");
        final int written = at(beat, "client.sendComment(");
        final int released = at(beat, "beating.set(false)");

        assertTrue(
                guard < submitted,
                "the tick is submitted before anything checks whether the last one finished, so a"
                        + " follow nobody is reading accumulates one parked virtual thread per tick with"
                        + " nothing to stop it");
        assertTrue(
                written < released,
                "`beating` is cleared before the comment has been written, which makes the guard"
                        + " decorative: the next tick is free to start immediately");
    }

    /** The body of {@code serve} on its own, since {@code sendComment} legitimately appears inside {@code beat}. */
    private String serveMethod() {
        final int from = logFollows.indexOf("void serve(final SseClient client");
        assertTrue(
                from > 0,
                "LogFollows#serve is gone or its signature changed. If it moved, this"
                        + " test moves with it - a check that cannot find its subject stops running and"
                        + " says nothing about it");
        final int to = logFollows.indexOf("\n    private void backlog(");
        assertTrue(
                to > from,
                "LogFollows#backlog is gone or has moved above serve; this"
                        + " test brackets the serve method and needs both ends");
        return logFollows.substring(from, to);
    }

    /** The body of {@code beat}, for the same reason: the orderings inside it are its own. */
    private String beatMethod() {
        final int from = logFollows.indexOf("private void beat(final SseClient client");
        assertTrue(
                from > 0,
                "LogFollows#beat is gone. If the heartbeat went back to being written"
                        + " inline, that is the change this file exists to argue with");
        final int to = logFollows.indexOf("\n    private void goneOnShutdown(");
        assertTrue(
                to > from,
                "LogFollows#goneOnShutdown is gone or has moved above beat; this test"
                        + " brackets one method and needs both ends");
        return logFollows.substring(from, to);
    }

    /** Where {@code token} is, refusing {@code -1} so a deleted call cannot pass an ordering assertion. */
    private static int at(final String haystack, final String token) {
        final int index = haystack.indexOf(token);
        assertTrue(
                index >= 0,
                "no longer contains `" + token + "`. If it was removed the"
                        + " guard is gone; if it was renamed, rename it here too.");
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
