package eu.nordtal.s2.internalapi.sse;

import io.javalin.http.sse.SseClient;
import java.io.Closeable;
import java.io.IOException;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Long server-sent event streams: each on its own virtual thread, kept talking, and all ended before Jetty stops.
 *
 * A follow still open when Jetty stopped made Javalin throw in a tight loop, so {@link #close()} ends them first.
 */
public final class Follows implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Follows.class);

    /**
     * How often an open follow says something, even when its source has not.
     *
     * Jetty drops a silent connection after thirty seconds, and a cancelled proxy only notices on the next write.
     */
    private static final Duration HEARTBEAT = Duration.ofSeconds(10);

    /** How often a running follow asks whether it is still wanted, since a follow outlives the check at open. */
    private static final long RECHECK_NANOS = TimeUnit.SECONDS.toNanos(1);

    /** Follows are long and blocking, so each gets a virtual thread. */
    private final ExecutorService followers = Executors.newVirtualThreadPerTaskExecutor();

    /** Every source still open, so that shutting down can end them first. */
    private final Set<Closeable> sources = ConcurrentHashMap.newKeySet();

    /**
     * Set before anything is shut down, so a follow that arrives meanwhile cleans up after itself.
     *
     * Read again after the source is added: close() sees the source or the follow sees the flag.
     */
    private volatile boolean closing;

    private final ScheduledExecutorService heartbeats;

    private final String owner;

    /** @param owner the process, named in the {@code gone} event a follow gets when it shuts down */
    public Follows(final String owner) {
        this.owner = owner;
        this.heartbeats = Executors.newSingleThreadScheduledExecutor(runnable -> {
            final Thread thread = new Thread(runnable, owner + "-sse-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** What a follow writes into: one event at a time, refused once nobody wants it any more. */
    @FunctionalInterface
    public interface Sink {
        void send(String event, String data);
    }

    /** The follow's work on its own thread, ended by its source closing or by {@link Sink#send} refusing. */
    @FunctionalInterface
    public interface Pump {
        void run(Sink sink) throws IOException;
    }

    /**
     * Whether whoever reads a follow may still read it, and what they are told when not.
     *
     * @param check asked at most once a second while events flow and on every heartbeat; a failure answers no
     */
    public record Wanted(BooleanSupplier check, String goneSentence) {

        /** A follow nobody ends but its reader, the source or a shutdown. */
        public static final Wanted ALWAYS = new Wanted(() -> true, "");
    }

    /**
     * Keeps {@code client} open and runs {@code pump} into it on a thread of its own.
     *
     * @param name what is followed, for the heartbeat and the log
     * @param source closed when the follow ends, whoever ends it
     */
    public void serve(
            final SseClient client, final String name, final Closeable source, final Wanted wanted, final Pump pump) {
        client.keepAlive();
        sources.add(source);
        if (closing) {
            // close() may have walked `sources` a moment before this line put the source in it.
            goneOnShutdown(client, source, name);
            return;
        }
        final ScheduledFuture<?> heartbeat;
        final AtomicBoolean beating = new AtomicBoolean();
        try {
            heartbeat = heartbeats.scheduleWithFixedDelay(
                    () -> beat(client, name, beating, wanted),
                    HEARTBEAT.toSeconds(),
                    HEARTBEAT.toSeconds(),
                    TimeUnit.SECONDS);
        } catch (final RejectedExecutionException rejected) {
            goneOnShutdown(client, source, name);
            return;
        }
        client.onClose(() -> {
            heartbeat.cancel(false);
            closeQuietly(source, name);
        });
        try {
            final var _ = followers.submit(() -> pump(client, name, source, wanted, pump));
        } catch (final RejectedExecutionException rejected) {
            heartbeat.cancel(false);
            goneOnShutdown(client, source, name);
        }
    }

    /** The follow's own thread: every event until the reader, the check or the source ends it. */
    private void pump(
            final SseClient client, final String name, final Closeable source, final Wanted wanted, final Pump pump) {
        final long[] checked = {System.nanoTime()};
        try {
            pump.run((event, data) -> {
                // Asked before writing, so a gone reader does not make the source read on for nobody.
                if (client.terminated()) {
                    throw new Gone();
                }
                final long now = System.nanoTime();
                if (now - checked[0] >= RECHECK_NANOS) {
                    if (!stillWanted(wanted, name)) {
                        client.sendEvent("gone", wanted.goneSentence());
                        throw new Gone();
                    }
                    checked[0] = now;
                }
                client.sendEvent(event, data);
            });
        } catch (final Gone gone) {
            log.debug("the follow of {} ended with whoever was reading it", name);
        } catch (final IOException e) {
            log.debug("the follow of {} ended", name, e);
        } finally {
            sources.remove(source);
            closeQuietly(source, name);
            client.close();
        }
    }

    /** Whether the follow is still wanted; a failure to ask answers no. */
    private static boolean stillWanted(final Wanted wanted, final String name) {
        try {
            return wanted.check().getAsBoolean();
        } catch (final RuntimeException gone) {
            log.warn("could not re-check who follows {}, so the follow is being ended: {}", name, gone.getMessage());
            return false;
        }
    }

    /**
     * One heartbeat, written somewhere it is allowed to block.
     *
     * It catches everything, since a throw would cancel the periodic task, and skips a tick while one is out.
     */
    private void beat(final SseClient client, final String name, final AtomicBoolean beating, final Wanted wanted) {
        if (!beating.compareAndSet(false, true)) {
            return;
        }
        try {
            final var _ = followers.submit(() -> {
                try {
                    // A quiet source sends nothing to re-check on, so the heartbeat asks too.
                    if (!stillWanted(wanted, name)) {
                        client.sendEvent("gone", wanted.goneSentence());
                        client.close();
                        return;
                    }
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

    /** Ends a follow that arrived while this process was going away, and says so, so the reader does not retry. */
    private void goneOnShutdown(final SseClient client, final Closeable source, final String name) {
        sources.remove(source);
        closeQuietly(source, name);
        client.sendEvent("gone", owner + " is shutting down");
        client.close();
    }

    /** Nobody is reading any more; thrown from the sink to leave the pump, without a stack trace. */
    private static final class Gone extends RuntimeException {

        Gone() {
            super(null, null, false, false);
        }
    }

    private static void closeQuietly(final Closeable source, final String name) {
        try {
            source.close();
        } catch (final IOException e) {
            log.debug("closing the source of {}", name, e);
        }
    }

    /** Stops every open follow before Jetty, then waits up to two seconds for their threads to close the emitters. */
    @Override
    public void close() {
        // First, so a request halfway through arranging a follow cleans up instead of meeting a dead executor.
        closing = true;
        heartbeats.shutdownNow();
        for (final Closeable source : sources) {
            closeQuietly(source, "a follow still open at shutdown");
        }
        followers.shutdownNow();
        try {
            if (!followers.awaitTermination(2, TimeUnit.SECONDS)) {
                log.warn("a follow was still running two seconds into shutdown");
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
