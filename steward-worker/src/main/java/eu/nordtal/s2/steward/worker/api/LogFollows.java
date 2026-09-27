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
 * The live console SSE stream: one open follow per browser tab, ended before Jetty stops.
 *
 * A follow still open when Jetty stopped made Javalin throw in a tight loop, so {@link #close()} ends them first.
 */
final class LogFollows {

    private static final Logger log = LoggerFactory.getLogger(LogFollows.class);

    /**
     * How often an open log follow says something, even when the container has not.
     *
     * Jetty drops a silent connection after thirty seconds, and a cancelled proxy only notices on the next write.
     */
    private static final Duration HEARTBEAT = Duration.ofSeconds(10);

    /** Log follows are long and blocking, so each gets a virtual thread. */
    private final ExecutorService followers = Executors.newVirtualThreadPerTaskExecutor();

    /** Every log follow that is still open, so that shutting down can end them first. */
    private final Set<DockerSocket.Stream> follows = ConcurrentHashMap.newKeySet();

    /**
     * Set before anything is shut down, so a follow that arrives meanwhile cleans up after itself.
     *
     * Read before the work and again after the stream is added: close() sees the stream or the follow sees the flag.
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

    /** The live log, over SSE: one direction, reconnects by itself, no reverse-proxy rule. */
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
                        // Asked before writing, so a gone browser does not make this read the whole backlog for nobody.
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
     * Earlier runs from the volume, oldest first, and an {@code end} event first when nothing older is left.
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
     * It catches everything, since a throw would cancel the periodic task, and skips a tick while one is out.
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
            // close() got there first; the follow is being torn down anyway.
            beating.set(false);
        }
    }

    /**
     * Ends a follow that arrived while this process was going away, leaving nothing open.
     *
     * It says so to the reader, so the page does not retry into a closed port.
     */
    private void goneOnShutdown(final SseClient client, final DockerSocket.Stream stream, final String name) {
        follows.remove(stream);
        closeQuietly(stream, name);
        client.sendEvent("gone", "steward-worker is shutting down");
        client.close();
    }

    /** Nobody is reading any more; thrown from the line consumer to leave the read, without a stack trace. */
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

    /** Stops every open follow before Jetty, then waits up to two seconds for their threads to close the emitters. */
    void close() {
        // First, so a request halfway through arranging a follow cleans up instead of meeting a dead executor.
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
