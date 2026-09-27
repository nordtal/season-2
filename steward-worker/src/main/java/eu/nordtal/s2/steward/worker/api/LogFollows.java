package eu.nordtal.s2.steward.worker.api;

import eu.nordtal.s2.steward.worker.docker.Docker;
import eu.nordtal.s2.steward.worker.docker.DockerSocket;
import eu.nordtal.s2.steward.worker.docker.LogFrames;
import io.javalin.http.sse.SseClient;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The live console SSE stream: one open follow per browser tab, and the shutdown ordering that ends them cleanly.
 *
 * Why a set, rather than letting Jetty tidy up: a follow that was still open when Jetty stopped was not a tidy
 * ending. Javalin then closes the emitter against a request Jetty has already recycled, the close throws a
 * {@link NullPointerException}, and Javalin's own exception mapper throws a second one while trying to read a
 * header off that same dead request - so the failure cannot be reported and is retried. Measured on this host: about
 * sixty thousand of those a second, for as long as the process lived. In a test JVM that is an
 * {@code OutOfMemoryError} in the build; on the host it is a container that will not go down and a disk filling
 * with one repeated line.
 *
 * Closing the docker stream is what ends the read, which runs the follow's own {@code finally} and closes the
 * emitter while Jetty is still alive - the ordinary path, taken deliberately instead of being raced into.
 */
final class LogFollows {

    private static final Logger log = LoggerFactory.getLogger(LogFollows.class);

    /**
     * How often an open log follow says something, even when the container has not.
     *
     * A connection nothing is written on is dropped after thirty seconds - Jetty's own idle timeout - and Javalin's
     * {@code keepAlive()} does not write anything; it only holds the request open. A healthy Minecraft server is
     * quiet for minutes at a time, so the log view of one was closed under the watcher half a minute after they
     * opened it, and looked exactly like a server that had stopped talking.
     *
     * It matters a second time at the other end: {@code steward-ui} proxies this stream, and when its browser goes
     * away it cancels its side. The JDK's HTTP client only tears a connection down when something next happens on
     * it, so on a silent stream that cancellation arrives nowhere and this process keeps a docker log stream open
     * for a tab nobody has. A comment every ten seconds is what lets both ends notice each other.
     */
    private static final Duration HEARTBEAT = Duration.ofSeconds(10);

    /** Log follows are long and blocking; each one gets a thread of its own, and they are cheap. */
    private final ExecutorService followers = Executors.newVirtualThreadPerTaskExecutor();

    /** Every log follow that is still open, so that shutting down can end them first. */
    private final Set<DockerSocket.Stream> follows = ConcurrentHashMap.newKeySet();

    /**
     * Set before anything is shut down, so a request already in flight can be told to give up.
     *
     * Without it a follow could register its stream between {@link #close()} iterating {@link #follows} and the
     * executors refusing new work - and then be rejected by the scheduler with nothing yet arranged to clean it up.
     * The flag is set first and read twice: once before any work is done, and once after the stream has been added,
     * which is what makes the pair of them a handover rather than a race - either close() sees the stream, or the
     * follow sees the flag.
     */
    private volatile boolean closing;

    private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor(runnable -> {
        final Thread thread = new Thread(runnable, "steward-worker-sse-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    private final Docker docker;
    private final LogArchive archive;

    LogFollows(final Docker docker, final LogArchive archive) {
        this.docker = docker;
        this.archive = archive;
    }

    /** The live log. SSE rather than a websocket: one direction, reconnects by itself, no reverse-proxy rule. */
    void serve(final SseClient client, final String containerId, final String name) {
        client.keepAlive();
        final boolean multiplexed = !docker.inspect(containerId).tty();
        final String tail = client.ctx().queryParamAsClass("tail", String.class).getOrDefault("200");
        final String since = client.ctx().queryParam("since");

        final DockerSocket.Stream stream = docker.logs(containerId, true, tail, since);
        follows.add(stream);
        if (closing) {
            // close() may have walked `follows` a moment before this line put the stream in it.
            goneOnShutdown(client, stream, name);
            return;
        }
        final ScheduledFuture<?> heartbeat;
        final AtomicBoolean beating = new AtomicBoolean();
        try {
            heartbeat = heartbeats.scheduleWithFixedDelay(
                    () -> beat(client, name, beating), HEARTBEAT.toSeconds(), HEARTBEAT.toSeconds(), TimeUnit.SECONDS);
        } catch (final RejectedExecutionException rejected) {
            goneOnShutdown(client, stream, name);
            return;
        }
        client.onClose(() -> {
            heartbeat.cancel(false);
            closeQuietly(stream, name);
        });
        try {
            final var _ = followers.submit(() -> {
                try {
                    backlog(client, containerId, name, tail, multiplexed);
                    LogFrames.read(stream.body(), multiplexed, line -> {
                        // Asked before writing: a gone browser must not make this read the whole backlog for nobody.
                        if (client.terminated()) {
                            throw new Gone();
                        }
                        client.sendEvent("line", line);
                    });
                } catch (final Gone gone) {
                    log.debug("the follow of {} ended with whoever was watching it", name);
                } catch (final IOException e) {
                    log.debug("the log follow for {} ended", name, e);
                } finally {
                    follows.remove(stream);
                    closeQuietly(stream, name);
                    client.close();
                }
            });
        } catch (final RejectedExecutionException rejected) {
            heartbeat.cancel(false);
            goneOnShutdown(client, stream, name);
        }
    }

    /**
     * What the console shows below the live lines when Docker alone cannot fill the window.
     *
     * The earlier runs out of the volume, oldest first so that the browser's arrival order stays the log's order,
     * and an {@code end} event first of all when nothing older is left anywhere. The follow that comes after starts
     * with the same {@code tail}, so the two meet where Docker's own log begins.
     */
    private void backlog(
            final SseClient client,
            final String containerId,
            final String name,
            final String tail,
            final boolean multiplexed) {
        final int wanted;
        try {
            wanted = Integer.parseInt(tail);
        } catch (final NumberFormatException all) {
            return;
        }
        final List<String> dockerLines = docker.recentLines(containerId, wanted, multiplexed);
        if (dockerLines.size() >= wanted) {
            return;
        }
        final LogArchive.Backlog earlier = archive.before(name, oldest(dockerLines), wanted - dockerLines.size());
        if (earlier.exhausted()) {
            client.sendEvent("end", "Nothing older.");
        }
        for (final LogArchive.Run run : earlier.runs()) {
            client.sendEvent("run", run.label());
            for (final String line : run.lines()) {
                if (client.terminated()) {
                    throw new Gone();
                }
                client.sendEvent("line", line);
            }
        }
    }

    /** The timestamp Docker put in front of the first line, or now when there is none. */
    static Instant oldest(final List<String> dockerLines) {
        if (!dockerLines.isEmpty()) {
            final String first = dockerLines.getFirst();
            final int space = first.indexOf(' ');
            if (space > 0) {
                try {
                    return Instant.parse(first.substring(0, space));
                } catch (final DateTimeParseException notStamped) {
                    // falls through to now
                }
            }
        }
        return Instant.now();
    }

    /**
     * One heartbeat, written somewhere it is allowed to block.
     *
     * An unchecked exception out of {@code sendComment} used to escape the {@code Runnable}, and
     * {@code ScheduledThreadPoolExecutor} then cancels that periodic task permanently - the follow's heartbeat never
     * returns and Jetty drops it thirty seconds later. The {@code catch} below is what prevents that.
     *
     * {@code beating} keeps the handover from becoming a queue of writes nobody is reading: while one comment is
     * still on its way out, the next tick is skipped. A consumer that misses heartbeats because it is not reading is
     * one Jetty is about to close, which is the outcome that was wanted.
     */
    private void beat(final SseClient client, final String name, final AtomicBoolean beating) {
        if (!beating.compareAndSet(false, true)) {
            return;
        }
        try {
            final var _ = followers.submit(() -> {
                try {
                    client.sendComment("following " + name);
                } catch (final RuntimeException e) {
                    log.debug("the heartbeat for {} could not be written", name, e);
                } finally {
                    beating.set(false);
                }
            });
        } catch (final RejectedExecutionException rejected) {
            // close() got there first. The follow is being torn down anyway.
            beating.set(false);
        }
    }

    /**
     * Ends a follow that arrived while this process was going away, leaving nothing open.
     *
     * Both callers are races with {@link #close()}, and the reader deserves a sentence rather than a connection
     * that simply stops: an SSE client reconnects by itself, and "the server is going away" is what tells the page
     * to say so instead of retrying into a closed port.
     */
    private void goneOnShutdown(final SseClient client, final DockerSocket.Stream stream, final String name) {
        follows.remove(stream);
        closeQuietly(stream, name);
        client.sendEvent("gone", "steward-worker is shutting down");
        client.close();
    }

    /**
     * Nobody is reading this any more, thrown from inside the line consumer to get out of the read.
     *
     * It carries no stack trace: it is not a failure, it is the ordinary end of a follow, and it happens once per
     * closed tab.
     */
    private static final class Gone extends RuntimeException {

        Gone() {
            super(null, null, false, false);
        }
    }

    private static void closeQuietly(final DockerSocket.Stream stream, final String name) {
        try {
            stream.close();
        } catch (final IOException e) {
            log.debug("closing the log stream of {}", name, e);
        }
    }

    /**
     * Stops, and the order of these five lines is the whole of it.
     *
     * Every open follow is ended before Jetty is - see {@link #follows} for what happens when it is the other way
     * round. Closing the stream is what unblocks the read; the follow's own {@code finally} then closes the
     * emitter, which is why this waits for those threads rather than assuming they got there. Two seconds is far
     * longer than an interrupted read needs and short enough that nobody watches a container refuse to stop.
     */
    void close() {
        // FIRST: a request halfway through arranging a follow reads this and cleans up, not hit a dead executor.
        closing = true;
        heartbeats.shutdownNow();
        for (final DockerSocket.Stream stream : follows) {
            closeQuietly(stream, "a follow still open at shutdown");
        }
        followers.shutdownNow();
        try {
            if (!followers.awaitTermination(2, TimeUnit.SECONDS)) {
                log.warn("a log follow was still running two seconds into shutdown");
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
