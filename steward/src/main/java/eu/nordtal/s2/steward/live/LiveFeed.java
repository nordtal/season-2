package eu.nordtal.s2.steward.live;

import eu.nordtal.s2.common.json.Json;
import eu.nordtal.s2.common.time.Scheduler;
import eu.nordtal.s2.common.time.Waiting;
import eu.nordtal.s2.database.notify.Doorbell;
import eu.nordtal.s2.internalapi.sse.Follows;
import io.javalin.http.sse.SseClient;
import java.io.Closeable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The browser's one live stream: every topic read again in full when the hub rings, a changed one announced.
 *
 * Nothing is read while nobody listens, and a topic read for the first time since then is recorded, not announced.
 */
public final class LiveFeed implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LiveFeed.class);

    /** How often the timed topics are read while somebody listens: steward-agent's numbers, which no signal covers. */
    static final Duration TIMED = Duration.ofSeconds(10);

    /** The shortest gap between two passes, so a burst of signals costs one pass a second. */
    static final Duration GAP = Duration.ofSeconds(1);

    private static final String SIGNED_OUT = "this session ended - sign in again to stay live";

    private final Map<Topic, Supplier<?>> readings = new EnumMap<>(Topic.class);
    private final Map<Topic, String> versions = new ConcurrentHashMap<>();

    /** The topics whose last read failed, so a streak of failures warns once. */
    private final Set<Topic> failing = ConcurrentHashMap.newKeySet();

    private final Set<Subscription> subscribers = ConcurrentHashMap.newKeySet();
    private final Doorbell bell = new Doorbell();
    private final Follows follows;
    private final Waiting waiting;
    private volatile boolean running = true;
    private @Nullable Thread loop;

    public LiveFeed(final Waiting waiting, final Scheduler scheduler) {
        this.waiting = waiting;
        this.follows = new Follows("Steward", scheduler);
    }

    /** Registers what one topic reads; before {@link #start()}, and once per topic. */
    public synchronized void watch(final Topic topic, final Supplier<?> reading) {
        if (loop != null) {
            throw new IllegalStateException("a topic watched after the start would never be read");
        }
        if (readings.putIfAbsent(topic, reading) != null) {
            throw new IllegalStateException(topic + " is watched twice");
        }
    }

    /** Starts the passes on a daemon thread of their own. */
    public synchronized void start() {
        if (loop != null) {
            throw new IllegalStateException("the live feed has already been started");
        }
        final Thread thread = new Thread(this::run, "steward-live");
        thread.setDaemon(true);
        loop = thread;
        thread.start();
    }

    /** Asks for a pass over every topic, as a signal on the hub does; rings that pile up make one pass. */
    public void ring() {
        bell.ring();
    }

    /**
     * Keeps {@code client} open and writes every change into it, until it leaves or {@code signedIn} says no.
     *
     * @param signedIn asked on every heartbeat and at most once a second while changes flow
     */
    public void serve(final SseClient client, final BooleanSupplier signedIn) {
        final Subscription subscription = subscribe();
        follows.serve(client, "live", subscription, new Follows.Wanted(signedIn, SIGNED_OUT), sink -> {
            for (Optional<LiveEvent> next = subscription.next(); next.isPresent(); next = subscription.next()) {
                sink.send("change", Json.encode(next.get()));
            }
        });
    }

    /** A new reader, rung for at once so the versions it is compared against are taken now. */
    Subscription subscribe() {
        final Subscription subscription = new Subscription();
        subscribers.add(subscription);
        bell.ring();
        return subscription;
    }

    /**
     * Reads every topic a pass covers and announces the ones whose answer changed.
     *
     * @param signalled whether the hub rang, which covers every topic; otherwise only the timed ones are read
     */
    void pass(final boolean signalled) {
        if (subscribers.isEmpty()) {
            // Versions taken for nobody would be stale by the next reader, and announce what it already has.
            versions.clear();
            return;
        }
        final Map<Topic, Supplier<?>> due = new EnumMap<>(Topic.class);
        synchronized (this) {
            readings.forEach((topic, reading) -> {
                if (signalled || topic.timed()) {
                    due.put(topic, reading);
                }
            });
        }
        due.forEach(this::read);
    }

    private void read(final Topic topic, final Supplier<?> reading) {
        final String version;
        try {
            version = versionOf(reading.get());
        } catch (final RuntimeException unreadable) {
            // The browser keeps what it has; the next pass tries again, and only the first failure of a streak warns.
            if (!running) {
                return;
            }
            if (failing.add(topic)) {
                log.warn("the live topic {} could not be read", topic, unreadable);
            } else {
                log.debug("the live topic {} still cannot be read", topic, unreadable);
            }
            return;
        }
        failing.remove(topic);
        final @Nullable String before = versions.put(topic, version);
        if (before != null && !before.equals(version)) {
            final LiveEvent event = new LiveEvent(topic, version);
            subscribers.forEach(subscriber -> subscriber.offer(event));
        }
    }

    /** The first twelve hex digits of the answer's SHA-256: enough to tell two answers apart. */
    static String versionOf(final @Nullable Object answer) {
        try {
            final byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(Json.encode(answer == null ? Map.of() : answer).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (final NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("every JVM has SHA-256", impossible);
        }
    }

    private void run() {
        while (running) {
            final boolean rang;
            try {
                rang = bell.await(TIMED);
            } catch (final InterruptedException stopping) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                pass(rang);
            } catch (final RuntimeException failed) {
                log.warn("a pass of the live feed failed; the next one tries again", failed);
            }
            if (!waiting.sleep(GAP)) {
                return;
            }
        }
    }

    /** Ends every stream before Jetty stops, then the passes. */
    @Override
    public void close() {
        running = false;
        follows.close();
        final Thread thread = loop;
        if (thread != null) {
            thread.interrupt();
        }
    }

    /** One reader's queue of changes; closed when the stream ends, whoever ends it. */
    final class Subscription implements Closeable {

        /** One place in the queue: a change, or none for the end. */
        private record Item(@Nullable LiveEvent event) {}

        private final LinkedBlockingQueue<Item> queue = new LinkedBlockingQueue<>();

        private void offer(final LiveEvent event) {
            queue.add(new Item(event));
        }

        /** The next change, waiting for it; empty once the stream is closed or its thread interrupted. */
        Optional<LiveEvent> next() {
            try {
                return Optional.ofNullable(queue.take().event());
            } catch (final InterruptedException ended) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }

        /** The next change if one is waiting, without waiting; for a test. */
        @Nullable
        LiveEvent poll() {
            final @Nullable Item next = queue.poll();
            return next == null ? null : next.event();
        }

        @Override
        public void close() {
            subscribers.remove(this);
            queue.add(new Item(null));
        }
    }
}
