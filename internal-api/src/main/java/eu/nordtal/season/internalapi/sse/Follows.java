package eu.nordtal.season.internalapi.sse;

import eu.nordtal.season.common.time.Scheduler;
import io.javalin.http.sse.SseClient;
import java.io.Closeable;
import java.io.IOException;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Phaser;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Long server-sent event streams, each on its own thread. {@link #close()} ends them all before Jetty stops. */
public final class Follows implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Follows.class);

    /**
     * How often an open follow sends an event, even if its source does not.
     * Jetty drops a connection that is silent for thirty seconds.
     */
    private static final Duration HEARTBEAT = Duration.ofSeconds(10);

    /** How often a running follow asks whether it is still wanted. */
    private static final long RECHECK_NANOS = TimeUnit.SECONDS.toNanos(1);

    private final Scheduler scheduler;

    /** A party per running follow, and one for shutdown, which waits for the follows to close their emitters. */
    private final Phaser running = new Phaser(1);

    private final Set<Scheduler.Task> heartbeats = ConcurrentHashMap.newKeySet();

    private final Set<Closeable> sources = ConcurrentHashMap.newKeySet();

    /**
     * Set before shutdown starts.
     * A follow that arrives meanwhile cleans up itself.
     * It is read again after the source is added.
     */
    private volatile boolean closing;

    private final String owner;

    public Follows(final String owner, final Scheduler scheduler) {
        this.owner = owner;
        this.scheduler = scheduler;
    }

    /** What a follow writes into: one event at a time. It refuses once nobody wants the follow. */
    @FunctionalInterface
    public interface Sink {
        void send(String event, String data);
    }

    /** The work of a follow on its own thread. It ends when the source closes or {@link Sink#send} refuses. */
    @FunctionalInterface
    public interface Pump {
        void run(Sink sink) throws IOException;
    }

    /**
     * Whether the reader of a follow may still read it, and what the reader is told if not.
     *
     * @param check asked at most once a second while events flow, and on every heartbeat. A failure answers no.
     */
    public record Wanted(BooleanSupplier check, String goneSentence) {

        /** A follow that only its reader, its source or a shutdown ends. */
        public static final Wanted ALWAYS = new Wanted(() -> true, "");
    }

    /**
     * Keeps {@code client} open and runs {@code pump} into it on a thread of its own.
     *
     * @param name what is followed, for the heartbeat and the log
     * @param source closed when the follow ends
     */
    public void serve(
            final SseClient client, final String name, final Closeable source, final Wanted wanted, final Pump pump) {
        client.keepAlive();
        sources.add(source);
        if (closing) {
            // close() may have walked {@code sources} before this line added the source.
            goneOnShutdown(client, source, name);
            return;
        }
        final Scheduler.Task heartbeat;
        try {
            heartbeat = scheduler.every(HEARTBEAT, HEARTBEAT, () -> beat(client, name, wanted));
        } catch (final RejectedExecutionException rejected) {
            goneOnShutdown(client, source, name);
            return;
        }
        heartbeats.add(heartbeat);
        client.onClose(() -> {
            heartbeat.cancel();
            heartbeats.remove(heartbeat);
            closeQuietly(source, name);
        });
        running.register();
        try {
            scheduler.execute(() -> {
                try {
                    pump(client, name, source, wanted, pump);
                } finally {
                    running.arriveAndDeregister();
                }
            });
        } catch (final RejectedExecutionException rejected) {
            running.arriveAndDeregister();
            heartbeat.cancel();
            goneOnShutdown(client, source, name);
        }
    }

    /** The follow's thread: it sends every event until the reader, the check or the source ends it. */
    private void pump(
            final SseClient client, final String name, final Closeable source, final Wanted wanted, final Pump pump) {
        final long[] checked = {System.nanoTime()};
        try {
            pump.run((event, data) -> {
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

    /** Returns whether the follow is still wanted. A failure to ask answers no. */
    private static boolean stillWanted(final Wanted wanted, final String name) {
        try {
            return wanted.check().getAsBoolean();
        } catch (final RuntimeException gone) {
            log.warn("could not re-check who follows {}, so the follow is being ended: {}", name, gone.getMessage());
            return false;
        }
    }

    /** One heartbeat on a thread of its own, so that a reader who stopped reading blocks no other follow. */
    private void beat(final SseClient client, final String name, final Wanted wanted) {
        try {
            // A quiet source has nothing to re-check on, so the heartbeat asks too.
            if (!stillWanted(wanted, name)) {
                client.sendEvent("gone", wanted.goneSentence());
                client.close();
                return;
            }
            client.sendComment("following " + name);
        } catch (final RuntimeException e) {
            log.debug("the heartbeat for {} could not be written", name, e);
        }
    }

    /** Ends a follow that arrived during shutdown and sends {@code gone}, so that the reader does not retry. */
    private void goneOnShutdown(final SseClient client, final Closeable source, final String name) {
        sources.remove(source);
        closeQuietly(source, name);
        client.sendEvent("gone", owner + " is shutting down");
        client.close();
    }

    /** Thrown from the sink to leave the pump, without a stack trace. */
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
        // This runs first, so that a request that is arranging a follow cleans up instead of meeting a dead executor.
        closing = true;
        heartbeats.forEach(Scheduler.Task::cancel);
        for (final Closeable source : sources) {
            closeQuietly(source, "a follow still open at shutdown");
        }
        try {
            running.awaitAdvanceInterruptibly(running.arrive(), 2, TimeUnit.SECONDS);
        } catch (final TimeoutException e) {
            log.warn("a follow was still running two seconds into shutdown");
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
