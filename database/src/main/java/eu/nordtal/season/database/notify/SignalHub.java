package eu.nordtal.season.database.notify;

import eu.nordtal.season.common.time.NetworkTime;
import eu.nordtal.season.common.time.Waiting;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * A process's one {@code LISTEN} connection: every refresh runs on connect, on every signal and every quiet minute.
 * The channel decides what is listened on, never which refresh runs. A refresh runs on the hub's thread and must be
 * quick; one that takes long rings a {@link Doorbell} or hands its work to its own executor.
 */
public final class SignalHub implements AutoCloseable {

    /** How long a quiet connection waits before every refresh runs anyway: the guarantee behind a lost signal. */
    public static final Duration RECONCILIATION = Duration.ofMinutes(1);

    /** How long to wait before reconnecting after a failure. */
    private static final Duration RECONNECT_BACKOFF = Duration.ofSeconds(5);

    /** One thing to re-read, the channel whose signal it waits for, and a name for the log line when it fails. */
    private record Refresh(Channel channel, String what, Runnable task) {}

    private final Notifications.Connector connector;
    private final String name;
    private final Logger logger;
    private final Duration reconciliation;
    private final Duration reconnectBackoff;
    private final Waiting waiting;

    private final List<Refresh> refreshes = new ArrayList<>();
    private final AtomicReference<Notifications> current = new AtomicReference<>();
    private volatile boolean running = true;
    private volatile @Nullable Thread thread;

    /**
     * Creates a hub; nothing listens until {@link #start()}.
     *
     * @param name the thread's and the log lines' name, naming the process
     */
    public SignalHub(final Notifications.Connector connector, final String name, final Logger logger) {
        this(connector, name, logger, RECONCILIATION, RECONNECT_BACKOFF);
    }

    /**
     * Creates a hub on a dedicated, unpooled connection of its own.
     *
     * @param socketTimeoutSeconds bounds a peer that has gone away without closing
     * @param name                 the thread's, the connection's and the log lines' name, naming the process
     */
    public static SignalHub open(
            final String jdbcUrl,
            final String username,
            final String password,
            final int socketTimeoutSeconds,
            final String name,
            final Logger logger) {
        return new SignalHub(
                PostgresNotifications.connector(jdbcUrl, username, password, socketTimeoutSeconds, name), name, logger);
    }

    SignalHub(
            final Notifications.Connector connector,
            final String name,
            final Logger logger,
            final Duration reconciliation,
            final Duration reconnectBackoff) {
        this.connector = Objects.requireNonNull(connector, "connector");
        this.name = Objects.requireNonNull(name, "name");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.reconciliation = Objects.requireNonNull(reconciliation, "reconciliation");
        this.reconnectBackoff = Objects.requireNonNull(reconnectBackoff, "reconnectBackoff");
        this.waiting = Waiting.on(NetworkTime.clock());
    }

    /**
     * Registers a re-read that waits for {@code channel}; refused once the hub has started.
     *
     * @param what a name for the log line when it fails, such as "the season phase"
     * @param task the re-read itself; safe to run repeatedly, from the hub's thread
     */
    public synchronized void on(final Channel channel, final String what, final Runnable task) {
        if (thread != null) {
            throw new IllegalStateException("A refresh registered after start would miss the connection's LISTEN");
        }
        refreshes.add(new Refresh(
                Objects.requireNonNull(channel, "channel"),
                Objects.requireNonNull(what, "what"),
                Objects.requireNonNull(task, "task")));
    }

    /**
     * Registers a re-read of rows that hands them on only when they differ from the last ones handed on or seen.
     * Every process layers stored rows over what it ships this way; a quiet reconciliation reads and calls nothing.
     *
     * @param seen  what the process already runs with, or {@code null} to hand on the first read
     * @param read  the rows, read in full; a read that throws leaves {@code seen} as it was
     * @param apply takes rows that changed, on the hub's thread
     */
    public <T> void watch(
            final Channel channel,
            final String what,
            final @Nullable T seen,
            final Supplier<T> read,
            final Consumer<T> apply) {
        Objects.requireNonNull(read, "read");
        Objects.requireNonNull(apply, "apply");
        final AtomicReference<@Nullable T> last = new AtomicReference<>(seen);
        on(channel, what, () -> {
            final T now = read.get();
            if (!now.equals(last.get())) {
                apply.accept(now);
                last.set(now);
            }
        });
    }

    /** Returns the channels this hub listens on: the union of every registration. */
    public synchronized Set<Channel> channels() {
        final Set<Channel> channels = EnumSet.noneOf(Channel.class);
        refreshes.forEach(refresh -> channels.add(refresh.channel()));
        return channels;
    }

    /** Starts listening on its own daemon thread; calling this twice, or with nothing registered, is refused. */
    public synchronized void start() {
        if (thread != null) {
            throw new IllegalStateException("This hub has already been started");
        }
        if (refreshes.isEmpty()) {
            throw new IllegalStateException("A hub with nothing to refresh would wake up and do nothing");
        }
        final Thread hubThread = new Thread(this::run, name);
        hubThread.setDaemon(true);
        this.thread = hubThread;
        hubThread.start();
    }

    /** Runs the connect, re-read and wait loop. */
    void run() {
        final Set<Channel> channels = channels();
        final List<Refresh> registered = registered();
        while (running) {
            try (Notifications notifications = connector.listen(channels)) {
                current.set(notifications);
                logger.info("{} is listening on {}", name, channels);
                while (running) {
                    // Before every wait: a change made while disconnected is never announced again.
                    refreshAll(registered);
                    notifications.awaitNotification(reconciliation);
                }
            } catch (final SQLException exception) {
                if (!running) {
                    break;
                }
                logger.warn(
                        "{} lost its connection; retrying in {}s, with nothing re-read until then",
                        name,
                        reconnectBackoff.toSeconds(),
                        exception);
                if (!sleepBeforeRetry()) {
                    break;
                }
            } catch (final RuntimeException exception) {
                if (!running) {
                    break;
                }
                logger.error("{} failed unexpectedly; retrying in {}s", name, reconnectBackoff.toSeconds(), exception);
                if (!sleepBeforeRetry()) {
                    break;
                }
            } finally {
                current.set(null);
            }
        }
        logger.info("{} has stopped", name);
    }

    private synchronized List<Refresh> registered() {
        return List.copyOf(refreshes);
    }

    private void refreshAll(final List<Refresh> registered) {
        for (final Refresh refresh : registered) {
            try {
                refresh.task().run();
            } catch (final RuntimeException failure) {
                logger.warn("Could not refresh {}; the hub tries again on the next signal", refresh.what(), failure);
            }
        }
    }

    /** Returns {@code false} when the wait was interrupted, which means stop. */
    private boolean sleepBeforeRetry() {
        return waiting.sleep(reconnectBackoff) && running;
    }

    /** Stops the loop and closes the connection under the blocking wait, so shutdown is immediate. */
    @Override
    public void close() {
        running = false;

        final Notifications open = current.getAndSet(null);
        if (open != null) {
            open.close();
        }

        final Thread hubThread = this.thread;
        if (hubThread != null) {
            hubThread.interrupt();
        }
    }
}
