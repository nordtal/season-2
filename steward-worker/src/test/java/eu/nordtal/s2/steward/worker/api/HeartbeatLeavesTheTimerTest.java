package eu.nordtal.s2.steward.worker.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The heartbeat timer decides when a comment is written, and never writes one itself.
 *
 * <h2>What this is protecting</h2>
 * Every open log follow schedules its heartbeat on {@code heartbeats}, which is one thread for the
 * whole process. {@code SseClient#sendComment} writes into a socket, and a socket whose reader has
 * stopped reading eventually has nowhere to put the bytes. Done on the timer thread, a write that
 * cannot proceed is a write that holds the only thread every other follow's heartbeat is queued on
 * - so one reader that stopped reading would silence every log view in the stack, and Jetty would
 * drop each of them thirty seconds later. That is a worse failure than the one the heartbeat was
 * added to prevent, because it belongs to somebody who did nothing wrong.
 *
 * <h2>Why this is a text search and not a run, which is the uncomfortable part</h2>
 * A test that opened two follows, stalled the first and counted the second's heartbeats was
 * written first, and it <b>passed on the old code as well</b>. That is not a subtlety to leave in a
 * comment somewhere, so here is what was measured on this host on 2026-09-13, against Javalin
 * 7.2.3, Jetty 12.1.12 and Java 25, with the timer thread's stack sampled throughout:
 *
 * <ul>
 *   <li>A follow of {@code smp} - 1.8 MB of backlog waiting - read through a socket whose receive
 *       buffer was set to 4 KB and then never read. Back-pressure was real and was confirmed rather
 *       than assumed: emptying the buffer once refilled it with another 4 096 bytes. A second
 *       follow kept its heartbeats throughout, and {@code steward-worker-sse-heartbeat} was in
 *       {@code DelayedWorkQueue.take} at every sample - idle, never inside a write.</li>
 *   <li>A follow with no log traffic at all, beating every 5 ms until the unread reader's buffer
 *       froze at 5 248 bytes and stayed there. A second follow received 538 comments in three
 *       seconds, where 600 were due. Again the timer thread was idle at every sample.</li>
 * </ul>
 *
 * <p>The reason is in Javalin's own bytecode. {@code Emitter.emit(String)} - the comment path, as
 * opposed to the three-argument event path - is <em>not</em> synchronised, so a comment does not
 * queue behind the log writer on the same connection; and it wraps {@code print} and
 * {@code flushBuffer} in {@code catch (IOException) { closed = true }}, after which
 * {@code sendComment} closes the client. Between Jetty's aggregating output buffer and the kernel's
 * send buffer there is roughly 48 KB of somewhere-to-put-it, and what was observed when that ran
 * out was the stalled follow's own heartbeat stopping - not the timer parking. Pushing harder than
 * that (a beat every millisecond) stopped being a test of anything: it took the test JVM down
 * through the shutdown storm {@code WorkerApi#follows} describes.</p>
 *
 * <p>So the danger is latent rather than live: it rests on Javalin continuing not to synchronise
 * that path and on Jetty continuing to buffer, neither of which this project controls or would
 * hear about. Doing the write somewhere it is allowed to block is still the right shape, and the
 * shape is the only part of it a test on this machine can hold. This file says so out loud instead
 * of dressing a green run up as proof of something it did not reach.</p>
 *
 * <h2>What it would catch</h2>
 * Somebody folding {@code beat(...)} back into the one-liner it replaced - which reads like tidying
 * up, because on a healthy connection the two behave identically and no test but this one can tell
 * them apart.
 */
class HeartbeatLeavesTheTimerTest {

    private final String source =
            read("steward-worker/src/main/java/eu/nordtal/s2/steward/worker/api/WorkerApi.java");

    @Test
    @DisplayName("the scheduled task hands the write on rather than doing it")
    void theTimerOnlyDecidesWhen() {
        final String route = followRoute();

        // This one first, and deliberately: on the failing path the route contains `sendComment`
        // AND has lost the call to beat(), so whichever is checked first is the message somebody
        // reads. "It no longer contains this string" describes the test; this describes the defect.
        assertFalse(route.contains("sendComment"),
                "the follow route writes a comment from inside the scheduled task again. That is"
                + " the one thread every follow in the process shares, so a reader that has stopped"
                + " reading can hold it - see this class for how far that was and was not"
                + " reproducible here");
        at(route, "heartbeats.scheduleWithFixedDelay(");
        at(route, "beat(client, name, beating)");
    }

    @Test
    @DisplayName("the write itself happens on the executor that is allowed to block")
    void theWriteGoesToAThreadThatCanPark() {
        final String beat = beatMethod();
        final int submitted = at(beat, "followers.submit(");
        final int written = at(beat, "client.sendComment(");

        assertTrue(submitted < written,
                "beat() writes the comment before handing it to `followers`, so it is writing on"
                + " whichever thread called it - and the only caller is the timer");
        assertTrue(source.contains("Executors.newVirtualThreadPerTaskExecutor()"),
                "`followers` is no longer a virtual thread per task, so handing the write to it"
                + " parks a platform thread instead - cheap enough to be worth checking, because"
                + " the whole argument for this shape is that parking there costs nothing");
        assertTrue(source.contains("Executors.newSingleThreadScheduledExecutor("),
                "the heartbeat scheduler is no longer a single thread. That is the premise of this"
                + " whole file: with a pool, one parked write costs one thread of it instead of"
                + " every follow. If that was deliberate, this test is what should have been"
                + " changed with it");
    }

    @Test
    @DisplayName("a comment still on its way out means the next tick is skipped, not queued")
    void oneCommentAtATime() {
        final String beat = beatMethod();
        final int guard = at(beat, "beating.compareAndSet(false, true)");
        final int submitted = at(beat, "followers.submit(");
        final int written = at(beat, "client.sendComment(");
        final int released = at(beat, "beating.set(false)");

        assertTrue(guard < submitted,
                "the tick is submitted before anything checks whether the last one finished, so a"
                + " follow nobody is reading accumulates one parked virtual thread per tick with"
                + " nothing to stop it");
        assertTrue(written < released,
                "`beating` is cleared before the comment has been written, which makes the guard"
                + " decorative: the next tick is free to start immediately");
    }

    /**
     * The SSE route on its own, so "does not write a comment" cannot be answered by the whole file.
     *
     * <p>{@code sendComment} legitimately appears further down, inside {@code beat} - which is the
     * entire point - so searching the source for it would make the assertion in
     * {@link #theTimerOnlyDecidesWhen} permanently false.</p>
     */
    private String followRoute() {
        final int from = source.indexOf("config.routes.sse(\"/api/services/{name}/logs\"");
        assertTrue(from > 0, "the log follow route is gone or its path changed. If it moved, this"
                + " test moves with it - a check that cannot find its subject stops running and"
                + " says nothing about it");
        final int to = source.indexOf("config.routes.post(\"/api/services/{name}/console\"");
        assertTrue(to > from, "the console route is gone or has moved above the follow; this"
                + " test brackets the follow route and needs both ends");
        return source.substring(from, to);
    }

    /** The body of {@code beat}, for the same reason: the orderings inside it are its own. */
    private String beatMethod() {
        final int from = source.indexOf("private void beat(final SseClient client");
        assertTrue(from > 0, "WorkerApi#beat is gone. If the heartbeat went back to being written"
                + " inline, that is the change this file exists to argue with");
        final int to = source.indexOf("\n    private void goneOnShutdown(");
        assertTrue(to > from, "WorkerApi#goneOnShutdown is gone or has moved above beat; this test"
                + " brackets one method and needs both ends");
        return source.substring(from, to);
    }

    /**
     * Where {@code token} is, refusing {@code -1}.
     *
     * <p>Not {@code indexOf} at the call site: a token that is not there answers -1, which is
     * smaller than every real position - so an ordering assertion turns <b>green</b> the moment the
     * call it is protecting is deleted.</p>
     */
    private static int at(final String haystack, final String token) {
        final int index = haystack.indexOf(token);
        assertTrue(index >= 0, "WorkerApi no longer contains `" + token + "`. If it was removed the"
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
